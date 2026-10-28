package li.songe.gkd.rescue

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.songe.gkd.META
import li.songe.gkd.R
import li.songe.gkd.appScope
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.a11y.updateTopActivity
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow
import li.songe.gkd.rescue.RescueUi.button
import li.songe.gkd.rescue.RescueUi.dp
import li.songe.gkd.rescue.RescueUi.space
import li.songe.gkd.rescue.RescueUi.text

class ElderHomeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var rescue = false
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var mainAction: Button? = null
    private var mainActionPrimary: Boolean? = null
    private var memoryDialog: AlertDialog? = null
    private var memoryJob: Job? = null
    private var feedbackDialog: AlertDialog? = null
    private var appUpdateJob: Job? = null
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 1_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ElderRules.ensureInitialized()
        window.navigationBarColor = RescueUi.background
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        rescue = savedInstanceState?.getBoolean(EXTRA_RESCUE)
            ?: intent.getBooleanExtra(EXTRA_RESCUE, false)
        render()
        if (savedInstanceState == null) RescueController.dismissPanel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        rescue = intent.getBooleanExtra(EXTRA_RESCUE, false)
        render()
        RescueController.setHomeVisible(true)
        RescueController.dismissPanel()
    }

    override fun onStart() {
        super.onStart()
        RescueController.setHomeVisible(true)
        synchronized(topActivityFlow) { updateTopActivity(packageName, javaClass.name) }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onStop() { RescueController.setHomeVisible(false); super.onStop() }
    override fun onDestroy() {
        memoryDialog?.dismiss()
        memoryJob?.cancel()
        feedbackDialog?.dismiss()
        appUpdateJob?.cancel()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_RESCUE, rescue)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        statusTitle = null; statusDetail = null; mainAction = null; mainActionPrimary = null
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(RescueUi.background)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        frame.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@ElderHomeActivity, 24), dp(this@ElderHomeActivity, 28),
                dp(this@ElderHomeActivity, 24), dp(this@ElderHomeActivity, 28))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        content.addView(text(this, getString(R.string.elder_app_name), 25f, true))
        space(content, 24)
        if (rescue) renderRescue(content) else renderHome(content)
        setContentView(frame)
        frame.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        title = getString(if (rescue) R.string.elder_rescue_title else R.string.elder_app_name)
    }

    private fun renderHome(content: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 22), dp(context, 24), dp(context, 22), dp(context, 24))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = dp(this@ElderHomeActivity, 22).toFloat()
            }
        }
        statusTitle = text(this, "", 36f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }.also { card.addView(it) }
        space(card, 18)
        statusDetail = text(this, "", 24f).also { card.addView(it) }
        content.addView(card, LinearLayout.LayoutParams(-1, -2))
        space(content, 24)
        mainAction = button(this, "", true).apply {
            setOnClickListener {
                if (!RescueController.isReady()) showSetup()
                else if (!ElderRules.ready) checkRuleUpdates()
                else {
                    storeFlow.update { it.copy(enableMatch = !it.enableMatch) }
                    RescueController.protectionChanged()
                    updateStatus()
                }
            }
        }.also {
            mainActionPrimary = true
            content.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        space(content, 28)
        content.addView(button(this, getString(R.string.elder_settings)).apply {
            textSize = 22f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(-1, -2))
        updateStatus()
    }

    private fun renderRescue(content: LinearLayout) {
        content.addView(text(this, getString(R.string.elder_rescue_title), 34f, true))
        space(content, 18)
        content.addView(text(this, getString(R.string.elder_rescue_detail), 24f))
        space(content, 32)
        content.addView(button(this, getString(R.string.elder_home), true).apply {
            textSize = 32f; minHeight = dp(this@ElderHomeActivity, 96)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                } catch (_: ActivityNotFoundException) { message(R.string.elder_home_failed) }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 16)
        content.addView(button(this, getString(R.string.elder_back)).apply {
            setOnClickListener {
                if (!RescueController.isReady()) showSetup()
                else if (moveTaskToBack(true)) RescueController.requestBackAfterExit()
                else message(R.string.elder_back_failed)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 16)
        content.addView(button(this, getString(R.string.elder_close)).apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun updateStatus() {
        val ready = RescueController.isReady()
        val enabled = A11yService.instance != null || serviceEnabled()
        val paused = !storeFlow.value.enableMatch
        val preparing = ready && !ElderRules.ready
        val title = if (!ready) R.string.elder_permission_needed else if (preparing) R.string.elder_preparing
            else if (paused) R.string.elder_paused else R.string.elder_active
        val detail = if (!ready) {
            if (enabled) R.string.elder_reconnect_detail else R.string.elder_permission_detail
        } else if (preparing) R.string.elder_preparing_detail
            else if (paused) R.string.elder_paused_detail else R.string.elder_active_detail
        val action = if (!ready) R.string.elder_authorize else if (preparing) R.string.elder_rule_updates
            else if (paused) R.string.elder_resume else R.string.elder_pause
        setText(statusTitle, title); setText(statusDetail, detail); setText(mainAction, action)
        val primary = !ready || preparing || paused
        mainAction?.let { view ->
            if (mainActionPrimary != primary) {
                RescueUi.applyButtonStyle(view, primary)
                mainActionPrimary = primary
            }
        }
    }

    private fun serviceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.let {
                ComponentName(it.packageName, it.name) == A11yService.a11yCn
            } }
    }

    private fun showSetup() = showLarge(dialogBuilder(R.string.elder_authorize)
        .setMessage(if (serviceEnabled()) R.string.elder_reconnect_detail else R.string.elder_setup_message)
        .setPositiveButton(R.string.elder_open_settings) { _, _ ->
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: ActivityNotFoundException) { message(R.string.elder_settings_failed) }
        }.setNegativeButton(R.string.elder_later, null).create())

    private fun showSettings() {
        val items = arrayOf(getString(R.string.elder_rule_updates), getString(R.string.elder_optional_features),
            getString(R.string.elder_memory), getString(R.string.elder_tile_help),
            getString(R.string.elder_feedback), getString(R.string.elder_instructions),
            getString(R.string.elder_open_source), getString(R.string.elder_system_ads),
            getString(R.string.elder_app_updates))
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 22f; minHeight = dp(context, 64); setTextColor(RescueUi.ink)
                }
        }
        showLarge(dialogBuilder(R.string.elder_settings)
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> showLarge(dialogBuilder(R.string.elder_rule_updates)
                        .setMessage(ElderRules.statusText() + if (getString(R.string.elder_rules_url).isBlank())
                            "\n\n" + getString(R.string.elder_own_rules_unpublished) else "")
                        .setPositiveButton(R.string.elder_check_now) { _, _ -> checkRuleUpdates() }
                        .setNegativeButton(R.string.elder_close_dialog, null).create())
                    1 -> showOptionalFeatures()
                    2 -> showMemory()
                    3 -> showText(R.string.elder_tile_help, R.string.elder_tile_message)
                    4 -> showFeedback()
                    5 -> showText(R.string.elder_instructions, R.string.elder_help_message)
                    6 -> showText(R.string.elder_open_source, R.string.elder_open_source_message)
                    7 -> showSystemAds()
                    8 -> checkAppUpdates()
                }
            }.setNegativeButton(R.string.elder_close_dialog, null).create())
    }

    private fun showSystemAds() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 8))
        }
        content.addView(text(this, getString(R.string.elder_system_ads_message), 22f))
        for ((label, action) in listOf(
            R.string.elder_ad_notifications to if (Build.VERSION.SDK_INT >= 33)
                Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS else Settings.ACTION_APPLICATION_SETTINGS,
            R.string.elder_ad_recommendations to Settings.ACTION_SETTINGS)) {
            space(content, 16)
            content.addView(button(this, getString(label)).apply {
                setOnClickListener { openSystemPage(Intent(action)) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        space(content, 16)
        content.addView(button(this, getString(R.string.elder_quick_apps)).apply {
            setOnClickListener { showQuickAppSettings() }
        }, LinearLayout.LayoutParams(-1, -2))
        showLarge(dialogBuilder(R.string.elder_system_ads)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.elder_close_dialog, null).create())
    }

    private fun showQuickAppSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 8))
        }
        content.addView(text(this, getString(R.string.elder_quick_apps_message), 22f))
        val hosts = listOf("com.hihonor.fastapp", "com.huawei.fastapp", "com.miui.hybrid").mapNotNull { id ->
            try { packageManager.getApplicationInfo(id, 0) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            catch (_: SecurityException) { null }
        }
        for (host in hosts) {
            space(content, 20)
            content.addView(text(this, packageManager.getApplicationLabel(host).toString(), 24f, true))
            space(content, 12)
            content.addView(button(this, getString(R.string.elder_quick_app_details)).apply {
                setOnClickListener { openSystemPage(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${host.packageName}"))) }
            }, LinearLayout.LayoutParams(-1, -2))
            space(content, 12)
            content.addView(button(this, getString(R.string.elder_quick_app_notifications)).apply {
                setOnClickListener { openSystemPage(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName)) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (hosts.isEmpty()) {
            space(content, 20)
            content.addView(text(this, getString(R.string.elder_quick_app_not_found), 22f))
            content.addView(button(this, getString(R.string.elder_manage_apps)).apply {
                setOnClickListener { openSystemPage(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        showLarge(dialogBuilder(R.string.elder_quick_apps)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.elder_close_dialog, null).create())
    }

    private fun openSystemPage(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { openMemorySettings(Settings.ACTION_SETTINGS) }
        catch (_: SecurityException) { openMemorySettings(Settings.ACTION_SETTINGS) }
    }

    private fun checkAppUpdates() {
        if (appUpdateJob?.isActive == true) return
        val address = getString(R.string.elder_app_update_url)
        if (address.isBlank()) {
            showText(R.string.elder_app_updates, R.string.elder_app_update_unpublished)
            return
        }
        message(R.string.elder_app_update_checking)
        appUpdateJob = appScope.launch {
            val release = try { withContext(Dispatchers.IO) { ElderAppUpdates.check(address) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(R.string.elder_app_update_failed); return@launch }
            if (isFinishing || isDestroyed) return@launch
            if (release.versionCode <= META.versionCode) {
                message(R.string.elder_app_update_latest)
                return@launch
            }
            showLarge(dialogBuilder(R.string.elder_app_updates)
                .setMessage(getString(R.string.elder_app_update_available, release.versionName, release.changelog))
                .setPositiveButton(R.string.elder_app_update_download) { _, _ ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))) }
                    catch (_: ActivityNotFoundException) { message(R.string.elder_app_update_download_failed) }
                    catch (_: SecurityException) { message(R.string.elder_app_update_download_failed) }
                }.setNegativeButton(R.string.elder_later, null).create())
        }
    }

    private fun showOptionalFeatures() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16))
        }
        content.addView(CheckBox(this).apply {
            setText(R.string.elder_automatic_rule_updates)
            textSize = 22f
            setTextColor(RescueUi.ink)
            minHeight = dp(context, 80)
            isChecked = RuntimeOptions.automaticRuleUpdatesEnabled
            setOnCheckedChangeListener { _, checked -> ElderRules.setAutomaticRuleUpdates(checked) }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 12)
        content.addView(CheckBox(this).apply {
            setText(R.string.elder_diagnostic_records)
            textSize = 22f
            setTextColor(RescueUi.ink)
            minHeight = dp(context, 80)
            isChecked = RuntimeOptions.diagnosticEnabled
            setOnCheckedChangeListener { _, checked -> RuntimeOptions.setDiagnosticEnabled(checked) }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 24)
        content.addView(text(this, getString(R.string.elder_optional_features_note), 22f))
        showLarge(dialogBuilder(R.string.elder_optional_features)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.elder_close_dialog, null).create())
    }

    private fun showFeedback() {
        feedbackDialog?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 8))
        }
        val version = META.versionName.take(80)
        val phoneModel = "${Build.MANUFACTURER.take(80)} ${Build.MODEL.take(80)}"
        val androidVersion = Build.VERSION.RELEASE.take(80)
        content.addView(text(this, getString(R.string.elder_feedback_auto_info,
            version, phoneModel, androidVersion), 22f, true))
        space(content, 16)
        content.addView(text(this, getString(R.string.elder_feedback_intro), 22f))
        space(content, 16)
        content.addView(text(this, getString(R.string.elder_feedback_type), 22f, true))
        val typeNames = arrayOf(R.string.elder_feedback_skip, R.string.elder_feedback_wrong,
            R.string.elder_feedback_rescue, R.string.elder_feedback_other).map { getString(it) }
        val typeAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, typeNames) {
            private fun largeOption(view: View) = (view as TextView).apply {
                textSize = 22f; minHeight = dp(context, 64); setTextColor(RescueUi.ink)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                largeOption(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                largeOption(super.getDropDownView(position, convertView, parent))
        }
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val types = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            adapter = typeAdapter
            minimumHeight = dp(context, 64)
        }
        content.addView(types, LinearLayout.LayoutParams(-1, -2))
        space(content, 16)
        content.addView(text(this, getString(R.string.elder_feedback_app), 22f, true))
        val appName = EditText(this).apply {
            setHint(R.string.elder_feedback_app_hint)
            textSize = 22f
            minHeight = dp(context, 76)
            setTextColor(RescueUi.ink)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(80))
        }
        content.addView(appName, LinearLayout.LayoutParams(-1, -2))
        space(content, 16)
        content.addView(text(this, getString(R.string.elder_feedback_detail), 22f, true))
        val detail = EditText(this).apply {
            setHint(R.string.elder_feedback_detail_hint)
            textSize = 22f
            setTextColor(RescueUi.ink)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = android.view.Gravity.TOP
            filters = arrayOf(InputFilter.LengthFilter(1000))
        }
        content.addView(detail, LinearLayout.LayoutParams(-1, -2))
        fun report(): String = getString(R.string.elder_feedback_report,
            version, phoneModel, androidVersion, Build.VERSION.SDK_INT, types.selectedItem.toString(),
            appName.text.toString().trim().take(80).ifEmpty { getString(R.string.elder_feedback_empty) },
            detail.text.toString().trim().take(1000).ifEmpty { getString(R.string.elder_feedback_empty) }
        ).take(1800)
        space(content, 20)
        content.addView(button(this, getString(R.string.elder_feedback_copy), true).apply {
            textSize = 22f
            setOnClickListener {
                try {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                        ?: error("Clipboard unavailable")
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.elder_feedback), report()))
                    message(R.string.elder_feedback_copied)
                } catch (_: RuntimeException) { message(R.string.elder_feedback_copy_failed) }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 12)
        content.addView(button(this, getString(R.string.elder_feedback_share)).apply {
            textSize = 22f
            setOnClickListener {
                try {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, report())
                    startActivity(Intent.createChooser(send, getString(R.string.elder_feedback_share)))
                } catch (_: ActivityNotFoundException) { message(R.string.elder_feedback_share_failed) }
                catch (_: SecurityException) { message(R.string.elder_feedback_share_failed) }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        val dialog = dialogBuilder(R.string.elder_feedback)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.elder_close_dialog, null).create()
        feedbackDialog = dialog
        dialog.setOnDismissListener { if (feedbackDialog === dialog) feedbackDialog = null }
        showLarge(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight = dp(this, 76)
    }

    private fun showMemory() {
        memoryJob?.cancel()
        memoryJob = null
        memoryDialog?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 8))
        }
        val reading = text(this, getString(R.string.elder_memory_reading), 24f, true)
        content.addView(reading)
        space(content, 20)
        content.addView(text(this, getString(R.string.elder_memory_explanation), 22f))
        space(content, 20)
        content.addView(button(this, getString(R.string.elder_manage_apps)).apply {
            setOnClickListener { openMemorySettings(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS) }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 12)
        content.addView(button(this, getString(R.string.elder_startup_help)).apply {
            setOnClickListener {
                showLarge(dialogBuilder(R.string.elder_startup_help)
                    .setMessage(R.string.elder_startup_message)
                    .setPositiveButton(R.string.elder_open_settings) { _, _ ->
                        openMemorySettings(Settings.ACTION_SETTINGS)
                    }.setNegativeButton(R.string.elder_close_dialog, null).create())
            }
        }, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = dialogBuilder(R.string.elder_memory)
            .setView(scroll)
            .setPositiveButton(R.string.elder_memory_refresh, null)
            .setNegativeButton(R.string.elder_close_dialog, null).create()
        memoryDialog = dialog
        dialog.setOnDismissListener {
            if (memoryDialog === dialog) {
                memoryJob?.cancel()
                memoryJob = null
                memoryDialog = null
            }
        }
        showLarge(dialog)
        val refreshButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        // Sample only on demand; no memory polling service or extra permission.
        fun sample() {
            refreshButton.isEnabled = false
            memoryJob = appScope.launch(Dispatchers.Main) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val system = ActivityManager.MemoryInfo()
                        val manager = getSystemService(ActivityManager::class.java)
                            ?: error("Memory service unavailable")
                        manager.getMemoryInfo(system)
                        val own = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                        getString(R.string.elder_memory_values,
                            system.totalMem / 1_073_741_824.0,
                            system.availMem / 1_073_741_824.0,
                            own.totalPss / 1024.0,
                            getString(if (system.lowMemory) R.string.elder_memory_low
                                else R.string.elder_memory_normal))
                    }.getOrElse { getString(R.string.elder_memory_failed) }
                }
                if (dialog.isShowing && !isFinishing && !isDestroyed) {
                    reading.text = result
                    refreshButton.isEnabled = true
                }
            }
        }
        refreshButton.setOnClickListener { sample() }
        sample()
    }

    private fun openMemorySettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: ActivityNotFoundException) { message(R.string.elder_memory_settings_failed) }
        catch (_: SecurityException) { message(R.string.elder_memory_settings_failed) }
    }

    private fun showText(title: Int, body: Int) = showLarge(dialogBuilder(title)
        .setMessage(body).setPositiveButton(R.string.elder_understood, null).create())

    private fun dialogBuilder(title: Int) = AlertDialog.Builder(this).setCustomTitle(
        text(this, getString(title), 24f, true).apply {
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 8))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    )

    private fun checkRuleUpdates() {
        message(R.string.elder_checking)
        ElderRules.checkUpdates { result ->
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }
    }

    private fun showLarge(dialog: AlertDialog) {
        dialog.show()
        if (Build.VERSION.SDK_INT >= 31) dialog.window?.setHideOverlayWindows(true)
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 22f
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply { textSize = 22f; minHeight = dp(context, 64) }
        }
    }

    private fun setText(view: TextView?, resource: Int) {
        if (view != null && view.text.toString() != getString(resource)) view.setText(resource)
    }
    private fun message(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_LONG).show()

    companion object { const val EXTRA_RESCUE = "rescue" }
}
