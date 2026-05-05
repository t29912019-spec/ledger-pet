package com.example.ledger.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 悬浮桌面宠物服务 — 拖拽释放后自动吸附最近边缘（左/右/下）。
 *
 * 九宫格精灵图姿势映射：
 *   EATING(0)   → 收入后 3 秒
 *   SLEEPING(1) → 底部长时间待机
 *   TOILET(2)   → 支出后 3 秒
 *   SITTING(3)  → 底部被点击 / 默认
 *   PEEKING(4)  → 侧边被点击
 *   HIDING(5)   → 侧边待机（右侧图，左侧镜像翻转）
 *   WALKING(6)  → 坐下→睡觉过渡动画
 *   HANGING(7)  → 底部→侧边过渡动画
 *   WAVING(8)   → 隐藏/显示
 */
class FloatingWindowService : Service() {

    // ---- 核心组件 ----
    private lateinit var windowManager: WindowManager
    lateinit var petView: PetView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    // ---- 屏幕信息 ----
    private var screenWidth = 0
    private var screenHeight = 0
    private var petWidth = 0
    private var petHeight = 0

    // ---- 区域定义 ----
    private enum class Zone { BOTTOM, LEFT, RIGHT }

    // ---- 状态机 ----
    private enum class PetState {
        DRAGGING,       // 用户拖拽中
        SETTLED,        // 静止在边缘
        TEMP_POSE,      // 临时表情 (吃饭/上厕所)
        TRANSITION      // 过渡动画 (行走/挂墙)
    }

    private var petState = PetState.SETTLED
    private var currentZone = Zone.BOTTOM

    // ---- 临时表情 ----
    private var tempPose: PetView.Pose? = null

    // ---- 区域过渡 ----
    private var prevZone = Zone.BOTTOM

    // ---- 待机计时 ----
    private var settledTime = 0L
    private val idleCompactDelay = 3000L   // 无操作 3s 后进入 compact 待机
    private val bottomSleepDelay = 6000L    // 底部静止 6s 后入睡过渡
    private val walkingAnimDuration = 800L  // 行走动画 0.8s
    private val hangingAnimDuration = 600L  // 挂墙动画 0.6s

    // ---- 触控追踪 ----
    private var touchStartTime = 0L
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // ---- Runnable ----
    private var tempPoseRunnable: Runnable? = null
    private var idleRefreshRunnable: Runnable? = null

    // ========================================================================
    // Companion — 公开 API
    // ========================================================================

    companion object {
        var isRunning = false
        var opacity = 0.8f

        fun applyOpacity(value: Float) {
            opacity = value
            instance?.updateOpacity(value)
        }

        private var instance: FloatingWindowService? = null
        private const val PREFS_NAME = "pet_prefs"
        private const val KEY_ICON = "pet_icon"
        private const val KEY_CUSTOM_SPRITE = "custom_sprite_uri"
        const val DEFAULT_ICON = "bg_pet"

        private val iconColorMap = mapOf(
            "bg_pet" to Pair(0xFFFF9800.toInt(), 0xFFE65100.toInt()),
            "bg_pet_blue" to Pair(0xFF2196F3.toInt(), 0xFF1565C0.toInt()),
            "bg_pet_green" to Pair(0xFF4CAF50.toInt(), 0xFF2E7D32.toInt()),
            "bg_pet_pink" to Pair(0xFFE91E63.toInt(), 0xFFAD1457.toInt()),
            "bg_pet_purple" to Pair(0xFF9C27B0.toInt(), 0xFF6A1B9A.toInt()),
            "bg_pet_red" to Pair(0xFFF44336.toInt(), 0xFFC62828.toInt()),
        )

        fun setTempPose(pose: PetView.Pose) {
            instance?.apply {
                tempPose = pose
                petState = PetState.TEMP_POSE
                petView.currentPose = pose
                petView.isCompact = false   // 立即唤醒 compact 状态
                settledTime = System.currentTimeMillis()
                tempPoseRunnable?.let { handler.removeCallbacks(it) }
                tempPoseRunnable = Runnable {
                    tempPose = null
                    petState = PetState.SETTLED
                    settledTime = System.currentTimeMillis()
                    refreshPose()
                }
                handler.postDelayed(tempPoseRunnable!!, 3000L)
            }
        }

        fun saveIconPref(context: Context, iconName: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ICON, iconName)
                .remove(KEY_CUSTOM_SPRITE)
                .apply()
            instance?.applyIconAndSprite(context)
        }

        fun showPet() {
            instance?.apply {
                petView.visibility = View.VISIBLE
                petView.currentPose = PetView.Pose.WAVING
                handler.postDelayed({
                    if (petState == PetState.SETTLED) refreshPose()
                }, 600L)
                snapToNearestEdge()
                settlePet()
            }
        }

