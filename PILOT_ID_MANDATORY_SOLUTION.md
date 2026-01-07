# 🚨 CRITICAL ISSUE: pilot_id is Mandatory

## ❌ Current Problem

Your backend is correctly rejecting the session because `pilot_id` is **mandatory** but **not being sent**.

### What's Happening:
1. ✅ Android connects to WebSocket successfully
2. ✅ Android sends: `{"type": "session_start", "vehicle_name": "DRONE_01"}`
3. ❌ Backend tries to create mission with `pilot_id = NULL`
4. ❌ Database rejects: `null value in column "pilot_id" violates not-null constraint`
5. ❌ Connection closes

---

## 🎯 The Root Cause

Your `WebSocketManager.kt` is sending this:
```json
{
  "type": "session_start",
  "vehicle_name": "DRONE_01"
}
```

Your backend **requires** this:
```json
{
  "type": "session_start",
  "vehicle_name": "DRONE_01",
  "pilot_id": 7,
  "admin_id": 1
}
```

---

## ✅ SOLUTION: You Must Modify WebSocketManager

Since `pilot_id` is mandatory in your backend, you **MUST** modify `WebSocketManager.kt` to:

1. Accept `pilot_id` and `admin_id` as parameters in `connect()` method
2. Include them in the `session_start` message

### Here's what needs to change in WebSocketManager.kt:

#### Change 1: Add fields to store IDs
```kotlin
class WebSocketManager {
    // ... existing code ...
    
    // Add these fields
    private var pilotId: Int = -1
    private var adminId: Int = -1
    
    // ... rest of code ...
}
```

#### Change 2: Update connect() method
```kotlin
// Change from:
fun connect() {
    // ...
}

// To:
fun connect(adminId: Int, pilotId: Int) {
    if (pilotId == -1 || pilotId <= 0) {
        Log.e(TAG, "❌ Cannot connect - Invalid pilotId=$pilotId")
        return
    }
    
    this.adminId = adminId
    this.pilotId = pilotId
    
    // ... rest of connection logic
}
```

#### Change 3: Update session_start message in onOpen()
```kotlin
override fun onOpen(webSocket: WebSocket, response: Response) {
    isConnected = true
    sessionStarted = false
    readyForTelemetry = false

    val sessionStart = JSONObject().apply {
        put("type", "session_start")
        put("vehicle_name", "DRONE_01")
        put("admin_id", adminId)    // ✅ ADD THIS
        put("pilot_id", pilotId)    // ✅ ADD THIS
    }

    webSocket.send(sessionStart.toString())
    Log.d(TAG, "📤 Sent session_start: $sessionStart")
}
```

#### Change 4: Update MainActivity to pass IDs
```kotlin
// In MainActivity.onCreate(), change from:
wsManager.connect()

// To:
val pilotId = SessionManager.getPilotId(this)
val adminId = 1 // or get from SessionManager if you implement getAdminId()

if (pilotId == -1) {
    Log.e("MAIN_ACTIVITY", "❌ Cannot connect - Pilot not logged in")
    // Don't connect
} else {
    wsManager.connect(adminId, pilotId)
}
```

---

## 🔧 Alternative: Delay Connection Until After Login

If you really don't want to modify WebSocketManager parameters, you could:

1. **Don't connect in onCreate()**
2. **Connect only after user logs in successfully**
3. **Store pilot_id globally and have WebSocketManager read it**

But this is more complex and error-prone.

---

## 📋 Why Your Current Code Fails

### Your logs show:
```
🔌 About to connect WebSocket...
⚠️ WARNING: Pilot not logged in (pilotId=-1)
✅ WebSocket connect() method called
```

Then backend:
```
📩 Received: {'type': 'session_start', 'vehicle_name': 'DRONE_01'}
❌ WebSocket error: null value in column "pilot_id" violates not-null constraint
```

**The problem:** WebSocket connects even when pilot is not logged in, and sends session_start without pilot_id.

---

## ✅ Correct Flow Should Be:

### Step 1: User Must Login First
```
User opens app → Login screen → Enter credentials → Backend returns pilot_id=7
```

### Step 2: Save pilot_id
```kotlin
SessionManager.saveSession(context, email, pilotId=7)
```

### Step 3: Only Then Connect WebSocket
```kotlin
val pilotId = SessionManager.getPilotId(this)
if (pilotId != -1) {
    wsManager.connect(adminId=1, pilotId=7)
} else {
    // Don't connect yet - wait for login
}
```

### Step 4: WebSocket Sends Complete session_start
```json
{
  "type": "session_start",
  "vehicle_name": "DRONE_01",
  "admin_id": 1,
  "pilot_id": 7
}
```

### Step 5: Backend Creates Mission Successfully
```
✅ Mission created with pilot_id=7
```

---

## 🚨 YOU CANNOT AVOID MODIFYING WEBSOCKETMANAGER

Since your backend requires `pilot_id` to be non-null, and currently WebSocketManager doesn't send it, you have only 2 options:

### Option 1: Modify WebSocketManager (Recommended)
- Add parameters to `connect(adminId, pilotId)`
- Include IDs in session_start message
- **This is the correct solution**

### Option 2: Modify Backend (Not Recommended)
- Make `pilot_id` nullable in database
- Use a default pilot_id when not provided
- **This defeats the purpose of tracking which pilot flew which mission**

---

## 📝 Action Items

To fix this issue, you must:

1. ✅ Modify `WebSocketManager.kt`:
   - Add `pilotId` and `adminId` fields
   - Update `connect()` to accept these parameters
   - Update `onOpen()` to include them in session_start

2. ✅ Modify `MainActivity.kt`:
   - Get pilot_id from SessionManager
   - Only connect if pilot_id is valid
   - Pass pilot_id to connect() method

3. ✅ Ensure login flow saves pilot_id:
   - Verify `SessionManager.saveSession()` is called after login
   - Verify pilot_id is stored correctly

---

## 🎯 Bottom Line

**Your backend's requirement for pilot_id is correct and necessary.** You need to modify the Android code to send it. There is no way around this if you want the system to work properly.

The modifications are necessary because:
- Backend needs to know which pilot is flying
- Each mission must be linked to a pilot
- Telemetry must be associated with the correct pilot
- This is a **business requirement**, not optional

**You must update WebSocketManager to send pilot_id in the session_start message.**

