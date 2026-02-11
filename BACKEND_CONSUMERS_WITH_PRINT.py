# consumers.py - Updated with guaranteed console output
# Copy this to your backend: pavaman_gcs_app/consumers.py

import json
import uuid
import traceback
import logging
import sys
from datetime import datetime
from django.utils.timezone import now
from channels.generic.websocket import AsyncWebsocketConsumer
from pavaman_gcs_app import db

# Configure logging to show in console
logger = logging.getLogger(__name__)
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)  # Force output to stdout
    ]
)

class TelemetryConsumer(AsyncWebsocketConsumer):

    async def connect(self):
        try:
            await self.accept()
            self.session = {}

            # 🔥 Use print with flush=True to guarantee console output
            print("=" * 60, flush=True)
            print("🔥 WebSocket connected (Django Channels)", flush=True)
            print("=" * 60, flush=True)
            logger.info("🔥 WebSocket connected (Django Channels)")

            # Verify DB connection
            if db.pool is None:
                print("❌ DB POOL IS NULL - Cannot save data!", flush=True)
                logger.error("❌ DB POOL IS NULL - Cannot save data!")
                await self.close()
                return

            print(f"✅ DB pool exists: {db.pool}", flush=True)

        except Exception as e:
            print(f"❌ WebSocket connection error: {repr(e)}", flush=True)
            logger.error(f"❌ WebSocket connection error: {repr(e)}")
            await self.close()
            return

        try:
            async with db.pool.acquire() as conn:
                row = await conn.fetchval("SELECT 1")
                print(f"✅ DB CONNECTION OK: {row}", flush=True)
                logger.info(f"✅ DB CONNECTION OK: {row}")
        except Exception as e:
            print(f"❌ DB CONNECTION FAILED: {repr(e)}", flush=True)
            logger.error(f"❌ DB CONNECTION FAILED: {repr(e)}")
            await self.close()

    async def receive(self, text_data):
        print(f"📩 RAW MESSAGE: {text_data[:200]}...", flush=True)
        logger.info(f"📩 RAW MESSAGE: {text_data[:200]}...")

        data = json.loads(text_data)
        msg_type = data.get("type")

        print(f"📩 MESSAGE TYPE: {msg_type}", flush=True)
        logger.info(f"📩 MESSAGE TYPE: {msg_type}")

        # ✅ SESSION START
        if msg_type == "session_start":
            print("🔄 Processing session_start...", flush=True)

            await self.send(json.dumps({"type": "session_ack"}))
            print("✅ session_ack sent", flush=True)
            logger.info("✅ session_ack sent")

            try:
                vehicle_id = data.get("drone_uid") or "SITL_DRONE_001"
                vehicle_name = data.get("vehicle_name")
                pilot_id = data.get("pilot_id")
                admin_id = data.get("admin_id")
                plot_name = data.get("plot_name")

                print(f"📋 Session data: vehicle={vehicle_id}, pilot={pilot_id}, admin={admin_id}, plot={plot_name}", flush=True)

                mission_id = uuid.uuid4()

                async with db.pool.acquire() as conn:
                    async with conn.transaction():

                        # ✅ CREATE MISSION
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_mission
                            (mission_id, vehicle_id, admin_id, pilot_id, start_time, status, plot_name)
                            VALUES ($1,$2,$3,$4,$5,$6,$7)
                        """,
                        mission_id,
                        vehicle_id,
                        admin_id,
                        pilot_id,
                        now(),
                        0,
                        plot_name
                        )

                        print(f"✅ Mission inserted into DB: {mission_id}", flush=True)
                        logger.info("✅ Mission inserted into DB")

                # store session
                self.session["mission_id"] = mission_id
                self.session["vehicle_id"] = vehicle_id
                self.session["pilot_id"] = pilot_id
                self.session["admin_id"] = admin_id

                await self.send(json.dumps({
                    "type": "mission_created",
                    "mission_id": str(mission_id)
                }))

                print(f"🚀 mission_created sent: {mission_id}", flush=True)
                logger.info(f"🚀 mission_created sent: {mission_id}")

            except Exception as e:
                print(f"❌ SESSION_START DB ERROR: {repr(e)}", flush=True)
                logger.error(f"❌ SESSION_START DB ERROR: {repr(e)}")
                traceback.print_exc()
                await self.close()

        # ✅ TELEMETRY
        elif msg_type == "telemetry":

            if "mission_id" not in self.session:
                print("❌ Telemetry before session", flush=True)
                logger.warning("❌ Telemetry before session")
                return

            try:
                ts = datetime.fromtimestamp(data["ts"] / 1000)

                async with db.pool.acquire() as conn:
                    async with conn.transaction():
                        # POSITION
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_telemetry_position
                            (vehicle_id, admin_id, pilot_id, mission_id, ts, lat, lng, alt, speed)
                            VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
                        """,
                        self.session["vehicle_id"],
                        self.session["admin_id"],
                        self.session["pilot_id"],
                        self.session["mission_id"],
                        ts,
                        data["position"]["lat"],
                        data["position"]["lng"],
                        data["position"]["alt"],
                        data["gps"]["speed"]
                        )

                        # BATTERY
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_telemetry_battery
                            (vehicle_id, ts, voltage, current, remaining, admin_id, mission_id, pilot_id)
                            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                        """,
                        self.session["vehicle_id"],
                        ts,
                        data["battery"]["voltage"],
                        data["battery"]["current"],
                        data["battery"]["remaining"],
                        self.session["admin_id"],
                        self.session["mission_id"],
                        self.session["pilot_id"]
                        )

                        # ATTITUDE
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_telemetry_attitude
                            (vehicle_id, admin_id, pilot_id, mission_id, ts, roll, pitch, yaw)
                            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                        """,
                        self.session["vehicle_id"],
                        self.session["admin_id"],
                        self.session["pilot_id"],
                        self.session["mission_id"],
                        ts,
                        data["attitude"]["roll"],
                        data["attitude"]["pitch"],
                        data["attitude"]["yaw"]
                        )

                        # GPS
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_telemetry_gps
                            (vehicle_id, admin_id, pilot_id, mission_id, ts, satellites, hdop)
                            VALUES ($1, $2, $3, $4, $5, $6, $7)
                        """,
                        self.session["vehicle_id"],
                        self.session["admin_id"],
                        self.session["pilot_id"],
                        self.session["mission_id"],
                        ts,
                        data["gps"]["satellites"],
                        data["gps"]["hdop"]
                        )

                        # STATUS
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_telemetry_status
                            (vehicle_id, admin_id, pilot_id, mission_id, ts, flight_mode, armed, failsafe)
                            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                        """,
                        self.session["vehicle_id"],
                        self.session["admin_id"],
                        self.session["pilot_id"],
                        self.session["mission_id"],
                        ts,
                        data["status"]["flight_mode"],
                        data["status"]["armed"],
                        data["status"]["failsafe"]
                        )

                        # SPRAY (if present)
                        spray = data.get("spray")
                        if spray:
                            await conn.execute("""
                                INSERT INTO pavaman_gcs_app_telemetry_spray
                                (vehicle_id, mission_id, admin_id, ts, spray_on, spray_rate_lpm, flow_pulse, tank_level_liters, pilot_id)
                                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                            """,
                            self.session["vehicle_id"],
                            self.session["mission_id"],
                            self.session["admin_id"],
                            ts,
                            spray["on"],
                            spray.get("rate_lpm"),
                            spray.get("flow_pulse"),
                            spray.get("tank_level"),
                            self.session["pilot_id"]
                            )

                        print(f"✅ Telemetry saved (all tables)", flush=True)
                        logger.info("✅ Telemetry saved (all tables)")

            except Exception as e:
                print(f"❌ TELEMETRY DB ERROR: {repr(e)}", flush=True)
                logger.error(f"❌ TELEMETRY DB ERROR: {repr(e)}")
                traceback.print_exc()

        # ✅ MISSION STATUS
        elif msg_type == "mission_status":
            status = data.get("status")
            mission_id = self.session.get("mission_id")

            print(f"📋 Mission status update: status={status}, mission_id={mission_id}", flush=True)

            if not mission_id:
                print("❌ mission_id missing for status update", flush=True)
                logger.warning("❌ mission_id missing for status update")
                return

            try:
                now_time = now()

                async with db.pool.acquire() as conn:
                    async with conn.transaction():
                        if status == 1:  # START
                            await conn.execute("""
                                UPDATE pavaman_gcs_app_mission
                                SET status = 1
                                WHERE mission_id = $1
                            """, mission_id)

                        elif status == 2:  # PAUSE
                            await conn.execute("""
                                UPDATE pavaman_gcs_app_mission
                                SET status = 2, paused_at = $1
                                WHERE mission_id = $2
                            """, now_time, mission_id)

                        elif status == 3:  # RESUME
                            await conn.execute("""
                                UPDATE pavaman_gcs_app_mission
                                SET status = 3, resumed_at = $1
                                WHERE mission_id = $2
                            """, now_time, mission_id)

                        elif status == 4:  # END
                            await conn.execute("""
                                UPDATE pavaman_gcs_app_mission
                                SET status = 4, end_time = $1
                                WHERE mission_id = $2
                            """, now_time, mission_id)

                            # CREATE MISSION SUMMARY
                            row = await conn.fetchrow("""
                                SELECT EXTRACT(EPOCH FROM ($1 - start_time))/60 AS flying_minutes
                                FROM pavaman_gcs_app_mission WHERE mission_id = $2
                            """, now_time, mission_id)
                            flying_time = row["flying_minutes"] if row else 0

                            row = await conn.fetchrow("""
                                SELECT AVG(speed) AS avg_speed
                                FROM pavaman_gcs_app_telemetry_position
                                WHERE mission_id = $1
                            """, mission_id)
                            avg_speed = row["avg_speed"] or 0

                            row = await conn.fetchrow("""
                                SELECT
                                    (SELECT remaining FROM pavaman_gcs_app_telemetry_battery
                                     WHERE mission_id=$1 ORDER BY ts ASC LIMIT 1) AS start_battery,
                                    (SELECT remaining FROM pavaman_gcs_app_telemetry_battery
                                     WHERE mission_id=$1 ORDER BY ts DESC LIMIT 1) AS end_battery
                            """, mission_id)
                            battery_start = row["start_battery"] if row else None
                            battery_end = row["end_battery"] if row else None

                            row = await conn.fetchrow("""
                                SELECT SUM(spray_rate_lpm) AS spray_used
                                FROM pavaman_gcs_app_telemetry_spray
                                WHERE mission_id = $1 AND spray_on = true
                            """, mission_id)
                            total_spray = row["spray_used"] or 0

                            row = await conn.fetchrow("""
                                SELECT COUNT(*) AS alerts
                                FROM pavaman_gcs_app_mission_event
                                WHERE mission_id = $1 AND event_status = 'WARNING'
                            """, mission_id)
                            alerts_count = row["alerts"] or 0

                            await conn.execute("""
                                INSERT INTO pavaman_gcs_app_mission_summary
                                (mission_id, vehicle_id, pilot_id, admin_id, flying_time_minutes,
                                 average_speed, total_spray_used, battery_start, battery_end,
                                 alerts_count, status, created_at)
                                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
                            """, mission_id, self.session["vehicle_id"], self.session["pilot_id"],
                            self.session["admin_id"], flying_time, avg_speed, total_spray,
                            battery_start, battery_end, alerts_count, 'COMPLETED', now())

                            print("📊 Mission summary created", flush=True)
                            logger.info("📊 Mission summary created")

                        print(f"✅ Mission status updated → {status}", flush=True)
                        logger.info(f"✅ Mission status updated → {status}")

            except Exception as e:
                print(f"❌ MISSION STATUS ERROR: {repr(e)}", flush=True)
                logger.error(f"❌ MISSION STATUS ERROR: {repr(e)}")
                traceback.print_exc()

        # ✅ MISSION EVENT
        elif msg_type == "mission_event":
            event_type = data.get("event_type")
            event_status = data.get("event_status")
            description = data.get("description", "")

            print(f"📋 Mission event: type={event_type}, status={event_status}", flush=True)

            if not self.session.get("mission_id"):
                print("❌ Missing session data for mission event", flush=True)
                logger.warning("❌ Missing session data for mission event")
                return

            try:
                async with db.pool.acquire() as conn:
                    async with conn.transaction():
                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_mission_event
                            (mission_id, vehicle_id, pilot_id, admin_id, event_type,
                             event_status, event_description, created_at)
                            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                        """, self.session["mission_id"], self.session["vehicle_id"],
                        self.session["pilot_id"], self.session["admin_id"],
                        event_type, event_status, description, now())

                        print(f"✅ Mission event recorded: {event_type} - {event_status}", flush=True)
                        logger.info(f"✅ Mission event recorded: {event_type} - {event_status}")

            except Exception as e:
                print(f"❌ MISSION EVENT ERROR: {repr(e)}", flush=True)
                logger.error(f"❌ MISSION EVENT ERROR: {repr(e)}")
                traceback.print_exc()

        # ✅ MISSION SUMMARY
        elif msg_type == "mission_summary":
            print("📋 Processing mission_summary...", flush=True)

            if not self.session.get("mission_id"):
                print("❌ Missing session data for mission summary", flush=True)
                logger.warning("❌ Missing session data for mission summary")
                return

            try:
                total_area = data.get("total_area")

                total_sprayed_acres = data.get("total_sprayed_acres")
                total_spray_used = data.get("total_spray_used")
                flying_time_minutes = data.get("flying_time_minutes")
                average_speed = data.get("average_speed")
                battery_start = data.get("battery_start")
                battery_end = data.get("battery_end")
                status = data.get("status", "COMPLETED")

                async with db.pool.acquire() as conn:
                    async with conn.transaction():
                        alerts_count = await conn.fetchval("""
                            SELECT COUNT(*) FROM pavaman_gcs_app_mission_event
                            WHERE mission_id = $1 AND event_status IN ('WARNING','ERROR')
                        """, self.session["mission_id"])

                        await conn.execute("""
                            INSERT INTO pavaman_gcs_app_mission_summary
                            (mission_id, vehicle_id, pilot_id, admin_id, total_area,
                             total_sprayed_acres, total_spray_used, flying_time_minutes, average_speed,
                             battery_start, battery_end, alerts_count, status, created_at)
                            VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
                            ON CONFLICT (mission_id) DO UPDATE SET
                                total_area = EXCLUDED.total_area,
                                total_sprayed_acres = EXCLUDED.total_sprayed_acres,
                                total_spray_used = EXCLUDED.total_spray_used,
                                flying_time_minutes = EXCLUDED.flying_time_minutes,
                                average_speed = EXCLUDED.average_speed,
                                battery_start = EXCLUDED.battery_start,
                                battery_end = EXCLUDED.battery_end,
                                alerts_count = EXCLUDED.alerts_count,
                                status = EXCLUDED.status
                        """, self.session["mission_id"], self.session["vehicle_id"],
                        self.session["pilot_id"], self.session["admin_id"], total_area,
                        total_sprayed_acres, total_spray_used, flying_time_minutes, average_speed,
                        battery_start, battery_end, alerts_count, status, now())

                        print(f"✅ Mission summary saved", flush=True)
                        logger.info(f"✅ Mission summary saved")

            except Exception as e:
                print(f"❌ MISSION SUMMARY ERROR: {repr(e)}", flush=True)
                logger.error(f"❌ MISSION SUMMARY ERROR: {repr(e)}")
                traceback.print_exc()

        else:
            print(f"⚠️ Unknown message type: {msg_type}", flush=True)

    async def disconnect(self, close_code):
        print(f"❌ WebSocket disconnected: {close_code}", flush=True)
        logger.info(f"❌ WebSocket disconnected: {close_code}")

        if self.session.get("mission_id"):
            try:
                async with db.pool.acquire() as conn:
                    await conn.execute("""
                        UPDATE pavaman_gcs_app_mission
                        SET end_time = $1, status = 4
                        WHERE mission_id = $2
                    """, now(), self.session["mission_id"])
                    print("✅ Mission closed in DB", flush=True)
                    logger.info("✅ Mission closed in DB")
            except Exception as e:
                print(f"❌ Mission close failed: {repr(e)}", flush=True)
                logger.error(f"❌ Mission close failed: {repr(e)}")
