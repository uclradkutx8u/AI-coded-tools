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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast

class ScreenTimeMonitorService : Service() {
    companion object {
        const val ACTION_TICK = "com.example.phonerest.ACTION_TICK"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_SCREEN_ON = "screen_on"
        private const val CHANNEL_ID = "continuous_screen_timer"
        private const val ACTION_RESET_TIMER = "com.example.phonerest.ACTION_RESET_TIMER"
        private const val NOTIFICATION_ID = 1003
        private const val TICK_MS = 1_000L
        private const val NOTIFICATION_UPDATE_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, ScreenTimeMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTimeMonitorService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var notificationManager: NotificationManager
    private var timerToast: Toast? = null

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
            if (intent.action != Intent.ACTION_SCREEN_ON &&
                intent.action != Intent.ACTION_SCREEN_OFF) return
            resetTimer(intent.action == Intent.ACTION_SCREEN_ON)
            publishTick()
            updateNotification()
            scheduleTicker()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (isScreenOn && UserPreferences.isMonitoringEnabled(this@ScreenTimeMonitorService)) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(PowerManager::class.java)
        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, ScreenLockAdminReceiver::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        receiverRegistered = true

        resetTimer(powerManager.isInteractive)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UserPreferences.isMonitoringEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESET_TIMER) {
            resetTimer(powerManager.isInteractive)
            showTimerToast(getString(R.string.timer_reset_confirmation))
        }
        startForeground(NOTIFICATION_ID, buildNotification(currentElapsedMs()))
        lastNotificationUpdateAt = SystemClock.elapsedRealtime()
        publishTick()
        scheduleTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        timerToast?.cancel()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resetTimer(screenOn: Boolean) {
        isScreenOn = screenOn
        screenOnStartedAt = if (screenOn) SystemClock.elapsedRealtime() else 0L
        reminderSent = false
    }

    private fun scheduleTicker() {
        handler.removeCallbacks(ticker)
        if (isScreenOn) handler.post(ticker)
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(currentElapsedMs()))
        lastNotificationUpdateAt = SystemClock.elapsedRealtime()
    }

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
            if (limitMinutes != lastLimitMinutes) updateNotification()
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
            resetTimer(screenOn = true)
            updateNotification()
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
            updateNotification()
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun showLockReminder(minutes: Int) {
        showTimerToast(getString(R.string.lock_reminder_format, minutes))
    }

    private fun showTimerToast(text: String) {
        timerToast?.cancel()
        timerToast = Toast.makeText(
            applicationContext,
            text,
            Toast.LENGTH_LONG
        ).also { it.show() }
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val resetIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenTimeMonitorService::class.java).setAction(ACTION_RESET_TIMER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = PendingIntent.getActivity(
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

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(resetIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_open_app),
                    openAppIntent
                ).build()
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
