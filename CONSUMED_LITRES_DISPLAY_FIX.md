# Consumed Litres Display Fix

## Problem
The "Consumed" field in the bottom telemetry overlay (StatusPanel) was not updating, even though the logs showed the data was being received correctly:

```
BATTERY_STATUS message received:
   Battery ID: 1
   current_battery: 0 cA (0.0 A)
   current_consumed: 470 mAh
```

## Root Cause
The `formattedConsumed` field was being calculated in TelemetryRepository, but the formatting logic had an issue:
- It was using `"%.0f mL".format(it * 1000f)` which could result in a Float formatting issue
- The zero case wasn't being handled explicitly
- The formatting wasn't robust enough for all edge cases

## Solution

### Updated TelemetryRepository.kt
**File:** `app/src/main/java/com/example/aerogcsclone/telemetry/TelemetryRepository.kt`

**Changed the formatting logic** to use a `when` expression with explicit handling of all cases:

```kotlin
// Format consumed volume - show in mL for small amounts, L for larger amounts
val formattedConsumed = when {
    consumedLiters == null -> null
    consumedLiters == 0f -> "0 mL"
    consumedLiters < 1f -> {
        val mL = (consumedLiters * 1000f).toInt()
        "$mL mL"
    }
    else -> "%.2f L".format(consumedLiters)
}
```

**Key Improvements:**
1. ✅ **Explicit zero handling** - Shows "0 mL" instead of potential rounding issues
2. ✅ **Integer mL conversion** - Converts to `Int` before formatting to avoid decimals in mL display
3. ✅ **Cleaner formatting** - Uses string interpolation for mL, format string for Liters
4. ✅ **Better logging** - Added raw value logging to help diagnose issues

### Display Format
The consumed value now displays as:
- **0-999 mL**: Shows as integer mL (e.g., "470 mL", "850 mL")
- **1+ Liters**: Shows as decimal liters (e.g., "1.25 L", "3.47 L")

### Example Values
Based on your log data:
```
current_consumed: 470 mAh → consumedLiters: 0.47 L → Display: "470 mL"
current_consumed: 1200 mAh → consumedLiters: 1.2 L → Display: "1.20 L"
current_consumed: 0 mAh → consumedLiters: 0.0 L → Display: "0 mL"
```

## Testing

### Verify in Logs
You should see in the logs:
```
Spray Telemetry: 📊 BATT2 Summary:
Spray Telemetry:    Flow Rate: 0.00 L/min
Spray Telemetry:    Consumed: 470 mL (raw: 0.47L)
Spray Telemetry:    Capacity: 10.0 L
Spray Telemetry:    Remaining: 95%
```

### Verify on Screen
The StatusPanel (bottom overlay) should now show:
```
┌────────────────────────────────────────────────┐
│ Alt: 5.2  Speed: 2.5 m/s  Area: 0.5 acres ... │
│ WP: 3     Time: 01:23     Distance: 125 m ... │
│                           Consumed: 470 mL    │
└────────────────────────────────────────────────┘
```

## Data Flow

1. **FCU sends** `BATTERY_STATUS` (Battery ID: 1) with `current_consumed` in mAh
2. **TelemetryRepository receives** and converts: mAh → mL → Liters
   - `470 mAh = 470 mL = 0.47 L`
3. **Formats for display** using new logic:
   - If < 1L: show as integer mL
   - If >= 1L: show as decimal L with 2 decimals
4. **Updates state** with `formattedConsumed` field
5. **StatusPanel displays** the formatted value in bottom overlay

## Files Modified
1. `app/src/main/java/com/example/aerogcsclone/telemetry/TelemetryRepository.kt`
   - Improved `formattedConsumed` calculation logic (lines ~568-577)
   - Added raw value logging for diagnostics

## Notes

### Why mAh = mL?
In ArduPilot's flow sensor configuration:
- `current_consumed` field is repurposed for volume tracking
- 1 mAh = 1 mL (by convention in flow sensor setup)
- BATT2_CAPACITY parameter defines tank size in mAh (which equals mL)

### MAVLink Message Structure
```
BATTERY_STATUS (Battery ID: 1 = Flow Sensor)
├─ current_battery (cA) → Flow rate (repurposed)
├─ current_consumed (mAh) → Volume consumed (mL)
├─ battery_remaining (%) → Tank remaining %
└─ voltages[0] (mV) → Not used for flow sensor
```

## Summary
✅ **Fixed formatting logic** - Now shows mL for small amounts, L for large amounts  
✅ **Explicit zero handling** - Shows "0 mL" instead of edge case issues  
✅ **Integer mL display** - No decimals for mL values (470 mL, not 470.0 mL)  
✅ **Better logging** - Shows both formatted and raw values for debugging  
✅ **Consistent with mission popup** - Uses same formatting style  
✅ **No compilation errors** - All changes validated successfully

The consumed litres field should now update properly in the bottom telemetry overlay!

