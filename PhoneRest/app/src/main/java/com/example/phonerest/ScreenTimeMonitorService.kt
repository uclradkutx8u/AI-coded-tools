package com.example.phonerest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ScaleXSpan
import android.widget.Toast

class ScreenTimeMonitorService : Service() {
    companion object {
        const val ACTION_TICK = "com.example.phonerest.ACTION_TICK"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_SCREEN_ON = "screen_on"
        private const val CHANNEL_ID = "continuous_screen_timer"
        private const val NOTIFICATION_ID = 1003
        private const val TICK_MS = 1_000L
        private const val NOTIFICATION_UPDATE_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, ScreenTimeMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTimeMonitorService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var isScreenOn = false
    private var screenOnStartedAt = 0L
    private var receiverRegistered = false
    private var reminderSent = false
    private var lastLimitMinutes = -1
    private var lastReminderMinutes = -1
    private var lastReminderEnabled = false
    private var lastNotificationUpdateAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    screenOnStartedAt = SystemClock.elapsedRealtime()
                    reminderSent = false
                    lastNotificationUpdateAt = 0L
                    publishTick()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    screenOnStartedAt = 0L
                    reminderSent = false
                    lastNotificationUpdateAt = 0L
                    publishTick()
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(PowerManager::class.java)
        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, ScreenLockAdminReceiver::class.java)
        createNotificationChannel()

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        receiverRegistered = true

        isScreenOn = powerManager.isInteractive
        screenOnStartedAt = if (isScreenOn) SystemClock.elapsedRealtime() else 0L
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UserPreferences.isMonitoringEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(currentElapsedMs()))
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        if (!UserPreferences.isMonitoringEnabled(this)) {
            stopSelf()
            return
        }

        val elapsed = currentElapsedMs()
        val limitMinutes = UserPreferences.getLimitMinutes(this)
        val reminderMinutes = UserPreferences.getReminderMinutes(this)
        val reminderEnabled = UserPreferences.isReminderEnabled(this)
        if (
            limitMinutes != lastLimitMinutes ||
            reminderMinutes != lastReminderMinutes ||
            reminderEnabled != lastReminderEnabled
        ) {
            reminderSent = false
            lastLimitMinutes = limitMinutes
            lastReminderMinutes = reminderMinutes
            lastReminderEnabled = reminderEnabled
        }

        val limitMs = limitMinutes * 60_000L
        val remainingMs = (limitMs - elapsed).coerceAtLeast(0L)
        val reminderMs = reminderMinutes * 60_000L
        if (
            isScreenOn &&
            reminderEnabled &&
            !reminderSent &&
            elapsed < limitMs &&
            remainingMs <= reminderMs
        ) {
            showLockReminder(reminderMinutes)
            reminderSent = true
        }

        if (isScreenOn && elapsed >= limitMs) {
            screenOnStartedAt = SystemClock.elapsedRealtime()
            reminderSent = false
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                try {
                    devicePolicyManager.lockNow()
                } catch (_: SecurityException) {
                    // The UI will show that administrator permission is required.
                }
            }
        }

        publishTick()
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateAt >= NOTIFICATION_UPDATE_MS) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(currentElapsedMs()))
            lastNotificationUpdateAt = now
        }
    }

    private fun currentElapsedMs(): Long =
        if (isScreenOn && screenOnStartedAt > 0L) {
            (SystemClock.elapsedRealtime() - screenOnStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }

    private fun publishTick() {
        sendBroadcast(Intent(ACTION_TICK).apply {
            setPackage(packageName)
            putExtra(EXTRA_ELAPSED_MS, currentElapsedMs())
            putExtra(EXTRA_SCREEN_ON, isScreenOn)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showLockReminder(minutes: Int) {
        val text = getString(R.string.lock_reminder_format, minutes)
        val emphasizedText = SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(Color.WHITE),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ScaleXSpan(1.08f),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        Toast.makeText(
            applicationContext,
            emphasizedText,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elapsedTotalSeconds = elapsedMs / 1_000L
        val elapsedMinutes = elapsedTotalSeconds / 60L
        val limitMinutes = UserPreferences.getLimitMinutes(this)
        val text = if (isScreenOn) {
            getString(
                R.string.notification_elapsed_format,
                elapsedMinutes,
                limitMinutes
            )
        } else {
            getString(R.string.notification_screen_off)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
