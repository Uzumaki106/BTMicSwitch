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

        // How often to poll microphone active state
        private const val MIC_POLL_INTERVAL_MS = 500L
    }

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var scoRetryCount = 0
    private var connectedDeviceName = "Unknown Device"
    private var isScoCurrentlyOn = false

    /**
     * CORE STRATEGY: Poll microphone usage every 500ms.
     * - If mic is being used by another app → activate SCO (BT mic)
     * - If mic is NOT being used → deactivate SCO, restore A2DP for playback
     *
     * This way audio playback is NEVER blocked by SCO when not recording.
     */
    private val micPollingRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val micActive = isMicrophoneActiveByOtherApp()

            if (micActive && !isScoCurrentlyOn) {
                Log.d(TAG, "Mic now active — switching to SCO for BT mic input")
                enableSco()
            } else if (!micActive && isScoCurrentlyOn) {
                Log.d(TAG, "Mic no longer active — switching back to A2DP for playback")
                disableScoKeepBtAudio()
            }

            handler.postDelayed(this, MIC_POLL_INTERVAL_MS)
        }
    }

    // ─── SCO State Receiver ───────────────────────────────────────────────────
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.d(TAG, "SCO state: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO connected")
                    scoRetryCount = 0
                    isScoCurrentlyOn = true
                    audioManager.isMicrophoneMute = false
                    broadcastState(STATE_ACTIVE)
                    updateNotification("BT Mic Active – $connectedDeviceName")
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    isScoCurrentlyOn = false
                    if (isRunning) {
                        Log.w(TAG, "SCO disconnected unexpectedly")
                        // Don't retry here — let the mic poller handle re-enabling if needed
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "SCO error")
                    isScoCurrentlyOn = false
                }
            }
        }
    }

    // ─── Bluetooth Connection Receiver ────────────────────────────────────────
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (isRunning) {
                        Log.w(TAG, "BT device disconnected")
                        broadcastState(STATE_BT_DISCONNECTED)
                        stopSelf()
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_DISCONNECTED && isRunning) {
                        broadcastState(STATE_BT_DISCONNECTED)
                        stopSelf()
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
        }
        registerReceiver(bluetoothReceiver, btFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectedDeviceName = getConnectedDeviceName()
                startForeground(NOTIFICATION_ID, buildNotification("BT Mic Ready – $connectedDeviceName"))
                isRunning = true
                broadcastState(STATE_ACTIVE)
                // Start polling microphone state
                handler.post(micPollingRunnable)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        hardStopSco()
        safeUnregister(scoReceiver)
        safeUnregister(bluetoothReceiver)
        releaseWakeLock()
        broadcastState(STATE_STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Mic Detection ────────────────────────────────────────────────────────
    /**
     * Detects if any app is currently recording audio.
     * Uses AudioManager on API 29+ (Android 10) which is our minSdk.
     */
    private fun isMicrophoneActiveByOtherApp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                audioManager.microphones.isNotEmpty() &&
                        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                   it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            } catch (e: Exception) {
                false
            }
        } else {
            // Fallback: always keep SCO on for older devices
            true
        }
    }

    // ─── SCO Audio Routing ────────────────────────────────────────────────────
    /**
     * Enable SCO: routes microphone input through Bluetooth HFP.
     * Called when recording is detected.
     */
    private fun enableSco() {
        try {
            Log.d(TAG, "Enabling SCO for BT mic")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling SCO: ${e.message}")
        }
    }

    /**
     * Disable SCO but keep Bluetooth audio active via A2DP.
     * Called when recording stops — this is what restores audio playback.
     *
     * Key insight: stopping SCO lets Android switch back to A2DP profile
     * automatically, which is the high-quality stereo Bluetooth audio used
     * for music/playback. The mic routing is no longer needed when not recording.
     */
    @Suppress("DEPRECATION")
    private fun disableScoKeepBtAudio() {
        try {
            Log.d(TAG, "Disabling SCO, restoring A2DP playback")
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            // Switch to NORMAL mode so A2DP takes over for playback
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            isScoCurrentlyOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling SCO: ${e.message}")
        }
    }

    /**
     * Hard stop SCO — used only on service destroy.
     */
    @Suppress("DEPRECATION")
    private fun hardStopSco() {
        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            isScoCurrentlyOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SCO: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun getConnectedDeviceName(): String {
        return try {
            val adapter = bluetoothManager.adapter ?: return "Unknown Device"
            @Suppress("MissingPermission")
            adapter.bondedDevices?.firstOrNull()?.name ?: "BT Headset"
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
                CHANNEL_ID, "BT Mic Switch", NotificationManager.IMPORTANCE_LOW
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
            this, 0, Intent(this, MainActivity::class.java),
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
            PowerManager.PARTIAL_WAKE_LOCK, "BTMicSwitch::ScoWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } }
        catch (e: Exception) { Log.e(TAG, "WakeLock error: ${e.message}") }
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
