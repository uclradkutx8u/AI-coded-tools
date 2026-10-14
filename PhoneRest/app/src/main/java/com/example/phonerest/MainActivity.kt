package com.example.phonerest

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import kotlin.math.min
import com.google.android.material.button.MaterialButton

class MainActivity : Activity() {
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var reminderControls: ViewGroup
    private lateinit var monitorSwitch: CompoundButton
    private lateinit var reminderSwitch: CompoundButton
    private lateinit var adminStatus: TextView
    private lateinit var timerText: TextView

    private val limitPresets = linkedMapOf<Int, MaterialButton>()
    private val reminderPresets = linkedMapOf<Int, MaterialButton>()
    private var syncingSwitches = false
    private var tickReceiverRegistered = false

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val elapsed = intent.getLongExtra(ScreenTimeMonitorService.EXTRA_ELAPSED_MS, 0L)
            val screenOn = intent.getBooleanExtra(ScreenTimeMonitorService.EXTRA_SCREEN_ON, false)
            updateTimer(elapsed, screenOn)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()

        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, ScreenLockAdminReceiver::class.java)
        reminderControls = findViewById(R.id.reminderControls)
        monitorSwitch = findViewById(R.id.monitorSwitch)
        reminderSwitch = findViewById(R.id.reminderSwitch)
        adminStatus = findViewById(R.id.adminStatus)
        timerText = findViewById(R.id.timerText)

        limitPresets.putAll(
            mapOf(
                15 to findViewById<MaterialButton>(R.id.limitPreset15),
                20 to findViewById<MaterialButton>(R.id.limitPreset20),
                25 to findViewById<MaterialButton>(R.id.limitPreset25),
                30 to findViewById<MaterialButton>(R.id.limitPreset30)
            )
        )
        reminderPresets.putAll(
            mapOf(
                1 to findViewById<MaterialButton>(R.id.reminderPreset1),
                2 to findViewById<MaterialButton>(R.id.reminderPreset2),
                3 to findViewById<MaterialButton>(R.id.reminderPreset3),
                5 to findViewById<MaterialButton>(R.id.reminderPreset5)
            )
        )

        setupLimitControls()
        setupReminderControls()
        setupActions()
        requestNotificationPermissionIfNeeded()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenTimeMonitorService.ACTION_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(tickReceiver, filter)
        }
        tickReceiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        refreshAdminStatus()
        syncingSwitches = true
        val monitoringEnabled = UserPreferences.isMonitoringEnabled(this)
        monitorSwitch.isChecked = monitoringEnabled
        reminderSwitch.isChecked = UserPreferences.isReminderEnabled(this)
        syncingSwitches = false
        setReminderControlsEnabled(reminderSwitch.isChecked)
        if (monitoringEnabled && devicePolicyManager.isAdminActive(adminComponent)) {
            startScreenTimeMonitor()
        }
    }

    override fun onStop() {
        if (tickReceiverRegistered) {
            unregisterReceiver(tickReceiver)
            tickReceiverRegistered = false
        }
        super.onStop()
    }

    private fun setupLimitControls() {
        DurationControl(
            seekBar = findViewById(R.id.limitSeekBar),
            input = findViewById(R.id.limitInput),
            presetButtons = limitPresets,
            range = UserPreferences.MIN_LIMIT_MINUTES..UserPreferences.MAX_LIMIT_MINUTES,
            initialValue = UserPreferences.getLimitMinutes(this),
            persistValue = { UserPreferences.setLimitMinutes(this, it) },
            updatePresetButtons = ::updatePresetButtons
        )
    }

    private fun setupReminderControls() {
        DurationControl(
            seekBar = findViewById(R.id.reminderSeekBar),
            input = findViewById(R.id.reminderInput),
            presetButtons = reminderPresets,
            range = UserPreferences.MIN_REMINDER_MINUTES..UserPreferences.MAX_REMINDER_MINUTES,
            initialValue = UserPreferences.getReminderMinutes(this),
            persistValue = { UserPreferences.setReminderMinutes(this, it) },
            updatePresetButtons = ::updatePresetButtons
        )
    }

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.adminButton).setOnClickListener {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                Toast.makeText(this, R.string.admin_already_active, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_explanation)
                    )
                })
            }
        }

        findViewById<MaterialButton>(R.id.lockTestButton).setOnClickListener {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                try {
                    devicePolicyManager.lockNow()
                } catch (_: SecurityException) {
                    Toast.makeText(this, R.string.admin_permission_expired, Toast.LENGTH_LONG).show()
                    refreshAdminStatus()
                }
            } else {
                Toast.makeText(this, R.string.admin_required, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<MaterialButton>(R.id.appSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        findViewById<MaterialButton>(R.id.chibiPortraitButton).setOnClickListener {
            showChibiPortrait()
        }

        monitorSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (enabled && !devicePolicyManager.isAdminActive(adminComponent)) {
                Toast.makeText(this, R.string.admin_required, Toast.LENGTH_LONG).show()
                syncingSwitches = true
                monitorSwitch.isChecked = false
                syncingSwitches = false
                return@setOnCheckedChangeListener
            }

            UserPreferences.setMonitoringEnabled(this, enabled)
            if (enabled) {
                startScreenTimeMonitor()
            } else {
                ScreenTimeMonitorService.stop(this)
                updateTimer(0L, false)
            }
        }

        reminderSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            UserPreferences.setReminderEnabled(this, enabled)
            setReminderControlsEnabled(enabled)
        }
    }

    private fun showChibiPortrait() {
        val density = resources.displayMetrics.density
        val maxSize = (520 * density).toInt()
        val horizontalMargin = (48 * density).toInt()
        val size = min(resources.displayMetrics.widthPixels - horizontalMargin, maxSize)
        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.chibi_portrait)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        Dialog(this).apply {
            setContentView(imageView)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.78f)
            }
            show()
            window?.setLayout(size, size)
        }
    }

    private fun startScreenTimeMonitor() {
        ScreenTimeMonitorService.start(this)
    }

    private fun updatePresetButtons(buttons: Map<Int, MaterialButton>, selectedValue: Int) {
        val selectedBackground = getColor(R.color.preset_selected_background)
        val idleBackground = getColor(R.color.preset_idle_background)
        val selectedText = getColor(R.color.preset_selected_text)
        val idleText = getColor(R.color.text_primary)
        val border = getColor(R.color.card_stroke)
        val cornerRadius = 14f * resources.displayMetrics.density
        val borderWidth = (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        buttons.forEach { (value, button) ->
            val selected = value == selectedValue
            button.isSelected = selected
            val backgroundColor = if (selected) selectedBackground else idleBackground
            val strokeColor = if (selected) selectedBackground else border
            button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
            button.strokeColor = ColorStateList.valueOf(strokeColor)
            button.strokeWidth = borderWidth
            button.cornerRadius = cornerRadius.toInt()
            button.insetTop = 0
            button.insetBottom = 0
            button.setTextColor(if (selected) selectedText else idleText)
        }
    }

    private fun setReminderControlsEnabled(enabled: Boolean) {
        reminderControls.alpha = if (enabled) 1f else 0.45f
        setEnabledRecursively(reminderControls, enabled)
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(index), enabled)
            }
        }
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.mainScroll)
        val originalLeft = root.paddingLeft
        val originalTop = root.paddingTop
        val originalRight = root.paddingRight
        val originalBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            view.setPadding(
                originalLeft,
                originalTop + topInset,
                originalRight,
                originalBottom + bottomInset
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun refreshAdminStatus() {
        val active = devicePolicyManager.isAdminActive(adminComponent)
        adminStatus.text = if (active) {
            getString(R.string.admin_status_active)
        } else {
            getString(R.string.admin_status_inactive)
        }
        adminStatus.setTextColor(
            getColor(if (active) R.color.admin_active else R.color.admin_inactive)
        )
    }

    private fun updateTimer(elapsedMs: Long, screenOn: Boolean) {
        val totalSeconds = elapsedMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        timerText.text = if (screenOn) {
            getString(R.string.screen_time_format, minutes, seconds)
        } else {
            getString(R.string.screen_time_initial)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
