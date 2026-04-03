#!/usr/bin/env python3
"""
Quick WebSocket Test - Verify backend receive() is working
Run: python test_ws_quick.py
Requires: pip install websocket-client
"""

import json
import websocket

# --- ACTIVE: Production domain ---
WS_URL = "wss://kftgcs.com/ws/telemetry/"

# --- COMMENTED OUT: Direct AWS EC2 IP (uncomment to switch back) ---
# WS_URL = "ws://65.0.76.31:8000/ws/telemetry/"

def test():
    print(f"🔌 Connecting to {WS_URL}...")
    ws = websocket.create_connection(WS_URL, timeout=10)
    print("✅ Connected!")

    # Send session_start (exactly like Android does)
    # Only pilot_id is needed — backend derives admin & superadmin from Pilot
    payload = json.dumps({
        "type": "session_start",
        "vehicle_name": "DRONE_01",
        "pilot_id": 7,
        "drone_uid": "TEST_DRONE_001",
        "plot_name": "Test Field"
    })

    print(f"📤 Sending: {payload}")
    ws.send(payload)

    # Wait for response
    print("📩 Waiting for response...")
    response = ws.recv()
    print(f"📩 Received: {response}")

    # Check for mission_created
    response2 = ws.recv()
    print(f"📩 Received: {response2}")

    ws.close()
    print("✅ Test complete!")

if __name__ == "__main__":
    test()

