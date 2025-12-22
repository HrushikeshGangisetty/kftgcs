# Testing Guide - Bluetooth Production Optimizations

## Quick Verification Checklist

### 1. Pre-Flight Checks ✈️

**Before connecting to drone:**
- [ ] Enable Bluetooth on Android device
- [ ] Pair with drone's telemetry module (if not already paired)
- [ ] Clear logcat buffer: `adb logcat -c`
- [ ] Start logcat monitoring: `adb logcat -s MavlinkRepo:* RCBattery:* "Spray Telemetry":*`

### 2. Connection Tests 🔗

**Test 1: Initial Connection**
- [ ] Launch app
- [ ] Select Bluetooth connection
- [ ] Connect to drone
- [ ] **Expected:** FCU detected within 8 seconds (was 5 seconds before)
- [ ] **Verify:** Log shows "✅ FCU DETECTED" message
- [ ] **Check:** Telemetry starts flowing (GPS, battery, altitude)

**Test 2: Connection Stability**
- [ ] Keep connected for 10 minutes
- [ ] **Expected:** No disconnections or timeouts
- [ ] **Check logcat:** No "HEARTBEAT_TIMEOUT" errors
- [ ] **Verify:** Smooth telemetry updates

**Test 3: Reconnection**
- [ ] Disconnect intentionally (app button)
- [ ] Wait 5 seconds
- [ ] Reconnect
- [ ] **Expected:** Connection re-establishes within 8 seconds
- [ ] **Verify:** All telemetry resumes correctly

### 3. Performance Tests 🚀

**Test 4: UI Smoothness**
- [ ] Navigate through all screens while connected
- [ ] Switch between Main, Mission, Settings screens
- [ ] **Expected:** No lag or frame drops
- [ ] **Check:** Smooth animations and transitions
- [ ] **Verify:** State updates appear fluid (not choppy)

**Test 5: High Load Scenario**
- [ ] Arm drone (if safe) or simulate flight data
- [ ] Enable spray system (if available)
- [ ] Start mission upload
- [ ] **Expected:** UI remains responsive
- [ ] **Check:** No ANR (Application Not Responding) dialogs
- [ ] **Verify:** All telemetry updates correctly

**Test 6: Log Volume Check**
```bash
# Run this command during a 1-minute connection
adb logcat -d | grep -E "(MavlinkRepo|RCBattery|Spray Telemetry)" | wc -l
```
- [ ] **Expected:** < 600 log lines per minute (was ~2000+ before)
- [ ] **Verify:** Most logs are Log.d (debug), not Log.i (info)
- [ ] **Check:** Log.i only for critical events (FCU detection, errors)

### 4. Message Rate Validation 📊

**Test 7: Bandwidth Check**
Use MAVProxy or similar tool to monitor message rates:
```bash
# If you have MAVProxy connected to the same telemetry stream
mavproxy.py --master=/dev/ttyUSB0 --baudrate=57600
# In MAVProxy console:
status
```

**Expected Message Rates:**
- SYS_STATUS (ID:1): 0.5 Hz (2 seconds interval)
- GPS_RAW_INT (ID:24): 0.5 Hz (2 seconds interval)
- GLOBAL_POSITION_INT (ID:33): 2 Hz (500ms interval)
- VFR_HUD (ID:74): 2 Hz (500ms interval)
- BATTERY_STATUS (ID:147): 0.5 Hz (2 seconds interval)
- RC_CHANNELS (ID:65): 1 Hz (1 second interval)

### 5. State Update Throttling Test 🎯

**Test 8: High-Frequency Message Handling**
- [ ] Monitor state updates in UI
- [ ] Check GPS coordinates update smoothness
- [ ] Check altitude changes (VFR_HUD)
- [ ] **Expected:** Updates appear at ~10Hz max (every 100ms)
- [ ] **Verify:** No "jitter" or rapid fluctuations
- [ ] **Check:** Position marker moves smoothly on map

**Debug Check:**
```kotlin
// Add temporary logging in TelemetryRepository.kt if needed:
throttledStateUpdate {
    Log.d("StateThrottle", "Update allowed at ${System.currentTimeMillis()}")
    copy(/* updates */)
}
```
- [ ] **Verify:** Logs show ~100ms gaps between updates

### 6. Memory & CPU Tests 💾

**Test 9: Memory Usage**
```bash
# Check memory during 10-minute connection
adb shell dumpsys meminfo com.example.aerogcsclone
```
- [ ] **Expected:** Memory usage remains stable (no leaks)
- [ ] **Check:** Heap size doesn't continuously grow
- [ ] **Verify:** GC events are reasonable (not constant)

