package org.crisislink.ble

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER, ULTRA_LOW }

/** Radio schedule for one power mode. [scanOnMs] / [scanOffMs] duty-cycle the scan; continuous ignores them. */
data class PowerProfile(
    val mode: PowerMode,
    val continuous: Boolean,
    val scanOnMs: Long,
    val scanOffMs: Long,
    val scanMode: Int,
    val advertiseMode: Int,
    val txPower: Int,
    val minRssi: Int,
    val maxLinks: Int = 8,
)

/** Same schedule table as bitchat-android, which has been field-tested on many devices. */
object PowerProfiles {
    fun of(mode: PowerMode): PowerProfile = when (mode) {
        PowerMode.PERFORMANCE -> PowerProfile(mode, true, 0, 0, ScanSettings.SCAN_MODE_LOW_LATENCY,
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY, AdvertiseSettings.ADVERTISE_TX_POWER_HIGH, -95)
        PowerMode.BALANCED -> PowerProfile(mode, false, 8_000, 2_000, ScanSettings.SCAN_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_MODE_BALANCED, AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM, -85)
        PowerMode.POWER_SAVER -> PowerProfile(mode, false, 2_000, 28_000, ScanSettings.SCAN_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER, AdvertiseSettings.ADVERTISE_TX_POWER_LOW, -75)
        PowerMode.ULTRA_LOW -> PowerProfile(mode, false, 1_000, 29_000, ScanSettings.SCAN_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER, AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW, -65)
    }

    /** Background overrides the duty cycle regardless of mode: sparse scans, sparser when alone. */
    fun background(base: PowerProfile, hasPeers: Boolean): PowerProfile =
        base.copy(continuous = false, scanOnMs = 1_000, scanOffMs = if (hasPeers) 29_000 else 59_000)
}

/** Picks a [PowerProfile] from charging state, battery level and whether the app is visible. */
class PowerController(private val context: Context, private val onChange: (PowerProfile) -> Unit) {
    @Volatile private var foreground = true
    @Volatile private var charging = false
    @Volatile private var level = 100
    @Volatile var hasPeers = false
        set(value) { if (field != value) { field = value; publish() } }

    @Volatile var current: PowerProfile = PowerProfiles.of(PowerMode.BALANCED)
        private set

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { readBattery(i); publish() }
    }

    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { foreground = true; publish() }
        override fun onStop(owner: LifecycleOwner) { foreground = false; publish() }
    }

    val batteryFraction: Double get() = level / 100.0

    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycle)
        }
        publish()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycle)
        }
    }

    private fun readBattery(i: Intent) {
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun mode(): PowerMode = when {
        !foreground && level <= CRITICAL -> PowerMode.ULTRA_LOW
        !foreground -> PowerMode.POWER_SAVER
        charging -> PowerMode.PERFORMANCE
        level <= CRITICAL -> PowerMode.ULTRA_LOW
        level <= LOW -> PowerMode.POWER_SAVER
        else -> PowerMode.BALANCED
    }

    private fun publish() {
        var profile = PowerProfiles.of(mode())
        if (!foreground) profile = PowerProfiles.background(profile, hasPeers)
        if (profile != current) {
            current = profile
            onChange(profile)
        }
    }

    private companion object { const val LOW = 30; const val CRITICAL = 15 }
}
