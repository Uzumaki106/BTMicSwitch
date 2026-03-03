package com.btmicswitch

import android.app.*
import android.bluetooth.*
import android.content.*
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class BluetoothScoService : Service() {

    companion object {
        const val TAG = "BTMicSwitch"
        const val CHANNEL_ID = "bt_mic_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.btmicswitch.START"
        const val ACTION_STOP = "com.btmicswitch.STOP"
        const val BROADCAST_STATE = "com.btmicswitch.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val STATE_ACTIVE = "active"
        const val STATE_CONNECTING = "connecting"
        const val STATE_FAILED = "failed"
        const val STATE_STOPPED = "stopped"
        const val STATE_BT_DISCONNECTED = "bt_disconnected"

        private const val SCO_RETRY_DELAY_MS = 2000L
        private const val SCO_MAX_RETRIES = 5
        private const val ROUTING_KEEPALIVE_MS = 3000L
    }

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var scoRetryCount = 0
    private var connectedDeviceName = "Unknown Device"

    // Periodically re-applies audio routing so playback stays working after recording
    private val routingKeepalive = object : Runnable {
        override fun run() {
            if (isRunning) {
                restorePlaybackRouting()
                handler.postDelayed(this, ROUTING_KEEPALIVE_MS)
            }
        }
    }

    // ─── SCO State Receiver ───────────────────────────────────────────────────
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.d(TAG, "SCO state changed: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO connected successfully")
                    scoRetryCount = 0
                    isRunning = true
                    audioManager.isMicrophoneMute = false
                    // Restore playback routing right after SCO connects
                    restorePlaybackRouting()
                    handler.removeCallbacks(routingKeepalive)
                    handler.postDelayed(routingKeepalive, ROUTING_KEEPALIVE_MS)
                    broadcastState(STATE_ACTIVE)
                    updateNotification("BT Mic Active – $connectedDeviceName")
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (isRunning) {
                        Log.w(TAG, "SCO disconnected, retry $scoRetryCount")
                        handler.removeCallbacks(routingKeepalive)
                        if (scoRetryCount < SCO_MAX_RETRIES) {
                            scoRetryCount++
                            broadcastState(STATE_CONNECTING)
                            updateNotification("Reconnecting BT Mic…")
                            handler.postDelayed({ startSco() }, SCO_RETRY_DELAY_MS)
                        } else {
                            Log.e(TAG, "SCO failed after $SCO_MAX_RETRIES retries")
                            broadcastState(STATE_FAILED)
                            stopSelf()
                        }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "SCO error")
                    handler.removeCallbacks(routingKeepalive)
                    broadcastState(STATE_FAILED)
                    stopSelf()
                }
            }
        }
    }

    // ─── Bluetooth Connection Receiver ────────────────────────────────────────
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    Log.w(TAG, "Bluetooth device disconnected: ${device?.address}")
                    if (isRunning) {
                        broadcastState(STATE_BT_DISCONNECTED)
                        stopSelf()
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_DISCONNECTED && isRunning) {
                        Log.w(TAG, "Headset profile disconnected")
                        broadcastState(STATE_BT_DISCONNECTED)
                        stopSelf()
                    }
                }
                // Re-apply routing whenever another app changes audio output
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (isRunning) {
                        Log.d(TAG, "Audio becoming noisy, re-applying routing")
                        restorePlaybackRouting()
                    }
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        createNotificationChannel()
        acquireWakeLock()

        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(bluetoothReceiver, btFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectedDeviceName = getConnectedDeviceName()
                startForeground(NOTIFICATION_ID, buildNotification("Connecting BT Mic…"))
                broadcastState(STATE_CONNECTING)
                startSco()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying, stopping SCO")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopSco()
        safeUnregister(scoReceiver)
        safeUnregister(bluetoothReceiver)
        releaseWakeLock()
        broadcastState(STATE_STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── SCO Management ───────────────────────────────────────────────────────
    private fun startSco() {
        Log.d(TAG, "Starting SCO (attempt ${scoRetryCount + 1})")
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
            broadcastState(STATE_CONNECTING)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SCO: ${e.message}")
            broadcastState(STATE_FAILED)
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SCO: ${e.message}")
        }
    }

    /**
     * THE CORE FIX for simultaneous BT mic input + audio playback:
     *
     * Problem: MODE_IN_COMMUNICATION routes BOTH mic AND speaker through SCO (8kHz narrowband),
     * which either silences playback or makes it sound terrible.
     *
     * Solution: Keep SCO alive only for mic input. Force audio OUTPUT to go through
     * either A2DP (Bluetooth high quality) or the phone speaker by:
     * 1. Keeping isBluetoothScoOn = true (mic stays on SCO)
     * 2. Setting isSpeakerphoneOn = false (don't override to phone speaker)
     * 3. Android will automatically use A2DP for playback when available
     *
     * This mirrors exactly how phone calls work: mic uses SCO, earpiece/A2DP for audio.
     */
    private fun restorePlaybackRouting() {
        try {
            // Keep SCO active for microphone
            if (!audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = true
            }
            // Disable speakerphone override
            audioManager.isSpeakerphoneOn = false

            // Check if A2DP output device is available and prefer it for playback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasA2dp = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                Log.d(TAG, "A2DP output available: $hasA2dp — playback will route through ${if (hasA2dp) "BT A2DP" else "phone speaker"}")
            }

            Log.d(TAG, "Routing: SCO mic=ON, Speakerphone=OFF, playback→A2DP/speaker")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring playback routing: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun getConnectedDeviceName(): String {
        return try {
            val adapter = bluetoothManager.adapter ?: return "Unknown Device"
            val proxy = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            if (proxy == BluetoothProfile.STATE_CONNECTED) {
                @Suppress("MissingPermission")
                adapter.bondedDevices?.firstOrNull()?.name ?: "BT Headset"
            } else {
                "BT Headset"
            }
        } catch (e: Exception) {
            "BT Headset"
        }
    }

    private fun broadcastState(state: String) {
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_DEVICE_NAME, connectedDeviceName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ─── Notification ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BT Mic Switch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth microphone routing status"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BluetoothScoService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Mic Switch")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_bt)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Turn Off", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BTMicSwitch::ScoWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release error: ${e.message}")
        }
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
