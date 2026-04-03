#!/usr/bin/env python3
"""
WebSocket Connection Test Script
================================
This script tests the connection to your Django Channels backend.
It simulates what the Android app does:
1. Connect to WebSocket
2. Send session_start message
3. Wait for session_ack
4. Receive mission_created with mission_id
5. Send telemetry data

Run this script from your local machine to verify the backend is working:
    pip install websocket-client
    python test_websocket_connection.py
"""

import json
import time
import websocket
from datetime import datetime

# ✅ Backend WebSocket URL
# --- ACTIVE: Production domain (works with both AWS and Aiven DB behind it) ---
WS_URL = "wss://kftgcs.com/ws/telemetry/"

# --- COMMENTED OUT: Direct AWS EC2 IP (uncomment to switch back) ---
# WS_URL = "ws://65.0.76.31:8000/ws/telemetry/"

# Test data (simulating Android app)
PILOT_ID = 7  # Your test pilot ID
DRONE_UID = "TEST_DRONE_001"
VEHICLE_NAME = "DRONE_01"
PLOT_NAME = "Test Field"


def on_open(ws):
    """Called when WebSocket connection is established"""
    print("="*60)
    print("🔥 WEBSOCKET CONNECTED!")
    print(f"📡 Connected to: {WS_URL}")
    print("="*60)

    # Step 2: Send session_start (same as Android app does)
    # Only pilot_id is needed — backend derives admin & superadmin from Pilot
    session_start = {
        "type": "session_start",
        "vehicle_name": VEHICLE_NAME,
        "pilot_id": PILOT_ID,
        "drone_uid": DRONE_UID,
        "plot_name": PLOT_NAME
    }

    payload = json.dumps(session_start)
    print(f"\n📤 Sending session_start:")
    print(f"   {payload}")
    ws.send(payload)
    print("📤 Waiting for session_ack from backend...")


def on_message(ws, message):
    """Called when a message is received from the server"""
    print(f"\n📩 MESSAGE FROM BACKEND:")
    print(f"   {message}")

    try:
        data = json.loads(message)
        msg_type = data.get("type")

        if msg_type == "session_ack":
            print("✅✅✅ SESSION_ACK RECEIVED! ✅✅✅")
            print("✅ TelemetryConsumer.receive() is working correctly!")
            print("✅ Backend routing is configured properly!")

        elif msg_type == "mission_created":
            mission_id = data.get("mission_id")
            print("🚀🚀🚀 MISSION CREATED! 🚀🚀🚀")
            print(f"🚀 Mission ID: {mission_id}")
            print("🚀 Mission was inserted into PostgreSQL database!")

            # Now send a test telemetry message
            print("\n📤 Sending test telemetry...")
            send_test_telemetry(ws, mission_id)

        else:
            print(f"📨 Received message type: {msg_type}")

    except json.JSONDecodeError as e:
        print(f"⚠️ Failed to parse message as JSON: {e}")


def send_test_telemetry(ws, mission_id):
    """Send a test telemetry message"""
    telemetry = {
        "type": "telemetry",
        "ts": int(time.time() * 1000),  # milliseconds
        "pilot_id": PILOT_ID,
        "admin_id": ADMIN_ID,
        "mission_id": mission_id,
        "drone_uid": DRONE_UID,
        "position": {
            "lat": 17.385044,
            "lng": 78.486671,
            "alt": 50.0
        },
        "attitude": {
            "roll": 0.0,
            "pitch": 0.0,
            "yaw": 90.0
        },
        "battery": {
            "voltage": 12.6,
            "current": 5.0,
            "remaining": 85
        },
        "gps": {
            "satellites": 12,
            "hdop": 0.8,
            "speed": 5.0
        },
        "status": {
            "flight_mode": "AUTO",
            "armed": True,
            "failsafe": False
        },
        "spray": {
            "on": True,
            "rate_lpm": 2.5,
            "flow_pulse": 100,
            "tank_level": 75.0
        }
    }

    payload = json.dumps(telemetry)
    print(f"📤 Telemetry payload:")
    print(f"   {payload[:200]}...")
    ws.send(payload)
    print("✅ Telemetry sent! Check Django backend logs to confirm DB insert.")

    # Wait a moment then close
    time.sleep(2)
    print("\n🔌 Test complete. Closing connection...")
    ws.close()


def on_error(ws, error):
    """Called when an error occurs"""
    print("="*60)
    print("❌❌❌ WEBSOCKET ERROR ❌❌❌")
    print(f"❌ Error: {error}")
    print("="*60)
    print("\n🔧 TROUBLESHOOTING:")
    print("1. Is Django server running?")
    print("   daphne -b 0.0.0.0 -p 8000 pavaman_gcs.asgi:application")
    print("2. Is port 8000 open in AWS Security Group?")
    print("3. Check Django server logs for errors")
    print("4. Verify routing.py has correct websocket_urlpatterns")
    print("5. Verify asgi.py includes ProtocolTypeRouter with websocket routing")


def on_close(ws, close_status_code, close_msg):
    """Called when WebSocket connection is closed"""
    print(f"\n🔌 WebSocket closed: {close_status_code} - {close_msg}")


def main():
    print("="*60)
    print("📡 WEBSOCKET CONNECTION TEST")
    print(f"🌐 URL: {WS_URL}")
    print(f"👤 Pilot ID: {PILOT_ID}")
    print(f"🚁 Drone UID: {DRONE_UID}")
    print("="*60)

    # Enable trace for debugging
    # websocket.enableTrace(True)

    ws = websocket.WebSocketApp(
        WS_URL,
        on_open=on_open,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close
    )

    print("\n🔄 Connecting to WebSocket...")
    ws.run_forever()


if __name__ == "__main__":
    main()

