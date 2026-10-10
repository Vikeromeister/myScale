/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground Service that listens for the saved Xiaomi Body Composition Scale 2
 * during the scheduled morning weigh-in window using low-power BLE scanning.
 *
 * When the user steps on the scale, the scale powers on and transmits advertisements.
 * This service catches the advertisement, stops scanning, connects via [BluetoothFacade],
 * receives the measurement, persists it to the database, automatically triggers
 * Google Health Connect sync, and shuts down immediately to save battery.
 */
@AndroidEntryPoint
class BackgroundScaleService : Service() {

    @Inject lateinit var bluetoothFacade: BluetoothFacade
    @Inject lateinit var settingsFacade: SettingsFacade

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentSavedDevice: ScannedDeviceInfo? = null
    private var isLeScanning = false
    private var scanCooldownJob: Job? = null
    private var windowTimeoutJob: Job? = null
    private var isWindowSession = false
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isReceiverRegistered = false

    companion object {
        private const val TAG = "BackgroundScaleService"
        private const val NOTIFICATION_ID = 4040
        private const val CHANNEL_ID = "background_scale_sync_channel"
        private const val COOLDOWN_AFTER_DISCONNECT_MS = 5_000L

        const val ACTION_START_WINDOW = "com.health.openscale.ACTION_START_WINDOW"
        const val EXTRA_WINDOW_DURATION_MINUTES = "extra_window_duration_minutes"
        const val DEFAULT_DURATION_MINUTES = 45

        fun start(context: Context) {
            val intent = Intent(context, BackgroundScaleService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
                LogManager.i(TAG, "Requested startForegroundService for BackgroundScaleService")
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to start BackgroundScaleService", e)
            }
        }

        fun startForWindow(context: Context, durationMinutes: Int) {
            val intent = Intent(context, BackgroundScaleService::class.java).apply {
                action = ACTION_START_WINDOW
                putExtra(EXTRA_WINDOW_DURATION_MINUTES, durationMinutes)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
                LogManager.i(TAG, "Requested startForegroundService for morning window ($durationMinutes min)")
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to start BackgroundScaleService for morning window", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundScaleService::class.java)
            try {
                context.stopService(intent)
                LogManager.i(TAG, "Requested stopService for BackgroundScaleService")
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to stop BackgroundScaleService", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogManager.i(TAG, "BackgroundScaleService created")
        createNotificationChannel()

        val initialNotification = buildNotification(getString(R.string.background_scale_service_listening))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        registerBluetoothReceiver()
        observeSettingsAndState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationMinutes = intent?.getIntExtra(EXTRA_WINDOW_DURATION_MINUTES, DEFAULT_DURATION_MINUTES) ?: DEFAULT_DURATION_MINUTES
        isWindowSession = (intent?.action == ACTION_START_WINDOW) || (intent != null && intent.hasExtra(EXTRA_WINDOW_DURATION_MINUTES))

        windowTimeoutJob?.cancel()
        if (isWindowSession) {
            LogManager.i(TAG, "Starting windowed background listening session for $durationMinutes minutes")
            updateNotification(getString(R.string.background_scale_service_listening))
            windowTimeoutJob = serviceScope.launch {
                delay(durationMinutes * 60_000L)
                LogManager.i(TAG, "Morning weigh-in window of $durationMinutes minutes expired without measurement. Stopping service.")
                stopSelf()
            }
        }

        serviceScope.launch {
            val enabled = settingsFacade.isBackgroundScaleSyncEnabled.first()
            if (!enabled) {
                LogManager.i(TAG, "Background scale sync disabled, stopping service")
                stopSelf()
                return@launch
            }
            if (!isLeScanning && bluetoothFacade.connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                startLeScan()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.i(TAG, "BackgroundScaleService destroyed")
        stopLeScan()
        unregisterBluetoothReceiver()
        windowTimeoutJob?.cancel()
        serviceScope.cancel()
        BackgroundScaleScheduler.scheduleNext(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_scale_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.background_scale_notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun hasBluetoothPermissions(): Boolean {
        val scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return scan && connect
    }

    @SuppressLint("MissingPermission")
    private fun startLeScan() {
        if (isLeScanning) return
        val saved = currentSavedDevice ?: return

        if (!hasBluetoothPermissions()) {
            LogManager.w(TAG, "Missing Bluetooth permissions for background BLE scan")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            LogManager.d(TAG, "Bluetooth adapter not enabled, scan deferred")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            LogManager.w(TAG, "BluetoothLeScanner not available")
            return
        }
        bluetoothLeScanner = scanner

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceAddress(saved.address)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isLeScanning = true
            LogManager.i(TAG, "Low-power background scan started for ${saved.name} (${saved.address})")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error starting background BLE scan", e)
            isLeScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopLeScan() {
        if (!isLeScanning) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            LogManager.i(TAG, "Low-power background scan stopped")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error stopping BLE scan", e)
        } finally {
            isLeScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val saved = currentSavedDevice ?: return
            if (device.address.equals(saved.address, ignoreCase = true)) {
                LogManager.i(TAG, "Xiaomi Scale 2 advertisement detected (${device.address})! Connecting...")
                stopLeScan()
                serviceScope.launch {
                    updateNotification(getString(R.string.background_scale_service_connecting))
                    bluetoothFacade.connectToSavedDevice()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            LogManager.w(TAG, "Background BLE scan failed with code: $errorCode")
            isLeScanning = false
            serviceScope.launch {
                delay(10_000L)
                startLeScan()
            }
        }
    }

    private fun observeSettingsAndState() {
        // 1. Observe saved device
        serviceScope.launch {
            settingsFacade.observeSavedDevice().collect { device ->
                currentSavedDevice = device
                if (device == null) {
                    stopLeScan()
                    updateNotification(getString(R.string.snackbar_bluetooth_no_scale_saved))
                } else {
                    updateNotification(getString(R.string.background_scale_service_listening))
                    if (!isLeScanning && bluetoothFacade.connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                        startLeScan()
                    }
                }
            }
        }

        // 2. Observe enabled toggle
        serviceScope.launch {
            settingsFacade.isBackgroundScaleSyncEnabled.collect { enabled ->
                if (!enabled) {
                    LogManager.i(TAG, "Background scale sync disabled by user, stopping service")
                    stopLeScan()
                    stopSelf()
                }
            }
        }

        // 3. Observe connection status
        serviceScope.launch {
            bluetoothFacade.connectionStatus.collect { status ->
                LogManager.d(TAG, "Connection status changed: $status")
                when (status) {
                    ConnectionStatus.CONNECTING -> {
                        stopLeScan()
                        updateNotification(getString(R.string.background_scale_service_connecting))
                    }
                    ConnectionStatus.CONNECTED -> {
                        stopLeScan()
                        updateNotification(getString(R.string.background_scale_service_measuring))
                    }
                    ConnectionStatus.DISCONNECTING -> {
                        stopLeScan()
                    }
                    ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED -> {
                        scanCooldownJob?.cancel()
                        scanCooldownJob = serviceScope.launch {
                            delay(COOLDOWN_AFTER_DISCONNECT_MS)
                            updateNotification(getString(R.string.background_scale_service_listening))
                            startLeScan()
                        }
                    }
                    else -> {}
                }
            }
        }

        // 4. Observe measurement snackbar events to show sync confirmation and shut down
        serviceScope.launch {
            bluetoothFacade.snackbarEventsFromConnector.collect { event ->
                if (event.messageResId == R.string.bluetooth_connector_measurement_saved) {
                    val weight = event.messageFormatArgs.firstOrNull()?.toString() ?: ""
                    updateNotification(getString(R.string.background_scale_service_synced, weight))
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    settingsFacade.setBackgroundSyncLastMeasuredDate(todayStr)
                    LogManager.i(TAG, "Measurement ($weight kg) recorded and synced to Health Connect! Stopping morning window service in 5s to save battery.")
                    scanCooldownJob?.cancel()
                    windowTimeoutJob?.cancel()
                    stopLeScan()
                    serviceScope.launch {
                        delay(5_000L)
                        stopSelf()
                    }
                }
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        LogManager.i(TAG, "Bluetooth turned ON, resuming background scan")
                        updateNotification(getString(R.string.background_scale_service_listening))
                        startLeScan()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        LogManager.i(TAG, "Bluetooth turned OFF, stopping scan")
                        stopLeScan()
                        updateNotification(getString(R.string.bluetooth_must_be_enabled_for_scan))
                    }
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(btStateReceiver, filter)
            isReceiverRegistered = true
        }
    }

    private fun unregisterBluetoothReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(btStateReceiver)
            } catch (e: Exception) {
                LogManager.e(TAG, "Error unregistering Bluetooth receiver", e)
            } finally {
                isReceiverRegistered = false
            }
        }
    }
}
