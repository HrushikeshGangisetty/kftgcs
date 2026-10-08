# Aquaculture Prawn prototype

## APK feature switch

The only switch is `FeatureFlags.enableAquaculture` in
`app/src/main/java/com/example/kftgcs/FeatureFlags.kt`.

Set the switch to **false** for a production APK. A connected operator then sees Select
Operation with Agriculture only. Agriculture opens the existing flying-method selector and GCS screens.
Aquaculture routes are not registered while the flag is false.
Set that single line to **true** and rebuild to expose Aquaculture. Return it to false
before building any production APK. Both application flavors use the same gate.

## Demonstration

1. Enable the flag and build the demo APK.
2. Connect a vehicle through the existing connection screen.
3. Select Aquaculture, then Prawn. Fish is disabled with Coming Soon.
4. Tap pond corners in boundary order, drag/select/delete vertices as needed, or use
   **Load sample pond**. The sample polygon is not a surveyed operating site.
5. Keep biomass at 320 kg, count at 60/kg, sessions at 3, hopper at 2 kg,
   and discharge at 0.05 kg/s. Select **Day 25** from the scrollable culture-day dropdown.
6. Press **Calculate & review**, review the feed estimate in the Mission tab, then **Generate mission**.
7. Open Setup, select **Day 75**, calculate and generate again. The polygon remains unchanged.

| Demo day | Rate | Daily feed | Feed/session | Hopper loads | Session boundary passes |
| --- | --- | --- | --- | --- | --- |
| 25 | 4.0% | 12.8 kg | 4.267 kg | 3 | 3 |
| 75 | 4.5% | 14.4 kg | 4.8 kg | 3 | 4 |

Day 75 also changes waypoint count, total distance, flight time, speed, and per-pass
discharge estimates. Use load and lap controls to inspect the generated mission.
Repeated laps occupy the same edge, so the map displays the selected lap and the
summary displays the complete session totals. Larger numbered cyan markers show sampled flight
waypoints, with WP 1 highlighted green as the start of the selected lap. A dark route outline
improves contrast on satellite imagery. Blue/yellow markers show the original pond vertices
and yellow outlines the pond.
The camera fits the pond after loading a sample or generating a mission so the inset is visible.

Input or boundary edits invalidate the calculation/preview and require review again.
Selecting another culture day clears a manual rate override so the day table takes effect.
The full-width **Hopper capacity (kg)** field in Feeding is saved automatically for valid,
positive entries and restored when the planner is reopened or the app restarts. Editing it
invalidates the prior preview and recalculating adjusts hopper loads. Invalid edits are not saved.

The screens use the application's navy/blue style, horizontal option cards and icon buttons.
Selection icons are 56 dp; section, button and planner icons are enlarged for readability.
Waypoint numbers refer to the selected lap's boundary points, rather than MAVLink item sequences.
The planning panel is on the left on landscape/tablet displays; narrow portrait layouts stack
the controls above the map. Setup and Mission tabs separate the configuration from the review.
Operator-facing DEMO labels have been removed. The calculation info button describes the
interim formulas and simulated discharge without repeating banners across the UI.

## Placeholder calculations and strategy

These are DEMO values, not scientifically validated feeding recommendations:

- Days 1–20: 3%; 21–40: 4%; 41–60: 5%; 61–80: 4.5%; 81–100: 3.5%; 101–120: 2.5%.
- Daily feed = operator-estimated biomass × rate / 100.
- Feed/session = daily feed / feeding sessions.
- Approximate individual weight = 1000 / count/kg grams.
- Loads = ceil(feed/session / hopper capacity). No biomass, survival, stocking density,
  or growth is inferred from count, culture day, or pond area.
- Pond area is derived from the polygon. An optional operator area can be displayed
  separately; it does not change geometry or infer biomass.
- Session passes = max(loads, ceil(feed/session / 1.5 kg)). The 1.5 kg average target
  is a replaceable demo heuristic, not a discharge calibration or a hard per-pass limit.
- Each load initially gets one lap. Extra laps go to the load with the largest
  feed per lap. Each individual load stays within the hopper capacity.
- Simulated feed ON duration/pass = load feed / discharge rate / load passes.
- Speed = min(base speed / (1 + session feed / 10), perimeter / ON duration per pass).
  This is also a demo heuristic. Infeasible speeds below 0.3 m/s are rejected.
- The pond-work contour is offset inward by the requested indentation, with a minimum of
  **3 m** and maximum of 50 m. The original pond polygon remains unchanged. Adjacent inward
  offset edge lines are intersected to obtain the flight contour. A small projection/coordinate
  rounding allowance keeps exported waypoints above the requested minimum clearance.
- Every flight contour segment must remain inside the pond and clear every original edge.
  Collapsed, crossing or insufficiently clear offsets are rejected rather than turned into a path.
  Very narrow/complex ponds may therefore require boundary adjustment before generation.
- The **inset perimeter**, not the original pond perimeter, drives flight distance and duration.
  Its edges are sampled at no more than the requested spacing, its corners are kept, and the
  last edge returns to the first vertex. There are no internal grid/coverage lines or shortcuts.

## Upload and prototype limits

**Feed actuator control is simulated only.** The UI explicitly labels this, and
MAVLink uploads contain HOME, TAKEOFF, DO_CHANGE_SPEED, boundary NAV_WAYPOINTs and RTL.
They send no sprayer/servo commands and do not physically discharge feed. The feed
hardware channel, control protocol, calibration and validated application schedule
were not provided. Add the confirmed actuator integration in the converter once
those are available; do not equate flight-only uploads with completed feeding.

