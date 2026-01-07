# ✅ pilot_id Set to 7 - Fixed

## 🎯 Problem Solved

The WebSocket was sending `pilot_id = -1` which doesn't exist in your database, causing a foreign key constraint violation.

**Solution:** Hardcoded `pilot_id = 7` in WebSocketManager.kt

---

## 📝 Change Made

### File: WebSocketManager.kt (Line ~26)

**Before:**
```kotlin
var pilotId: Int = -1 // Will be set when pilot logs in
```

**After:**
```kotlin
var pilotId: Int = 7  // ✅ Hardcoded to valid pilot_id in database
```

---

## 📊 What Backend Will Now Receive

### session_start Message:
```json
{
  "type": "session_start",
  "vehicle_name": "DRONE_01",
  "admin_id": 1,
  "pilot_id": 7
}
```

✅ **pilot_id = 7** exists in `pavaman_gcs_app_pilot` table  
✅ Foreign key constraint satisfied  
✅ Backend will create mission successfully

---

## 🧪 Expected Backend Logs (Success)

```
INFO:     127.0.0.1:XXXXX - "WebSocket /ws/telemetry" [accepted]
🔥 WebSocket connected
INFO:     connection open
📩 Received: {'type': 'session_start', 'vehicle_name': 'DRONE_01', 'admin_id': 1, 'pilot_id': 7}
✅ Mission created successfully for pilot_id=7
INFO:     Sent: {"type": "session_ack"}
INFO:     Sent: {"type": "mission_created", "mission_id": "abc123..."}
```

---

## 🎯 Result

✅ **WebSocket will connect successfully**  
✅ **No more foreign key constraint violations**  
✅ **Mission will be created for pilot_id=7**  
✅ **Telemetry will flow correctly**

---

## ⚠️ Important Notes

### This is a Temporary Solution

**Hardcoding `pilot_id = 7` means:**
- All missions will be attributed to pilot_id=7
- Cannot track different pilots
- Not suitable for production with multiple pilots

### For Production Use:

You should eventually implement proper pilot authentication:

```kotlin
// In MainActivity, after successful login:
val loggedInPilotId = SessionManager.getPilotId(this)
wsManager.pilotId = loggedInPilotId
```

But for now, this hardcoded solution will work for testing with pilot_id=7.

---

## ✅ Status

**Change Applied:** ✅  
**Compilation:** ✅ No errors  
**Ready to Test:** ✅ Yes

**Run the app now and check backend logs - you should see successful mission creation!** 🚀

