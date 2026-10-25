package com.example.allinonedailer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.allinonedailer.R
import com.example.allinonedailer.data.RecordingItem
import com.example.allinonedailer.data.RecordingRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallRecordingService : Service() {

    companion object {
        private const val TAG = "CallRecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "CallRecordingChannel"

        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val EXTRA_PHONE_NUMBER = "EXTRA_PHONE_NUMBER"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFilePath: String? = null
    private var currentPhoneNumber: String = "Unknown"
    private var startTimeMillis: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START_RECORDING -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Unknown"
                startRecording(number)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(phoneNumber: String) {
        if (isRecording) return

        currentPhoneNumber = phoneNumber
        startTimeMillis = System.currentTimeMillis()

        val notification = createNotification("Recording call with $phoneNumber...")
        startForeground(NOTIFICATION_ID, notification)

        try {
            val dir = File(getExternalFilesDir(null), "recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "CALL_${phoneNumber}_$timeStamp.m4a"
            val file = File(dir, fileName)
            currentFilePath = file.absolutePath

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            recorder.apply {
                // VOICE_COMMUNICATION tunes audio for voice call audio streams
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            Log.d(TAG, "Recording started silently: $currentFilePath")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording with VOICE_COMMUNICATION, trying MIC fallback", e)
            tryMicFallback()
        }
    }

    private fun tryMicFallback() {
        try {
            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            Log.d(TAG, "Recording started with MIC fallback: $currentFilePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed mic fallback recording", e)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            val durationSeconds = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
            currentFilePath?.let { path ->
                val repo = RecordingRepository(applicationContext)
                repo.saveRecording(
                    RecordingItem(
                        phoneNumber = currentPhoneNumber,
                        filePath = path,
                        timestamp = startTimeMillis,
                        durationSeconds = durationSeconds
                    )
                )
            }

            Log.d(TAG, "Recording stopped and saved successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active call recording status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Call Recorder")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
