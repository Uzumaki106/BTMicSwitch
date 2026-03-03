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
        private const val STOP_SCO_DELAY_MS = 600L
    }

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var scoRetryCount = 0
    private var connectedDeviceName = "Unknown Device"
    private var isScoOn = false
    private var isRecording = false

    /**
     * Fires the moment any app starts or stops recording.
     * We filter out our own package to avoid false triggers.
     */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (!isRunning) return

            // Filter out our own app
            val wasRecording = isRecording
            isRecording = configs.isNotEmpty()

            if (isRecording && !wasRecording) {
                Log.d(TAG, "External recording STARTED — enabling SCO mic")
                handler.removeCallbacksAndMessages(null)
                enableSco()
            } else if (!isRecording && wasRecording) {
                Log.d(TAG, "External recording STOPPED — will disable SCO in ${STOP_SCO_DELAY_MS}ms")
                // Small delay in case recording restarts immediately (e.g. WhatsApp)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ disableSco() }, STOP_SCO_DELAY_MS)
            }
        }
    }

    // ─── SCO State Receiver ───────────────────────────────────────────────────
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.d(TAG, "SCO state: $state  isRunning=$isRunning  isRecording=$isRecording")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    isScoOn = true
                    scoRetryCount = 0
                    audioManager.isMicrophoneMute = false
                    Log.d(TAG, "SCO connected — BT mic is live")
                    updateNotification("🔴 Recording with BT Mic – $connectedDeviceName")
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    isScoOn = false
                    Log.d(TAG, "SCO disconnected")
                    // Only retry if we're in a recording session and it dropped unexpectedly
                    if (isRunning && isRecording) {
                        if (scoRetryCount < SCO_MAX_RETRIES) {
                            scoRetryCount++
                            handler.postDelayed({ enableSco() }, SCO_RETRY_DELAY_MS)
                        } else {
                            broadcastState(STATE_FAILED)
                            stopSelf()
                        }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    isScoOn = false
                    if (isRunning && isRecording) {
                        broadcastState(STATE_FAILED)
                        stopSelf()
                    }
                }
            }
        }
    }

    // ─── Bluetooth Disconnect Receiver ────────────────────────────────────────
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
        registerReceiver(bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        })
        // Register BEFORE service is active so we catch recording that starts immediately
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectedDeviceName = getConnectedDeviceName()
                isRunning = true
                startForeground(NOTIFICATION_ID, buildNotification("BT Mic Ready – start recording in any app"))
                broadcastState(STATE_ACTIVE)

                // Check if recording is already happening when user turns on the switch
                val currentlyRecording = audioManager.activeRecordingConfigurations.isNotEmpty()
                if (currentlyRecording) {
                    isRecording = true
                    enableSco()
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isRecording = false
        handler.removeCallbacksAndMessages(null)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        disableSco()
        safeUnregister(scoReceiver)
        safeUnregister(bluetoothReceiver)
        releaseWakeLock()
        broadcastState(STATE_STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── SCO On/Off ───────────────────────────────────────────────────────────

    /**
     * Enable SCO: called when recording starts.
     * Routes mic input through Bluetooth HFP.
     */
    private fun enableSco() {
        try {
            Log.d(TAG, "Enabling SCO for BT mic")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
        } catch (e: Exception) {
            Log.e(TAG, "enableSco error: ${e.message}")
        }
    }

    /**
     * Disable SCO: called when recording stops.
     * Restores MODE_NORMAL so A2DP takes over for audio output.
     * Does NOT restart SCO — it will only come back when recording starts again.
     */
    @Suppress("DEPRECATION")
    private fun disableSco() {
        try {
            Log.d(TAG, "Disabling SCO — restoring normal audio")
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            isScoOn = false
            if (isRunning) {
                updateNotification("BT Mic Ready – start recording in any app")
            }
        } catch (e: Exception) {
            Log.e(TAG, "disableSco error: ${e.message}")
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