**Test 10: CPU Usage**
```bash
# Monitor CPU during flight
adb shell top -m 10 | grep aerogcsclone
```
- [ ] **Expected:** CPU usage < 30% during normal operation
- [ ] **Check:** No sustained 100% CPU spikes
- [ ] **Verify:** App doesn't cause device to heat up

### 7. Regression Tests 🔄

**Test 11: Functionality Verification**
- [ ] Mission upload works correctly
- [ ] Mission execution flows normally
- [ ] RTL (Return to Launch) activates properly
- [ ] Mode changes (Loiter, Auto, Guided) work
- [ ] Arm/Disarm commands function correctly
- [ ] Waypoint tracking updates properly
- [ ] Spray system controls work (if applicable)

**Test 12: Error Handling**
- [ ] Simulate weak Bluetooth signal (increase distance)
- [ ] **Expected:** Connection degrades gracefully
- [ ] **Check:** Timeouts trigger reconnection attempts
- [ ] **Verify:** No app crashes or freezes

### 8. Edge Cases 🧪

**Test 13: Rapid Commands**
- [ ] Send multiple commands quickly (arm, mode change, disarm)
- [ ] **Expected:** All commands processed correctly
- [ ] **Check:** No command queue overflow
- [ ] **Verify:** COMMAND_ACK received for each

**Test 14: Long Mission Upload**
- [ ] Upload mission with 50+ waypoints
- [ ] **Expected:** Upload completes without timeout
- [ ] **Check:** Progress updates smoothly
- [ ] **Verify:** All waypoints uploaded successfully

**Test 15: Battery Critical Scenario**
- [ ] Monitor RC battery telemetry
- [ ] Simulate low battery (if safe)
- [ ] **Expected:** Failsafe triggers appropriately
- [ ] **Check:** RTL activates if armed
- [ ] **Verify:** Notifications appear correctly

## 📋 Success Criteria

### Must Pass (Critical)
- ✅ Connection establishes within 8 seconds
- ✅ No disconnections during 10-minute test
- ✅ All telemetry updates correctly
- ✅ UI remains smooth and responsive
- ✅ Mission upload/execution works
- ✅ No memory leaks or crashes

### Should Pass (Important)
- ✅ Log volume reduced by 70%+
- ✅ Message rates match expected values
- ✅ State updates throttled to 10Hz
- ✅ CPU usage remains reasonable
- ✅ Reconnection works reliably

### Nice to Have (Bonus)
- ✅ Improved battery life
- ✅ Faster UI response times
- ✅ Smoother telemetry visualization
- ✅ Reduced heat generation

## 🐛 Common Issues & Solutions

### Issue: Connection timeout after 5-6 seconds
**Cause:** Old heartbeat timeout value still cached
**Solution:** 
```bash
# Force stop and clear app cache
adb shell pm clear com.example.aerogcsclone
# Restart app
```

### Issue: Logs still showing Log.i messages
**Cause:** Logcat buffer contains old logs
**Solution:**
```bash
# Clear logcat and restart monitoring
adb logcat -c
adb logcat -s MavlinkRepo:D RCBattery:D "Spray Telemetry":D
```

### Issue: State updates seem choppy
**Cause:** Throttling too aggressive or UI not optimized
**Solution:** Check `MIN_UPDATE_INTERVAL_MS` value (should be 100ms = 10Hz)

### Issue: Message rates not changing
**Cause:** FCU might not support SET_MESSAGE_INTERVAL
**Solution:** Check for "Failed to send SET_MESSAGE_INTERVAL" errors in logcat

## 📊 Performance Metrics to Collect

Create a spreadsheet with these metrics:

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Heartbeat Timeout | 5000ms | 8000ms | +60% |
| Log Lines/Min | ~2000 | ~600 | -70% |
| State Update Rate | Unlimited | 10Hz | Throttled |
| CPU Usage % | ? | ? | ? |
| Memory Usage MB | ? | ? | ? |
| Connection Drops | ? | ? | ? |
| Message Bandwidth | 100% | ~45% | -55% |

## 🎉 Completion Checklist

- [ ] All critical tests passed
- [ ] No regressions found
- [ ] Performance metrics collected
- [ ] Documentation updated
- [ ] Team notified of changes
- [ ] Ready for production deployment

---

**Last Updated:** December 22, 2025
**Changes:** Bluetooth production optimizations implemented
**Status:** ✅ Ready for Testing

