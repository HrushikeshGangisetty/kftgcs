# Backend WebSocket Spec — data-integrity fixes for the backend team

This documents backend (`consumers.py` / `models.py`) changes required to close the data-integrity
issues found in the GCS ⇄ `wss://kftgcs.com/ws/telemetry/` audit. The **GCS app changes are already
implemented**; the items below are the matching backend half. File references are to the copies in
the repo root (`consumers.py`, `models.py`).

Priority: **A and B are the most important** — they stop phantom/duplicate missions and unauthenticated
tenant impersonation. The rest are correctness/robustness.

---

## A. Authenticate the socket (CRITICAL)

`TelemetryConsumer.connect()` (`consumers.py:50`) calls `self.accept()` with **no authentication**, and
`session_start` trusts a raw client-supplied `pilot_id` (`consumers.py:106`, `Pilot.objects.get(id=pilot_id)`).
`admin`/`superadmin` are derived from that pilot — so anyone who can reach the endpoint can impersonate any
pilot and write Vehicle/Mission/Telemetry/Summary rows into any tenant.

**Fix:** require an auth token (login/session token or signed JWT) via query string (`?token=…`) or the
`Sec-WebSocket-Protocol` subprotocol. In `connect()`, validate it, resolve the authenticated pilot, and
**reject `session_start` whose `pilot_id` ≠ the authenticated pilot** (or drop the client-supplied field
entirely and use the authenticated identity).

---

## B. Idempotent Vehicle & Mission — stop duplicate/phantom missions (CRITICAL)

Today `session_start` unconditionally runs `Vehicle.objects.create` (`consumers.py:149`) and
`Mission.objects.create` (`consumers.py:172`) on **every** socket open. Combined with the client sending
`session_start` on every `onOpen`, this means a mid-flight reconnect creates a *second* Vehicle and a
*second* Mission (new UUID), fragmenting telemetry across two missions; the first is then force-ended by
`disconnect()`.

The GCS now only opens a session **after takeoff**, and on a mid-flight reconnect it includes the field
**`resume_mission_id`** in `session_start` (the missionId it already holds). The backend must honour it:

- **Vehicle:** dedupe by `vehicle_id` (+ owning pilot/admin) with `get_or_create` instead of `create`.
  A given drone should map to one Vehicle row, updated in place.
- **Mission:** if `session_start` carries `resume_mission_id` and that Mission exists, belongs to this
  pilot, and is not yet ended → **reuse it** (re-send `mission_created` with the same id) instead of
  creating a new one. Only create a new Mission when there is no valid `resume_mission_id`.

```python
resume_id = data.get("resume_mission_id")
mission = None
if resume_id:
    mission = await sync_to_async(
        lambda: Mission.objects.filter(mission_id=resume_id, pilot=pilot, end_time__isnull=True).first()
    )()
if mission is None:
    mission = await sync_to_async(Mission.objects.create)(...)  # existing path
```

---

## C. Handle `drone_uid_update` (HIGH)

At connect the drone UID is not yet known, so `session_start` sends the placeholder
`drone_uid="SITL_DRONE_001"` / `vehicle_name="DRONE_<millis>"`. The real UID arrives later in a
`drone_uid_update` message — but the consumer has **no handler** for it, so it falls into the
`else: Unknown message type` branch (`consumers.py:412`) and the Vehicle identity stays the placeholder
forever.

**Fix:** add an `elif msg_type == "drone_uid_update":` branch that updates the session Vehicle's
`vehicle_id` (and `uin` if provided) from `data["drone_uid"]`.

---

## D. Reconcile the spray/telemetry field contract (HIGH)

Mismatches between what the GCS sends and what the backend reads/stores:

| Field | GCS sends | Backend does | Problem |
|---|---|---|---|
| `spray.tank_level` | **percent** (Int 0–100) | stored into `TelemetrySpray.tank_level_liters` (`consumers.py:309`, `models.py:338`) | A 40 % tank is persisted as "40 liters". **Rename column to `tank_level_percent`**, or convert percent→litres using tank capacity before storing. |
| `spray.consumed_liters` | litres (Float) | **not read / no column** | If the dashboard needs live consumed volume, add a `consumed_liters` column and read it; otherwise document that live consumed is summary-only. |
| `spray.flow_pulse` | *(not sent)* | read at `consumers.py:308` | Always null. Drop the read or have the GCS send it. |
| `battery.remaining` | *(not sent)* | read at `consumers.py:259` | Always null. Drop or send. |
| `mission_summary.average_speed`, `alerts_count` | sent | **not read** | Persist them or stop sending (`average_speed` is also hardcoded 0.0 on the client today). |

