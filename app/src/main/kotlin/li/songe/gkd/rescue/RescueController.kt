package li.songe.gkd.rescue

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import li.songe.gkd.R
import li.songe.gkd.isActivityVisible
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow
import li.songe.gkd.rescue.RescueUi.button
import li.songe.gkd.rescue.RescueUi.dp
import li.songe.gkd.rescue.RescueUi.space
import li.songe.gkd.rescue.RescueUi.text

/** Uses the GKD service's window token and global actions; it never selects ad controls. */
object RescueController {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var service: A11yService? = null
    @Volatile private var homeVisible = false
    @Volatile private var panel: ScrollView? = null
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var openingUntil = 0L
    private var foregroundWindow = -1
    private var overlay: Button? = null
    private var settingsJob: Job? = null
    private var receiverRegistered = false
    private var adScope: Pair<String, Int>? = null
    private var adHelp = false
    private var quickHelp = false
    private var quickRecheck: Runnable? = null
    private var positionX = -1
    private var positionY = -1
    private val adFailure = AdFailureTracker()
    private val quickGuard = QuickAppGuard()
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!unlocked()) { clearHelp(); removePanel() }
            refreshOverlay()
        }
    }

    fun onConnected(host: A11yService) {
        if (service === host) return
        service?.let(::onDestroyed)
        service = host
        foregroundPackage = windowIdentity()?.first
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else host.registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        settingsJob = host.scope.launch {
            storeFlow.map { it.enableMatch }.distinctUntilChanged().collect { protectionChanged() }
        }
        ElderRules.ensureInitialized()
    }

    /** true means this event belongs to quick-app rescue or a manual rescue surface. */
    fun onEvent(host: A11yService, event: AccessibilityEvent?): Boolean {
        if (service !== host) return false
        if (homeVisible || panel != null || !unlocked() || !storeFlow.value.enableMatch) {
            clearHelp()
            if (!unlocked()) removePanel()
            return true
        }
        val packageName = event?.packageName?.toString() ?: return false
        // A rescue button's own events must not hide the button that produced them.
        if (packageName == host.packageName) return true
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return false
        val identity = windowIdentity() ?: return false
        if (identity.first != packageName || identity.second != event.windowId) return false
        val changed = foregroundPackage != packageName || foregroundWindow != event.windowId
        if (changed) { dropAd(); quickHelp = false; cancelQuickRecheck(); openingUntil = 0L }
        foregroundPackage = packageName
        foregroundWindow = event.windowId
        if (QuickAppGuard.isHost(packageName)) {
            dropAd()
            if (changed || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                exitQuickApp(packageName, event.windowId)
            }
            refreshOverlay()
            return true
        }
        quickHelp = false
        cancelQuickRecheck()
        refreshOverlay()
        return false
    }

    fun onDestroyed(host: A11yService) {
        if (service !== host) return
        clearHelp()
        removePanel()
        settingsJob?.cancel(); settingsJob = null
        if (receiverRegistered) host.unregisterReceiver(screenReceiver)
        receiverRegistered = false
        quickGuard.reset()
        foregroundPackage = null; foregroundWindow = -1
        openingUntil = 0L
        service = null
        main.removeCallbacksAndMessages(null)
    }

    fun onInterrupted() = runMain { clearHelp(); openingUntil = 0L }

    /** Checked by the upstream engine before queued queries and before an action. */
    fun shouldSuspendMatching(): Boolean = shouldSuspendObserving()
            || openingUntil > SystemClock.elapsedRealtime()

    /** A blocked rescue Activity launch must not stop fresh ad-presence observations. */
    fun shouldSuspendObserving(): Boolean = service == null || homeVisible || isActivityVisible
            || panel != null || !storeFlow.value.enableMatch || !unlocked()
            || QuickAppGuard.isHost(foregroundPackage)

    /** The engine calls this only after a fresh query of the same ad rule and window. */
    fun onAdPresent(packageName: String, windowId: Int, present: Boolean) = runMain {
        val scope = packageName to windowId
        if (!present) {
            if (adScope == scope) { dropAd(); refreshOverlay() }
        } else if (!shouldSuspendObserving() && isForeground(scope)) {
            adScope = scope
            adFailure.observe("$packageName#$windowId", true, SystemClock.elapsedRealtime())
            adHelp = adFailure.needsHelp(SystemClock.elapsedRealtime())
            refreshOverlay()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onRuleAttempt(packageName: String, windowId: Int, accepted: Boolean) = runMain {
        // ACTION_CLICK's return value confirms a request, never that the advertisement is gone.
        if (adScope == (packageName to windowId) && !shouldSuspendMatching()
            && isForeground(packageName to windowId)) {
            adHelp = adFailure.needsHelp(SystemClock.elapsedRealtime())
            refreshOverlay()
        }
    }

    fun isReady(): Boolean {
        val current = service ?: return false
        val capabilities = current.serviceInfo?.capabilities ?: return false
        return A11yService.instance === current && capabilities.and(
            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
    }

    fun setHomeVisible(visible: Boolean) {
        homeVisible = visible
        runMain { if (visible) { openingUntil = 0L; clearHelp() }; refreshOverlay() }
    }

    fun protectionChanged() = runMain {
        if (!storeFlow.value.enableMatch) { clearHelp(); quickGuard.reset() }
        refreshOverlay()
    }

    fun dismissPanel() = runMain { removePanel(); refreshOverlay() }

    fun requestRescue() = runMain {
        val current = service ?: return@runMain
        if (!unlocked()) return@runMain
        cancelQuickRecheck()
        openingUntil = SystemClock.elapsedRealtime() + 1_200L
        if (Build.VERSION.SDK_INT < 31) showPanel()
        else {
            try {
                current.startActivity(Intent(current, ElderHomeActivity::class.java)
                    .putExtra(ElderHomeActivity.EXTRA_RESCUE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                // Leave a failed-ad button in place until the Activity confirms visibility.
                main.postDelayed({ refreshOverlay() }, 1_300L)
            } catch (_: RuntimeException) {
                openingUntil = 0L
                showPanel()
            }
        }
    }

    fun requestBackAfterExit() {
        val deadline = SystemClock.elapsedRealtime() + 1_200L
        main.postDelayed(object : Runnable {
            override fun run() {
                if (service == null || !unlocked()) return
                if (homeVisible) {
                    if (SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 60L)
                    else notify(R.string.elder_back_failed)
                } else performBack()
            }
        }, 150L)
    }

    private fun exitQuickApp(packageName: String, windowId: Int) {
        if (!storeFlow.value.enableMatch || !unlocked() || !isForeground(packageName to windowId)) return
        when (quickGuard.onForeground(packageName, SystemClock.elapsedRealtime())) {
            QuickAppGuard.Decision.IGNORE -> scheduleQuickRecheck(packageName, windowId)
            QuickAppGuard.Decision.HELP -> { quickHelp = true; cancelQuickRecheck(); refreshOverlay() }
            QuickAppGuard.Decision.RETURN_HOME -> {
                if (service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) != true) {
                    quickHelp = true; cancelQuickRecheck(); refreshOverlay()
                } else {
                    quickHelp = false
                    scheduleQuickRecheck(packageName, windowId)
                }
            }
        }
    }

    private fun scheduleQuickRecheck(packageName: String, windowId: Int) {
        cancelQuickRecheck()
        quickRecheck = Runnable {
            quickRecheck = null
            if (homeVisible || panel != null) return@Runnable
            if (isForeground(packageName to windowId)) exitQuickApp(packageName, windowId)
            else { quickHelp = false; refreshOverlay() }
        }.also { main.postDelayed(it, 1_500L) }
    }

    private fun cancelQuickRecheck() { quickRecheck?.let(main::removeCallbacks); quickRecheck = null }
    private fun dropAd() { adScope = null; adHelp = false; adFailure.reset() }
    private fun clearHelp() { dropAd(); quickHelp = false; cancelQuickRecheck(); removeOverlay() }

    private fun refreshOverlay() {
        val current = service ?: return
        if (!unlocked()) { clearHelp(); removePanel(); return }
        if (homeVisible || panel != null || !storeFlow.value.enableMatch) { clearHelp(); return }
        // Most events need no help button; avoid a second accessibility-window read.
        if (!adHelp && !quickHelp) { removeOverlay(); return }
        val identity = windowIdentity()
        if (identity == null) { removeOverlay(); return }
        if (identity.first == current.packageName) {
            // Our accessibility overlay becoming active is not evidence that its ad disappeared.
            if (isActivityVisible) removeOverlay()
            return
        }
        if (adHelp && adScope != identity) dropAd()
        if (quickHelp && !QuickAppGuard.isHost(identity.first)) quickHelp = false
        if (!adHelp && !quickHelp) { removeOverlay(); return }
        if (overlay != null) return
        val entry = button(current, current.getString(R.string.elder_rescue_button), true).apply {
            textSize = 22f
            minHeight = dp(current, 64)
            contentDescription = current.getString(R.string.elder_rescue_button_description)
            setOnClickListener { requestRescue() }
        }
        val metrics = current.resources.displayMetrics
        val maxWidth = minOf(dp(current, 240), metrics.widthPixels - dp(current, 16)).coerceAtLeast(1)
        entry.maxWidth = maxWidth
        entry.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST))
        if (positionX < 0) positionX = metrics.widthPixels - entry.measuredWidth - dp(current, 8)
        if (positionY < 0) positionY = (metrics.heightPixels - entry.measuredHeight) / 2
        positionX = positionX.coerceIn(0, (metrics.widthPixels - entry.measuredWidth).coerceAtLeast(0))
        positionY = positionY.coerceIn(0, (metrics.heightPixels - entry.measuredHeight).coerceAtLeast(0))
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = positionX; y = positionY }
        installDrag(entry, params)
        try { current.wm.addView(entry, params); overlay = entry }
        catch (_: RuntimeException) { notify(R.string.elder_overlay_failed) }
    }

    private fun installDrag(entry: Button, params: WindowManager.LayoutParams) {
        val current = service ?: return
        val slop = ViewConfiguration.get(current).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        entry.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = positionX; startY = positionY
                    dragging = false; view.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    dragging = dragging || kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop
                    if (dragging) {
                        view.isPressed = false
                        val metrics = current.resources.displayMetrics
                        positionX = (startX + dx.toInt()).coerceIn(0, (metrics.widthPixels - entry.width).coerceAtLeast(0))
                        positionY = (startY + dy.toInt()).coerceIn(0, (metrics.heightPixels - entry.height).coerceAtLeast(0))
                        params.x = positionX; params.y = positionY
                        try { current.wm.updateViewLayout(entry, params) }
                        catch (_: RuntimeException) { removeOverlay() }
                    }
                }
                MotionEvent.ACTION_UP -> { view.isPressed = false; if (!dragging) view.performClick() }
                MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            true
        }
    }

    private fun showPanel() {
        val current = service ?: return
        if (panel != null || !unlocked()) return
        val page = object : ScrollView(current) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) { removePanel(); refreshOverlay() }
                return true
            }
        }.apply { isFillViewport = true; setBackgroundColor(RescueUi.background) }
        val content = LinearLayout(current).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(current, 24), dp(current, 28), dp(current, 24), dp(current, 28))
        }
        page.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        content.addView(text(current, current.getString(R.string.elder_rescue_title), 34f, true))
        space(content, 18)
        content.addView(text(current, current.getString(R.string.elder_rescue_detail), 24f))
        space(content, 32)
        content.addView(button(current, current.getString(R.string.elder_home), true).apply {
            textSize = 32f; minHeight = dp(current, 96)
            setOnClickListener {
                if (current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
                    removePanel(); refreshOverlay()
                } else notify(R.string.elder_home_failed)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 16)
        content.addView(button(current, current.getString(R.string.elder_back)).apply {
            setOnClickListener {
                openingUntil = SystemClock.elapsedRealtime() + 600L
                removePanel(); main.post { performBack() }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        space(content, 16)
        content.addView(button(current, current.getString(R.string.elder_close)).apply {
            setOnClickListener { removePanel(); refreshOverlay() }
        }, LinearLayout.LayoutParams(-1, -2))
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0, PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        try {
            current.wm.addView(page, params); panel = page; clearHelp(); page.requestFocus()
        } catch (_: RuntimeException) { openingUntil = 0L; notify(R.string.elder_overlay_failed); refreshOverlay() }
    }

    private fun performBack() {
        if (!unlocked()) return
        openingUntil = SystemClock.elapsedRealtime() + 600L
        clearHelp()
        if (service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) != true) {
            openingUntil = 0L; showPanel(); notify(R.string.elder_back_failed)
        }
    }

    private fun removePanel() {
        val view = panel ?: return
        panel = null
        try { service?.wm?.removeViewImmediate(view) } catch (_: RuntimeException) { }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        try { service?.wm?.removeViewImmediate(view) } catch (_: RuntimeException) { }
    }

    private fun unlocked(): Boolean {
        val current = service ?: return false
        return current.getSystemService(PowerManager::class.java)?.isInteractive == true
                && current.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }

    @Suppress("DEPRECATION")
    private fun windowIdentity(): Pair<String, Int>? {
        val root = try { service?.rootInActiveWindow } catch (_: RuntimeException) { null } ?: return null
        return try { root.packageName?.toString()?.let { it to root.windowId } } finally { root.recycle() }
    }

    private fun isForeground(identity: Pair<String, Int>) = windowIdentity() == identity
    private fun notify(resource: Int) { service?.let { Toast.makeText(it, resource, Toast.LENGTH_LONG).show() } }
    private fun runMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }
}