        fun saveCustomSpriteUri(context: Context, uri: Uri) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CUSTOM_SPRITE, uri.toString()).apply()
            instance?.applyIconAndSprite(context)
        }

        fun getPetColorPair(context: Context): Pair<Int, Int> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_ICON, DEFAULT_ICON) ?: DEFAULT_ICON
            return iconColorMap[name] ?: iconColorMap[DEFAULT_ICON]!!
        }

        @SuppressLint("DiscouragedApi")
        fun getSavedIconRes(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_ICON, DEFAULT_ICON) ?: DEFAULT_ICON
            return context.resources.getIdentifier(name, "drawable", context.packageName)
        }
    }

    // ========================================================================
    // Service 生命周期
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val density: Float
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager.currentWindowMetrics
            screenWidth = wm.bounds.width()
            screenHeight = wm.bounds.height()
            density = resources.displayMetrics.density
        } else {
            @Suppress("DEPRECATION")
            fun getMetricsCompat(): DisplayMetrics {
                val dm = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(dm)
                return dm
            }
            val metrics = getMetricsCompat()
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            density = metrics.density
        }
        petWidth = (50 * density).toInt()
        petHeight = (50 * density).toInt()

        val (fillColor, strokeColor) = getPetColorPair(this)
        petView = PetView(this).apply {
            petColor = fillColor
            this.strokeColor = strokeColor
            alpha = opacity
        }

        layoutParams = WindowManager.LayoutParams(
            petWidth, petHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - petWidth
            y = (screenHeight - petHeight) / 2
        }

        applyIconAndSprite(this)
        petView.setOnTouchListener { _, event -> handleTouch(event) }

        startForeground()
        windowManager.addView(petView, layoutParams)
        isRunning = true

        // 初始吸附右侧
        snapToNearestEdge()
        petView.edge = PetView.Edge.RIGHT
        settlePet()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        windowManager.removeView(petView)
        isRunning = false
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateOpacity(value: Float) {
        opacity = value
        petView.alpha = value
    }

    // ========================================================================
    // 精灵图加载
    // ========================================================================

    private fun applyIconAndSprite(context: Context) {
        // 仅使用 Canvas 回退（圆形色块），不加载任何精灵图
        petView.spriteSheet = null
        val (fc, sc) = getPetColorPair(context)
        petView.petColor = fc; petView.strokeColor = sc
        petView.invalidate()
    }

    // ========================================================================
    // 触控处理
    // ========================================================================

    private fun handleTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                petState = PetState.DRAGGING
                initialX = layoutParams.x; initialY = layoutParams.y
                initialTouchX = event.rawX; initialTouchY = event.rawY
                touchStartTime = System.currentTimeMillis()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                // 实际拖拽时才唤醒，防止 ACTION_DOWN 提前清掉 isCompact 导致误触
                if (petView.isCompact) {
                    petView.isCompact = false
                    settledTime = System.currentTimeMillis()
                }
                layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(petView, layoutParams)
                updateDragPose()
                true
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - touchStartTime
                val dist = sqrt(
                    ((layoutParams.x - initialX) * (layoutParams.x - initialX) +
                     (layoutParams.y - initialY) * (layoutParams.y - initialY)).toFloat()
                )

                when {
                    duration > 500 && dist < 30f -> handleLongPress()
                    duration < 300 && dist < 20f -> handleTap()
                    else -> {
                        snapToNearestEdge()
                        settlePet()
                    }
                }
                true
            }

            else -> false
        }
    }

    private fun handleTap() {
        // 待机状态：第一次点击仅唤醒，不触发任何操作
        if (petView.isCompact) {
            petView.isCompact = false
            settledTime = System.currentTimeMillis()
            when (detectZone()) {
                Zone.BOTTOM -> petView.currentPose = PetView.Pose.SITTING
                Zone.LEFT   -> { petView.mirror = true;  petView.currentPose = PetView.Pose.PEEKING }
                Zone.RIGHT  -> { petView.mirror = false; petView.currentPose = PetView.Pose.PEEKING }
            }
            snapToNearestEdge()
            settlePet()
            return
        }

        openInputDialog()
        snapToNearestEdge()
        settlePet()
    }

    private fun handleLongPress() {
        val zone = detectZone()
        if (zone == Zone.LEFT || zone == Zone.RIGHT) {
            petView.currentPose = PetView.Pose.WAVING
            petView.mirror = zone == Zone.LEFT
            handler.postDelayed({ stopSelf() }, 600L)
        } else {
            openMainApp()
            snapToNearestEdge()
            settlePet()
        }
    }

    // ========================================================================
    // 吸边 — 拖拽释放后吸附最近边缘
    // ========================================================================

    private fun snapToNearestEdge() {
        val petCenterX = layoutParams.x + petWidth / 2
        val petCenterY = layoutParams.y + petHeight / 2
        val distLeft = petCenterX
        val distRight = screenWidth - petCenterX
        val distBottom = screenHeight - petCenterY

        val minDist = minOf(distLeft, distRight, distBottom)

        when (minDist) {
            distLeft   -> layoutParams.x = 0
            distRight  -> layoutParams.x = screenWidth - petWidth
            distBottom -> layoutParams.y = screenHeight - petHeight
        }

        windowManager.updateViewLayout(petView, layoutParams)
    }

    // ========================================================================
    // 静止处理
    // ========================================================================

    private fun settlePet() {
        petState = PetState.SETTLED
        settledTime = System.currentTimeMillis()
        petView.isCompact = false
        val newZone = detectZone()

        // 底部→侧边过渡 → 播放 HANGING
        if (prevZone == Zone.BOTTOM && (newZone == Zone.LEFT || newZone == Zone.RIGHT)) {
            petState = PetState.TRANSITION
            petView.currentPose = PetView.Pose.HANGING
            petView.mirror = newZone == Zone.LEFT
            handler.postDelayed({
                if (petState == PetState.TRANSITION) {
                    petState = PetState.SETTLED
                    currentZone = newZone
                    refreshPose()
                    startIdleTimer()
                }
            }, hangingAnimDuration)
            currentZone = newZone; prevZone = newZone
            petView.edge = zoneToEdge(newZone)
            return
        }

        currentZone = newZone; prevZone = newZone
        petView.edge = zoneToEdge(newZone)
        refreshPose()
        startIdleTimer()
    }

    private fun zoneToEdge(z: Zone): PetView.Edge = when (z) {
        Zone.LEFT -> PetView.Edge.LEFT
        Zone.RIGHT -> PetView.Edge.RIGHT
        Zone.BOTTOM -> PetView.Edge.BOTTOM
    }

    // ========================================================================
    // 区域检测 (只返回 BOTTOM / LEFT / RIGHT，吸附后不可能是其他)
    // ========================================================================

    private fun detectZone(): Zone {
        val x = layoutParams.x
        return when {
            x <= 0 -> Zone.LEFT
            x + petWidth >= screenWidth -> Zone.RIGHT
            else -> Zone.BOTTOM  // 吸附后必定贴边
        }
    }

    private fun detectZoneAt(rawX: Int, rawY: Int): Zone {
        val distLeft = rawX
        val distRight = screenWidth - rawX
        val distBottom = screenHeight - rawY
        val minDist = minOf(distLeft, distRight, distBottom)
        return when (minDist) {
            distLeft   -> Zone.LEFT
            distRight  -> Zone.RIGHT
            else       -> Zone.BOTTOM
        }
    }

    // ========================================================================
    // 姿势映射
    // ========================================================================

    private fun updateDragPose() {
        val zone = detectZone()
        petView.edge = zoneToEdge(zone)
        when (zone) {
            Zone.BOTTOM       -> { petView.mirror = false; petView.currentPose = PetView.Pose.SITTING }
            Zone.LEFT         -> { petView.mirror = true;  petView.currentPose = PetView.Pose.PEEKING }
            Zone.RIGHT        -> { petView.mirror = false; petView.currentPose = PetView.Pose.PEEKING }
        }
    }

    private fun refreshPose() {
        if (petState == PetState.TRANSITION) return
        if (petState == PetState.TEMP_POSE && tempPose != null) {
            petView.currentPose = tempPose!!
            petView.mirror = false
            return
        }
        if (petState == PetState.DRAGGING) { updateDragPose(); return }

        when (detectZone()) {
            Zone.BOTTOM -> {
                petView.mirror = false
                val elapsed = System.currentTimeMillis() - settledTime
                petView.currentPose = when {
                    elapsed > bottomSleepDelay + walkingAnimDuration -> PetView.Pose.SLEEPING
                    elapsed > bottomSleepDelay -> PetView.Pose.WALKING
                    else -> PetView.Pose.SITTING
                }
            }
            Zone.LEFT  -> {
                petView.mirror = true
                petView.currentPose = if (petView.isCompact) PetView.Pose.HIDING else PetView.Pose.PEEKING
            }
            Zone.RIGHT -> {
                petView.mirror = false
                petView.currentPose = if (petView.isCompact) PetView.Pose.HIDING else PetView.Pose.PEEKING
            }
        }
    }

    // ========================================================================
    // 待机计时器
    // ========================================================================

    private fun startIdleTimer() {
        idleRefreshRunnable?.let { handler.removeCallbacks(it) }
        idleRefreshRunnable = object : Runnable {
            override fun run() {
                if (petState != PetState.SETTLED) return
                val elapsed = System.currentTimeMillis() - settledTime

                // 3s 后进入 compact 待机
                if (elapsed > idleCompactDelay && !petView.isCompact) {
                    petView.isCompact = true
                    refreshPose()
                }

                // 底部入睡过渡
                if (detectZone() == Zone.BOTTOM) {
                    when {
                        elapsed > bottomSleepDelay + walkingAnimDuration -> {
                            petView.currentPose = PetView.Pose.SLEEPING
                            petView.mirror = false; return
                        }
                        elapsed > bottomSleepDelay -> {
                            petView.currentPose = PetView.Pose.WALKING
                            petView.mirror = false
                        }
                    }
                }
                handler.postDelayed(this, 500L)
            }
        }
        handler.postDelayed(idleRefreshRunnable!!, 500L)
    }

    // ========================================================================
    // 动作触发
    // ========================================================================

    private fun openInputDialog() {
        startActivity(Intent(this, InputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openMainApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ========================================================================
    // 前台服务通知
    // ========================================================================

    private fun startForeground() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, "floating_service")
            .setContentTitle("随记账本")
            .setContentText("点击宠物即可记账")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }
}