> Note re **Issue #2 (consumed litres in thousands):** `total_spray_used_liters` is stored verbatim from
> the GCS in **litres** — the backend performs no accumulation or unit conversion. If a dashboard shows
> thousands, the dashboard is either integrating `TelemetrySpray.spray_rate_lpm` (L/min) across ~1 Hz rows
> **without multiplying by the sample interval**, or summing `total_spray_used_liters` across the phantom
> missions from item B. Check the dashboard's consumed-volume query specifically.

---

## E. Make `client_id` dedup real (MEDIUM)

The GCS stamps a `client_id` UUID on offline-replayed messages "so the backend can deduplicate"
(`WebSocketManager.syncPendingMessages`), but the backend never reads it and `mission_event` uses plain
`objects.create` (`consumers.py:350`). A double-flush therefore inserts duplicate events.

**Fix:** add a nullable `client_id` (indexed/unique-per-mission) to `MissionEvent` (and any other replayed
model) and use `update_or_create`/`get_or_create` on it, ignoring duplicates.

---

## F. Atomic telemetry writes + safe key access (MEDIUM)

The six `*.objects.create(...)` calls in the `telemetry` handler (`consumers.py:237–310`) run outside a
transaction and index required keys directly (`data["position"]["lat"]`, `data["attitude"]["roll"]`,
`data["gps"]["satellites"]`, …). A single missing subkey raises mid-sequence: the earlier rows (Position,
Battery) commit, the later ones (Attitude/GPS/Status/Spray) are skipped, and the broad `except` at
`consumers.py:315` swallows it → the telemetry tables silently drift out of row-count sync for that
timestamp.

**Fix:** wrap the six creates in a single `transaction.atomic()` (via `sync_to_async`) and use `.get()`
with sensible defaults instead of hard indexing, so a frame is written all-or-nothing.

---

## G. Timestamp / timezone handling (MEDIUM)

`ts = make_aware(datetime.fromtimestamp(data["ts"] / 1000))` (`consumers.py:235`) builds a server-**local**
naive datetime and then re-interprets it in the active timezone — during a DST transition this raises
`AmbiguousTimeError`/`NonExistentTimeError` and drops the whole frame.

**Fix:** `ts = datetime.fromtimestamp(data["ts"] / 1000, tz=timezone.utc)` (import `from datetime import
timezone`). Also note `data["ts"]` is the **client device clock**; consider clamping to server-now if it is
implausibly far off.

---

## H. Protocol alignment (MEDIUM / LOW)

- **`session_ack`:** the client documents a `session_ack` handshake (`WebSocketManager` header comment) but
  the server never sends it; the client currently relies on a 3 s timeout fallback. Either emit
  `{"type":"session_ack"}` right after `session_start` succeeds, or drop it from the client contract.
- **`STATUS_STARTED`:** the client sends `mission_status=1 (STARTED)` but the backend only handles
  PAUSED/RESUMED/ENDED (`consumers.py:330–335`); missions jump CREATED→ENDED. Persist STARTED (set a
  `started_at`/status) if analytics need it.

---

## I. Defense in depth (OPTIONAL)

Even with the client-side takeoff gate, consider server-side pruning: a Mission that accumulates no
telemetry / never reports `armed=true` at altitude within N seconds can be auto-deleted or flagged, so a
buggy or spoofed client can't reintroduce phantom missions.

---

## Client behaviour the backend can now rely on (already shipped)

- `session_start` is sent **only after the drone actually takes off** (climbs > ~1.5 m) — no session for
  ground arm/disarm cycles.
- On a mid-flight reconnect, `session_start` includes **`resume_mission_id`** (see B).
- End/summary (`mission_status=ENDED`, `mission_event`, `mission_summary`) is sent **once per flight** and
  **only if a session was opened** — no orphan end/summary for un-taken-off flights.
- Telemetry frames with placeholder `lat==0 && lng==0` (pre-GPS-lock) are **not sent**.
