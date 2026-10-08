package com.example.kftgcs.aquaculture.repository

import android.content.Context

interface AquacultureSettings {
    fun loadHopperCapacityKg(): Double?
    fun saveHopperCapacityKg(capacityKg: Double)
}

/** Local drone configuration, independent of transient pond and mission inputs. */
class AquaculturePreferences(context: Context) : AquacultureSettings {
    private val preferences = context.applicationContext.getSharedPreferences("aquaculture_settings", Context.MODE_PRIVATE)

    override fun loadHopperCapacityKg(): Double? = preferences.getString("hopper_capacity_kg", null)
        ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    override fun saveHopperCapacityKg(capacityKg: Double) {
        require(capacityKg.isFinite() && capacityKg > 0) { "Hopper capacity must be positive" }
        preferences.edit().putString("hopper_capacity_kg", capacityKg.toString()).apply()
    }
}
