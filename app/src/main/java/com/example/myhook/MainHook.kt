package com.example.myhook

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private var controlCenterOverlay: FrameLayout? = null
    private var isShowing = false
    private var isTorchOn = false
    private var torchCameraId: String? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("iOSControlCenter: Hooked into SystemUI")

        // Hook Status Bar touch events to detect top-right swipe down
        val statusBarClasses = listOf(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.shade.NotificationPanelView"
        )

        for (className in statusBarClasses) {
            val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
            XposedBridge.hookAllMethods(clazz, "dispatchTouchEvent", object : XC_MethodHook() {
                private var startX = 0f
                private var startY = 0f

                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    val event = param.args[0] as MotionEvent
                    val screenWidth = view.resources.displayMetrics.widthPixels

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaY = event.rawY - startY
                            // Trigger when swiping down on the right 45% of the status bar
                            if (startY < dp(view.context, 48) &&
                                startX > screenWidth * 0.55f &&
                                deltaY > dp(view.context, 36) &&
                                !isShowing
                            ) {
                                showControlCenter(view.context.applicationContext)
                                // Cancel native status bar pull-down
                                val cancelEvent = MotionEvent.obtain(event)
                                cancelEvent.action = MotionEvent.ACTION_CANCEL
                                param.args[0] = cancelEvent
                            }
                        }
                    }
                }
            })
        }

        // Also restyle native Quick Settings tiles with iOS squircle corners
        val qsTileClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.qs.tileimpl.QSTileViewImpl",
            lpparam.classLoader
        )
        if (qsTileClass != null) {
            XposedBridge.hookAllConstructors(qsTileClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tileView = param.thisObject as View
                    tileView.post {
                        tileView.alpha = 0.95f
                    }
                }
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showControlCenter(context: Context) {
        if (isShowing) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#D9101218"))
            alpha = 0f
            setOnClickListener { hideControlCenter(wm) }
        }

        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 22), dp(context, 56), dp(context, 22), dp(context, 28))
            translationY = -dp(context, 120).toFloat()
            isClickable = true
        }

        // --- ROW 1: 2x2 Connectivity Box + Media Player Card ---
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val connectivityCard = buildConnectivityCard(context, wm)
        val mediaCard = buildMediaCard(context)

        topRow.addView(connectivityCard, LinearLayout.LayoutParams(0, dp(context, 155), 1f).apply {
            marginEnd = dp(context, 8)
        })
        topRow.addView(mediaCard, LinearLayout.LayoutParams(0, dp(context, 155), 1f).apply {
            marginStart = dp(context, 8)
        })
        mainContainer.addView(topRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 16) })

        // --- ROW 2: Mini Toggles (Rotate + Silent) + Vertical Brightness & Volume Sliders ---
        val middleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val miniTogglesColumn = buildMiniTogglesColumn(context)
        val slidersBox = buildSlidersRow(context)

        middleRow.addView(miniTogglesColumn, LinearLayout.LayoutParams(0, dp(context, 165), 1f).apply {
            marginEnd = dp(context, 8)
        })
        middleRow.addView(slidersBox, LinearLayout.LayoutParams(0, dp(context, 165), 1f).apply {
            marginStart = dp(context, 8)
        })
        mainContainer.addView(middleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 16) })

        // --- ROW 3: Bottom 4 Action Squircles (Flashlight, Calculator, Camera, Settings) ---
        val bottomRow = buildBottomActionRow(context, wm)
        mainContainer.addView(bottomRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Pill Grabber at bottom
        val grabber = View(context).apply {
            background = roundedDrawable("#66FFFFFF", dp(context, 4).toFloat())
        }
        mainContainer.addView(grabber, LinearLayout.LayoutParams(dp(context, 44), dp(context, 5)).apply {
            topMargin = dp(context, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(mainContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        // Try standard system window types permitted inside SystemUI
        val windowTypes = intArrayOf(
            2014, // TYPE_STATUS_BAR_PANEL
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        )

        var added = false
        for (type in windowTypes) {
            try {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                wm.addView(root, params)
                added = true
                break
            } catch (e: Throwable) {
                XposedBridge.log("iOSControlCenter: Window type $type failed, trying fallback")
            }
        }

        if (added) {
            controlCenterOverlay = root
            isShowing = true
            ObjectAnimator.ofFloat(root, "alpha", 0f, 1f).apply {
                duration = 220
                start()
            }
            ObjectAnimator.ofFloat(mainContainer, "translationY", -dp(context, 120).toFloat(), 0f).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    private fun hideControlCenter(wm: WindowManager) {
        val overlay = controlCenterOverlay ?: return
        ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f).apply {
            duration = 180
            start()
        }
        overlay.postDelayed({
            try {
                wm.removeView(overlay)
            } catch (_: Throwable) {}
            controlCenterOverlay = null
            isShowing = false
        }, 185)
    }

    private fun buildConnectivityCard(context: Context, wm: WindowManager): View {
        val grid = GridLayout(context).apply {
            rowCount = 2
            columnCount = 2
            background = roundedDrawable("#A6252730", dp(context, 26).toFloat())
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        }

        val items = listOf(
            Triple("✈️", "#FF9500", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            Triple("📶", "#34C759", Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Triple("🛜", "#007AFF", Settings.ACTION_WIFI_SETTINGS),
            Triple("ᛒ", "#007AFF", Settings.ACTION_BLUETOOTH_SETTINGS)
        )

        items.forEachIndexed { index, (icon, activeColor, settingsAction) ->
            var active = index != 0 // Default Wi-Fi, Data, BT active visually
            val circle = TextView(context).apply {
                text = icon
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                background = roundedDrawable(
                    if (active) activeColor else "#38FFFFFF",
                    dp(context, 28).toFloat()
                )
                setOnClickListener {
                    active = !active
                    background = roundedDrawable(
                        if (active) activeColor else "#38FFFFFF",
                        dp(context, 28).toFloat()
                    )
                }
                setOnLongClickListener {
                    hideControlCenter(wm)
                    launchIntent(context, Intent(settingsAction))
                    true
                }
            }
            val size = dp(context, 54)
            val lp = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            }
            grid.addView(circle, lp)
        }
        return grid
    }

    private fun buildMediaCard(context: Context): View {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedDrawable("#A6252730", dp(context, 26).toFloat())
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
        }

        val title = TextView(context).apply {
            text = if (audioManager.isMusicActive) "Now Playing" else "Music"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(context).apply {
            text = "Media Controller"
            setTextColor(Color.parseColor("#99EBEBF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 16), 0, 0)
        }

        val prevBtn = mediaButton(context, "⏮") {
            sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        val playBtn = mediaButton(context, "⏯") {
            sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            title.text = if (!audioManager.isMusicActive) "Now Playing" else "Paused"
        }
        val nextBtn = mediaButton(context, "⏭") {
            sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        controlsRow.addView(prevBtn)
        controlsRow.addView(playBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(context, 14); marginEnd = dp(context, 14) })
        controlsRow.addView(nextBtn)

        box.addView(title)
        box.addView(subtitle)
        box.addView(controlsRow)
        return box
    }

    private fun buildMiniTogglesColumn(context: Context): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            weightSum = 2f
        }

        // 1. Auto-Rotate Lock Capsule
        val isRotateOn = Settings.System.getInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 1
        ) == 1
        var rotateLocked = !isRotateOn

        val rotateCard = createCapsuleToggle(
            context,
            icon = "🔒",
            label = if (rotateLocked) "Lock: On" else "Lock: Off",
            activeColor = "#FF3B30",
            initialState = rotateLocked
        ) { newState, tv ->
            rotateLocked = newState
            tv.text = if (rotateLocked) "🔒  Lock: On" else "🔓  Lock: Off"
            try {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    if (rotateLocked) 0 else 1
                )
            } catch (_: Throwable) {}
        }

        // 2. Silent / Ringer Mode Capsule
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var isSilent = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL

        val silentCard = createCapsuleToggle(
            context,
            icon = "🔔",
            label = if (isSilent) "Silent" else "Ring",
            activeColor = "#FF9500",
            initialState = isSilent
        ) { newState, tv ->
            isSilent = newState
            tv.text = if (isSilent) "🔕  Silent" else "🔔  Ring"
            try {
                audioManager.ringerMode = if (isSilent) {
                    AudioManager.RINGER_MODE_VIBRATE
                } else {
                    AudioManager.RINGER_MODE_NORMAL
                }
            } catch (_: Throwable) {}
        }

        col.addView(rotateCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = dp(context, 12) })

        col.addView(silentCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return col
    }

    private fun createCapsuleToggle(
        context: Context,
        icon: String,
        label: String,
        activeColor: String,
        initialState: Boolean,
        onToggle: (Boolean, TextView) -> Unit
    ): View {
        var state = initialState
        return TextView(context).apply {
            text = "$icon  $label"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedDrawable(
                if (state) activeColor else "#A6252730",
                dp(context, 22).toFloat()
            )
            setOnClickListener {
                state = !state
                background = roundedDrawable(
                    if (state) activeColor else "#A6252730",
                    dp(context, 22).toFloat()
                )
                onToggle(state, this)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSlidersRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        // Current Brightness (0f..1f)
        val curBrightness = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128
        ) / 255f

        val brightnessSlider = createVerticalPillSlider(context, "☀️", curBrightness) { ratio ->
            try {
                val value = (ratio * 255).toInt().coerceIn(5, 255)
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value
                )
            } catch (_: Throwable) {}
        }

        // Current Media Volume (0f..1f)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol

        val volumeSlider = createVerticalPillSlider(context, "🔊", curVol) { ratio ->
            try {
                val targetVol = (ratio * maxVol).toInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            } catch (_: Throwable) {}
        }

        row.addView(brightnessSlider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dp(context, 8)
        })
        row.addView(volumeSlider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(context, 8)
        })
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createVerticalPillSlider(
        context: Context,
        symbol: String,
        initialRatio: Float,
        onValueChanged: (Float) -> Unit
    ): View {
        val pillHeight = dp(context, 165)
        val container = FrameLayout(context).apply {
            background = roundedDrawable("#A6252730", dp(context, 24).toFloat())
            clipToOutline = true
        }

        val fillView = View(context).apply {
            background = roundedDrawable("#E6FFFFFF", dp(context, 20).toFloat())
        }

        val fillParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (pillHeight * initialRatio.coerceIn(0.08f, 1f)).toInt(),
            Gravity.BOTTOM
        )
        container.addView(fillView, fillParams)

        val iconView = TextView(context).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, dp(context, 14))
        }
        container.addView(iconView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val h = v.height.coerceAtLeast(1)
                    val ratio = (1f - (event.y / h)).coerceIn(0.05f, 1f)
                    fillView.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (h * ratio).toInt(),
                        Gravity.BOTTOM
                    )
                    onValueChanged(ratio)
                    true
                }
                else -> false
            }
        }
        return container
    }

    private fun buildBottomActionRow(context: Context, wm: WindowManager): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        // 1. Flashlight Button
        val torchBtn = createActionSquircle(context, "🔦", isTorchOn) { btn ->
            toggleFlashlight(context)
            btn.background = roundedDrawable(
                if (isTorchOn) "#007AFF" else "#A6252730",
                dp(context, 20).toFloat()
            )
        }

        // 2. Calculator Shortcut
        val calcBtn = createActionSquircle(context, "🧮", false) {
            hideControlCenter(wm)
            val intent = Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_CALCULATOR
            )
            launchIntent(context, intent)
        }

        // 3. Camera Shortcut
        val camBtn = createActionSquircle(context, "📷", false) {
            hideControlCenter(wm)
            launchIntent(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }

        // 4. Display Settings Shortcut
        val settingsBtn = createActionSquircle(context, "⚙️", false) {
            hideControlCenter(wm)
            launchIntent(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }

        val buttons = listOf(torchBtn, calcBtn, camBtn, settingsBtn)
        buttons.forEachIndexed { index, view ->
            row.addView(view, LinearLayout.LayoutParams(0, dp(context, 68), 1f).apply {
                if (index > 0) marginStart = dp(context, 12)
            })
        }
        return row
    }

    private fun createActionSquircle(
        context: Context,
        emoji: String,
        active: Boolean,
        onClick: (TextView) -> Unit
    ): TextView {
        return TextView(context).apply {
            text = emoji
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = roundedDrawable(
                if (active) "#007AFF" else "#A6252730",
                dp(context, 20).toFloat()
            )
            setOnClickListener { onClick(this) }
        }
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = torchCameraId ?: cm.cameraIdList.firstOrNull()?.also { torchCameraId = it } ?: return
            isTorchOn = !isTorchOn
            cm.setTorchMode(id, isTorchOn)
        } catch (e: Throwable) {
            XposedBridge.log("iOSControlCenter: Torch error -> ${e.message}")
        }
    }

    private fun sendMediaKey(audioManager: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }

    private fun mediaButton(context: Context, symbol: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = symbol
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setOnClickListener { onClick() }
        }
    }

    private fun launchIntent(context: Context, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }

    private fun roundedDrawable(hexColor: String, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor(hexColor))
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