Mission parameter 4 is command-specific: NAV_WAYPOINT/NAV_TAKEOFF use NaN to preserve
heading, while DO_CHANGE_SPEED and RTL use zero for unused fields. Previously sending NaN
for every command caused ArduPilot to reject the speed item with MAV_MISSION_INVALID_PARAM4.
This follows [ArduPilot's mission parameter validation](https://github.com/ArduPilot/ardupilot/blob/master/libraries/AP_Mission/AP_Mission.cpp).

The operator uploads one hopper load at a time through the existing
`SharedViewModel.uploadMission` ACK/progress/validation path. Upload requires a connected,
disarmed vehicle and an actual FC HOME_POSITION. No home coordinate is invented.
Each load returns home; land/refill and upload the next load manually. Upload does
not arm or start the aircraft. The existing GCS flight view is available after upload.

Only the pond-work waypoints follow the boundary. HOME/TAKEOFF and RTL remain standard
launch/return items and may be away from the polygon. Estimates exclude those transits,
climb/descent, acceleration, turns, refill and battery/endurance limits. NAV acceptance
radius and flight-controller corner handling mean this is a geometric planning demo,
not a guarantee of centimeter-accurate boundary tracking. No obstacle planning is added.

Preview edits do not clear an already uploaded aircraft mission. A later successful
upload replaces it. The Aquaculture ViewModel survives configuration changes while its
route exists; process-death recovery/persistent aquaculture templates are not included.
Only the drone hopper capacity is persisted locally; pond geometry and other culture inputs
remain transient.

Demo validation limits: culture days 1–120; sessions 1–24; positive finite quantities;
simple polygons with 3–200 distinct vertices and 1–5000 m edges; no date-line crossing;
speed 0.3–10 m/s; altitude 2–30 m; spacing 2–100 m; indentation 3–50 m; at most 100 passes/session,
2000 waypoints/load and 5000/session. The application uses only the biological demo
table and the separately replaceable mission strategy.

## Integration and replacement points

Existing files changed:

- `navigation/AppNavGraph.kt`: connection destination → operation selection; gated
  Aquaculture category/Prawn routes. Parameter-management connection keeps its existing
  explicit destination and is unaffected.
- `uimain/GcsMap.kt`: optional boundary waypoint overlay, compact pond markers and polygon edit lock, with
  defaults that preserve all existing callers' behavior.

New code is in `FeatureFlags.kt` and `aquaculture/{model,calculation,mission,repository,ui}`.
No changes are needed in Agriculture planning, connection transports, MAVLink upload
protocol, telemetry or existing database/DI architecture.

`FeedCalculationEngine` is the replacement boundary for validated biological logic.
Inject another implementation into `AquacultureViewModel`; maintain the units and
`AquacultureFeedResult` contract. `DemoMissionParameterPlanner` independently maps feed
to load/pass/speed/discharge parameters. `BoundaryMissionGenerator` accepts only a
polygon and geometric/timing parameters; it has no culture-day or biology dependency.
`BoundaryMissionConverter` owns MAVLink translation. `AquacultureMissionRepository`
bridges to the existing uploader and publishes the uploaded path for the existing GCS map.

## Verification

Run `gradlew.bat :app:testOriginalDebugUnitTest :app:assembleOriginalDebug`.
`AquacultureDemoTest` covers the day-table endpoints, weight conversion, rate overrides,
exact hopper capacities, day 25 vs 75, load conservation, deterministic generation,
closure/spacing/edge-only paths (including concave ponds), invalid inputs and self-crossing
polygons, MAVLink sequence/launch/RTL structure, and replacing the calculation engine.

Verification of the UI/inset pass completed on this host: original debug APK assembly,
all 15 JVM tests (including ten Aquaculture tests), and all five `AquacultureUiTest`
instrumentation tests on a separate API 35 tablet emulator. UI checks cover horizontal
operation cards, hidden/enabled options, disabled Fish, the actual navigation gate and
Agriculture handoff, the scrollable day selector, left-hand planning panel, removed DEMO
wording, and day-25/day-75 generation through the Prawn screen. Geometry tests additionally
cover clockwise/counterclockwise inset contours, custom indentation, narrow-pond rejection,
and minimum 3 m clearance after MAVLink coordinate quantization. The screens, satellite tiles
and rendered inset overlay were visually inspected. Actual vehicle upload/flight and feed
hardware were not exercised.

The subsequent upload/icon/hopper fix builds successfully: all 17 JVM tests and six UI tests
pass on the separate tablet emulator. It adds regression checks for
finite unused parameters on speed/RTL, preserved navigation yaw, capacity persistence, invalid
capacity edits and capacity-dependent loads. The Prawn option, planner header and Culture card
use the same dedicated vector prawn icon. The additional UI test checks the visible editable
hopper field, saving/reopening through SharedPreferences and the resulting load count. A live
flight-controller retry after this fix has not been performed on this host.

To run the focused UI checks, use:

```powershell
./gradlew.bat :app:connectedOriginalDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.kftgcs.aquaculture.AquacultureUiTest'
```

On this Windows host, Java's Unix-domain socket temporary directory needed a shorter
compatible location to start Gradle. This session used
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp`; no project settings were changed.
