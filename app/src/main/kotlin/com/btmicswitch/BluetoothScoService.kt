package com.btmicswitch

import android.app.*
import android.bluetooth.*
import android.content.*
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
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
        // Delay before restoring A2DP after recording stops (avoids flicker)
        private const val RESTORE_A2DP_DELAY_MS = 800L
    }

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var scoRetryCount = 0
    private var connectedDeviceName = "Unknown Device"
    private var isRecording = false
    private var scoConnected = false

    // Restore A2DP after recording stops (with small delay to avoid clicks)
    private val restoreA2dpRunnable = Runnable {
        if (isRunning && !isRecording) {
            Log.d(TAG, "Recording stopped → restoring A2DP for playback")
            switchToA2dpMode()
        }
    }

    /**
     * THE KEY: AudioRecordingCallback - fires instantly when any app
     * starts or stops recording. No polling needed.
     * - Recording starts → switch to SCO mode (BT mic active)
     * - Recording stops  → switch to A2DP mode (normal audio out)
     */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val wasRecording = isRecording
            isRecording = configs.isNotEmpty()

            if (isRecording && !wasRecording) {
                Log.d(TAG, "Recording STARTED — activating SCO mic")
                handler.removeCallbacks(restoreA2dpRunnable)
                switchToScoMode()
            } else if (!isRecording && wasRecording) {
                Log.d(TAG, "Recording STOPPED — scheduling A2DP restore")
                // Small delay to avoid audio glitch if recording restarts quickly
                handler.removeCallbacks(restoreA2dpRunnable)
                handler.postDelayed(restoreA2dpRunnable, RESTORE_A2DP_DELAY_MS)
            }
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
                    scoConnected = true
                    isRunning = true
                    audioManager.isMicrophoneMute = false
                    broadcastState(STATE_ACTIVE)
                    updateNotification("BT Mic Ready – tap record in any app")
                    // If already recording when SCO connects, stay in SCO mode
                    if (!isRecording) switchToA2dpMode()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    scoConnected = false
                    if (isRunning) {
                        if (scoRetryCount < SCO_MAX_RETRIES) {
                            scoRetryCount++
                            broadcastState(STATE_CONNECTING)
                            handler.postDelayed({ startSco() }, SCO_RETRY_DELAY_MS)
                        } else {
                            broadcastState(STATE_FAILED)
                            stopSelf()
                        }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    scoConnected = false
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
                    if (isRunning) { broadcastState(STATE_BT_DISCONNECTED); stopSelf() }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (st == BluetoothProfile.STATE_DISCONNECTED && isRunning) {
                        broadcastState(STATE_BT_DISCONNECTED); stopSelf()
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
        // Register recording callback - instant notification when any app records
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)
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
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        stopSco()
        safeUnregister(scoReceiver)
        safeUnregister(bluetoothReceiver)
        releaseWakeLock()
        broadcastState(STATE_STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Audio Mode Switching ─────────────────────────────────────────────────

    /**
     * SCO MODE: activated when recording starts.
     * - Mic input → Bluetooth HFP (your neckband mic)
     * - Audio output → still through BT earpiece/speaker during recording
     * This is unavoidable on Samsung — SCO is a two-way channel.
     */
    private fun switchToScoMode() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = true
            if (!scoConnected) audioManager.startBluetoothSco()
            Log.d(TAG, "Switched to SCO mode (recording)")
            updateNotification("🔴 Recording with BT Mic – $connectedDeviceName")
        } catch (e: Exception) {
            Log.e(TAG, "SCO mode switch error: ${e.message}")
        }
    }

    /**
     * A2DP MODE: activated when not recording.
     * - SCO kept alive in background so it can re-activate instantly for next recording
     * - Audio output → A2DP (high quality BT stereo) or phone speaker
     * - Mic input is inactive (no app is recording)
     */
    @Suppress("DEPRECATION")
    private fun switchToA2dpMode() {
        try {
            // Switch mode to NORMAL so Samsung HAL releases SCO output lock
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            // Keep isBluetoothScoOn = true so SCO channel stays warm
            // but stop the active SCO stream so A2DP can take over output
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            scoConnected = false
            // Restart SCO in background so it's ready for next recording
            // (will reconnect quickly since BT is already paired/connected)
            handler.postDelayed({ startSco() }, 500)
            Log.d(TAG, "Switched to A2DP mode (not recording)")
            updateNotification("BT Mic Ready – $connectedDeviceName")
        } catch (e: Exception) {
            Log.e(TAG, "A2DP mode switch error: ${e.message}")
        }
    }

    private fun startSco() {
        Log.d(TAG, "Starting SCO (attempt ${scoRetryCount + 1})")
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
        } catch (e: Exception) {
            Log.e(TAG, "SCO start error: ${e.message}")
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
            Log.e(TAG, "SCO stop error: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun getConnectedDeviceName(): String {
        return try {
            @Suppress("MissingPermission")
            bluetoothManager.adapter?.bondedDevices?.firstOrNull()?.name ?: "BT Headset"
        } catch (e: Exception) { "BT Headset" }
    }

    private fun broadcastState(state: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_STATE).apply {
                putExtra(EXTRA_STATE, state)
                putExtra(EXTRA_DEVICE_NAME, connectedDeviceName)
            }
        )
    }

    // ─── Notification ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BT Mic Switch", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Bluetooth microphone routing"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, BluetoothScoService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Mic Switch")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_bt)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stop, "Turn Off", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTMicSwitch::ScoWakeLock")
            .apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } }
        catch (e: Exception) { Log.e(TAG, "WakeLock error: ${e.message}") }
    }

    private fun safeUnregister(r: BroadcastReceiver) {
        try { unregisterReceiver(r) } catch (_: Exception) {}
    }
}
