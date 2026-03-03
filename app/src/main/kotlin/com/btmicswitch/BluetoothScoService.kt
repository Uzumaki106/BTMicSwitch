package com.btmicswitch

import android.app.*
import android.bluetooth.*
import android.content.*
import android.media.AudioManager
import android.media.MediaPlayer
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
    }

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var scoRetryCount = 0
    private var connectedDeviceName = "Unknown Device"

    /**
     * Silent MediaPlayer playing a 1-second silent audio file in a loop.
     *
     * WHY: This is the key trick. When MODE_IN_COMMUNICATION is active with SCO,
     * Samsung's HAL routes ALL output through SCO (earpiece, narrowband).
     * By having an active MediaPlayer using AudioManager.STREAM_MUSIC, Android is
     * forced to keep the MUSIC stream routed separately from the VOICE_CALL stream.
     * This causes the OS to use A2DP for STREAM_MUSIC output while SCO handles
     * the microphone input — giving us simultaneous BT mic + normal audio output.
     */
    private var silentPlayer: MediaPlayer? = null

    // ─── SCO State Receiver ───────────────────────────────────────────────────
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.d(TAG, "SCO state: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO connected")
                    scoRetryCount = 0
                    isRunning = true
                    audioManager.isMicrophoneMute = false
                    // Start silent player to force A2DP for music stream
                    startSilentPlayer()
                    broadcastState(STATE_ACTIVE)
                    updateNotification("BT Mic Active – $connectedDeviceName")
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (isRunning) {
                        Log.w(TAG, "SCO disconnected, retry $scoRetryCount")
                        stopSilentPlayer()
                        if (scoRetryCount < SCO_MAX_RETRIES) {
                            scoRetryCount++
                            broadcastState(STATE_CONNECTING)
                            updateNotification("Reconnecting BT Mic…")
                            handler.postDelayed({ startSco() }, SCO_RETRY_DELAY_MS)
                        } else {
                            broadcastState(STATE_FAILED)
                            stopSelf()
                        }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    stopSilentPlayer()
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
        stopSilentPlayer()
        stopSco()
        safeUnregister(scoReceiver)
        safeUnregister(bluetoothReceiver)
        releaseWakeLock()
        broadcastState(STATE_STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── SCO Management ───────────────────────────────────────────────────────
    private fun startSco() {
        Log.d(TAG, "Starting SCO")
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
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

    // ─── Silent Player (forces A2DP for output) ───────────────────────────────
    /**
     * Creates a MediaPlayer that plays a silent audio stream on STREAM_MUSIC.
     *
     * How it forces A2DP output:
     * - SCO handles STREAM_VOICE_CALL (mic input)
     * - STREAM_MUSIC is a separate audio stream that Android routes via A2DP
     * - Having an active STREAM_MUSIC MediaPlayer prevents Samsung HAL from
     *   collapsing all audio to the SCO earpiece
     * - Result: mic in via SCO (BT narrowband) + audio out via A2DP (BT stereo)
     *
     * The silent audio is generated as a minimal valid WAV in memory — no file needed.
     */
    private fun startSilentPlayer() {
        try {
            stopSilentPlayer()
            silentPlayer = MediaPlayer().apply {
                // Use a tiny inline silent audio source
                val silentUri = createSilentAudioUri()
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(applicationContext, silentUri)
                isLooping = true
                setVolume(0f, 0f) // Completely silent
                prepare()
                start()
            }
            Log.d(TAG, "Silent player started — A2DP output should be active")
        } catch (e: Exception) {
            Log.e(TAG, "Silent player error: ${e.message}")
            // Non-fatal — app still works, just output may go through SCO
        }
    }

    private fun stopSilentPlayer() {
        try {
            silentPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            silentPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Silent player stop error: ${e.message}")
        }
    }

    /**
     * Creates a URI pointing to a minimal silent WAV file written to cache.
     * 44 bytes = smallest valid WAV (0.0002 seconds of silence at 8000Hz mono).
     */
    private fun createSilentAudioUri(): android.net.Uri {
        val file = java.io.File(cacheDir, "silent.wav")
        if (!file.exists()) {
            // Minimal valid WAV header + 2 bytes of silence
            val wav = byteArrayOf(
                0x52, 0x49, 0x46, 0x46, // "RIFF"
                0x26, 0x00, 0x00, 0x00, // chunk size = 38
                0x57, 0x41, 0x56, 0x45, // "WAVE"
                0x66, 0x6D, 0x74, 0x20, // "fmt "
                0x10, 0x00, 0x00, 0x00, // subchunk size = 16
                0x01, 0x00,             // PCM format
                0x01, 0x00,             // mono
                0x40, 0x1F, 0x00, 0x00, // 8000 Hz sample rate
                0x40, 0x1F, 0x00, 0x00, // byte rate
                0x01, 0x00,             // block align
                0x08, 0x00,             // 8 bits per sample
                0x64, 0x61, 0x74, 0x61, // "data"
                0x02, 0x00, 0x00, 0x00, // data size = 2
                0x80.toByte(), 0x80.toByte() // 2 bytes of silence
            )
            file.writeBytes(wav)
        }
        return android.net.Uri.fromFile(file)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun getConnectedDeviceName(): String {
        return try {
            val adapter = bluetoothManager.adapter ?: return "Unknown Device"
            @Suppress("MissingPermission")
            adapter.bondedDevices?.firstOrNull()?.name ?: "BT Headset"
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
