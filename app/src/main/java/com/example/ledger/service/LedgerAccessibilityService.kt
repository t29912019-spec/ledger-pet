package com.example.ledger.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.ledger.data.db.AppDatabase
import com.example.ledger.ui.FloatingWindowService
import com.example.ledger.ui.PetView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 屏幕识别无障碍服务 — 双击悬浮宠物后截图+OCR读取屏幕文字，识别退款并自动抵消。
 *
 * 触发流程：
 *   双击悬浮宠物 → requestScreenCapture()
 *   → AccessibilityService.takeScreenshot() 截图
 *   → ML Kit 中文 OCR 识别文字
 *   → 解析退款关键词 + 金额
 *   → 匹配数据库中等额支出并删除
 *   → 通知栏显示结果
 */
class LedgerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val TAG = "LedgerA11y"
        private const val SCREEN_CHANNEL_ID = "screen_refund"
        private const val SCREEN_NOTIFY_ID = 2001

        private val REFUND_KEYWORDS = listOf(
            "退款", "已退款", "退款成功", "退款到账", "退款进度",
            "原路退回", "退款已", "退票", "退货退款",
            "退回", "退款金额", "退款详情",
        )

        fun requestScreenCapture(context: Context) {
            val service = instance
            if (service == null) {
                showEnableGuide(context)
                return
            }
            service.captureAndProcess()
        }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return flat?.contains(context.packageName + "/" + LedgerAccessibilityService::class.java.name) == true
        }

        @JvmStatic
        var instance: LedgerAccessibilityService? = null
            private set

        private fun showEnableGuide(context: Context) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "请先在「设置 → 无障碍 → 随记账本」中开启屏幕识别权限",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocrExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var ocrInProgress = false

    // ---- OCR 识别器（懒加载，避免长时间阻塞） ----
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // ---- 生命周期 ----

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        ensureNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        try { recognizer.close() } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- 核心：截图 + OCR ----

    @Suppress("DEPRECATION")
    private fun captureAndProcess() {
        if (ocrInProgress) return
        ocrInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(0, ocrExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        if (bitmap == null) {
                            fallbackTree()
                            return
                        }

                        val image = InputImage.fromBitmap(bitmap, 0)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val fullText = visionText.text
                                Log.d(TAG, "OCR result (${fullText.length} chars): ${fullText.take(200)}")
                                bitmap.recycle()
                                result.hardwareBuffer.close()

                                if (fullText.isBlank()) {
                                    fallbackTree()
                                    return@addOnSuccessListener
                                }

                                processOcrResult(fullText)
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "OCR failed", e)
                                bitmap.recycle()
                                result.hardwareBuffer.close()
                                fallbackTree()
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot/OCR error", e)
                        try { result.hardwareBuffer.close() } catch (_: Exception) {}
                        fallbackTree()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed, errorCode=$errorCode")
                    fallbackTree()
                }
            })
        } else {
            // Android 10 (API 29) 及以下：多级回退截图
            runOnOcrThread { bitmap ->
                processBitmapWithOcr(bitmap)
            }
        }
    }

    // ---- 在 ocrExecutor 上运行截图 + OCR ----

    private fun runOnOcrThread(onBitmap: (Bitmap?) -> Unit) {
        // 1) 反射 SurfaceControl
        val reflected = tryReflectScreenshot()
        if (reflected != null) { onBitmap(reflected); return }

        // 2) 文件回退（不阻塞、不需要权限）
        val fileBitmap = readScreenshotFromFile()
        if (fileBitmap != null) { onBitmap(fileBitmap); return }

        // 3) MediaProjection — 需要切换到主线程与系统交互
        mainHandler.post {
            ScreenCaptureManager.captureOrRequest(this) { mpBitmap ->
                ocrExecutor.execute { onBitmap(mpBitmap) }
            }
        }
    }

    private fun processBitmapWithOcr(bitmap: Bitmap?) {
        if (bitmap == null) {
            fallbackTree()
            return
        }
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    Log.d(TAG, "OCR result (${fullText.length} chars): ${fullText.take(200)}")
                    bitmap.recycle()
                    if (fullText.isBlank()) {
                        fallbackTree()
                    } else {
                        processOcrResult(fullText)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    bitmap.recycle()
                    fallbackTree()
                }
        } catch (e: Exception) {
            Log.e(TAG, "OCR error", e)
            try { bitmap.recycle() } catch (_: Exception) {}
            fallbackTree()
        }
    }

    // ---- SurfaceControl 反射截图 ----

    private fun tryReflectScreenshot(): Bitmap? {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val signatures = listOf(
                { surfaceControlClass.getMethod("screenshot", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, width, height) as? Bitmap },
                { surfaceControlClass.getMethod("screenshot", Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, Rect(0, 0, width, height), width, height, 0) as? Bitmap },
            )
            var bitmap: Bitmap? = null
            for (attempt in signatures) {
                try { bitmap = attempt(); if (bitmap != null) break }
                catch (_: NoSuchMethodException) { continue }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun readScreenshotFromFile(): Bitmap? {
        val paths = listOf(
            java.io.File("/data/local/tmp/refund_screen.png"),
            java.io.File("/sdcard/Pictures/refund_screen.png"),
            java.io.File("/storage/emulated/0/Pictures/refund_screen.png"),
        )
        for (path in paths) {
            if (!path.exists()) continue
            val bitmap = try {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val stream = java.io.FileInputStream(path)
                val bmp = android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                stream.close()
                bmp
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read screenshot file ${path.name}", e)
                null
            }
            if (bitmap != null) {
                Log.d(TAG, "Read screenshot from file: ${bitmap.width}x${bitmap.height} (${path.name})")
                return bitmap
            }
        }
        Log.d(TAG, "No screenshot file found in any location")
        return null
    }

    // ---- 回退：无障碍树 ----

    @Suppress("DEPRECATION")
    private fun fallbackTree() {
        val root = rootInActiveWindow
        if (root == null) {
            showResultNotification("无法读取屏幕内容", "", success = false)
            ocrInProgress = false
            return
        }
        val text = collectTextFromNode(root, 0, 50)
        root.recycle()

        if (text.isBlank()) {
            showResultNotification("屏幕无可读文字", "请确认当前页面包含退款信息", success = false)
            ocrInProgress = false
            return
        }
        Log.d(TAG, "Tree fallback text: ${text.take(200)}")
        processOcrResult(text)
    }

    // ---- OCR 结果处理 ----

    private fun processOcrResult(fullText: String) {
        val refundInfo = parseRefundFromText(fullText)
        if (refundInfo == null) {
            showResultNotification("未识别到退款信息", "请在退款详情页面再试", success = false)
            ocrInProgress = false
            return
        }

        processRefund(refundInfo)
        triggerPetPose("income")
    }

    // ---- 无障碍树递归 ----

    @Suppress("DEPRECATION")
    private fun collectTextFromNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int
    ): String {
        if (depth > maxDepth) return ""
        val texts = mutableListOf<String>()
        val childCount = node.childCount
        if (childCount > 0) {
            for (i in 0 until childCount) {
                node.getChild(i)?.let { child ->
                    if (child.childCount > 0) {
                        texts.add(collectTextFromNode(child, depth + 1, maxDepth))
                    } else {
                        child.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { texts.add(it) }
                        child.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { texts.add(it) }
                    }
                    child.recycle()
                }
            }
        } else {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?.let { texts.add(it) }
        }
        return texts.filter { it.isNotBlank() }.joinToString(" ")
    }

    // ---- 退款解析 ----

    private fun parseRefundFromText(text: String): RefundInfo? {
        if (REFUND_KEYWORDS.none { text.contains(it) }) return null
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null
        return RefundInfo(amount)
    }

    private fun extractAmount(text: String): Double? {
        // 1) "退款金额 ¥X.XX" or "退款金额 X.XX" — most reliable signal
        val refundAmountIdx = text.indexOf("退款金额")
        if (refundAmountIdx >= 0) {
            val after = text.substring(refundAmountIdx)
            Regex("""¥\s*(\d+\.?\d*)""").find(after)?.groupValues?.get(1)
                ?.toDoubleOrNull()?.let { if (it > 0) return it }
            Regex("""(\d+\.\d{2})""").find(after)?.groupValues?.get(1)
                ?.toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 2) ¥ or ￥ prefix anywhere
        val symIdx = text.indexOfFirst { it == '¥' || it == '￥' }
        if (symIdx >= 0) {
            val numStr = text.substring(symIdx + 1).trim()
                .takeWhile { it.isDigit() || it == '.' || it == ',' }
                .replace(",", "")
            numStr.toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 3) "X元" pattern
        for (match in Regex("""(\d+\.?\d*)\s*元""").findAll(text)) {
            match.groupValues[1].toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 4) Refund-keyword proximity: amounts near 退款/退 are more likely the refund
        val amounts = Regex("""\b(\d+\.\d{2})\b""").findAll(text).toList()
        if (amounts.isEmpty()) return null
        val refundWordIdx = REFUND_KEYWORDS.mapNotNull { kw ->
            val i = text.indexOf(kw); if (i >= 0) i else null
        }.minOrNull()
        if (refundWordIdx != null) {
            amounts.minByOrNull { m ->
                kotlin.math.abs(m.range.first - refundWordIdx)
            }?.groupValues?.get(1)?.toDoubleOrNull()?.let { if (it in 0.01..99999.0) return it }
        }
        return amounts.map { it.groupValues[1].toDoubleOrNull() }
            .filterNotNull()
            .filter { it in 0.01..99999.0 }
            .maxOrNull()
    }

    // ---- 退款处理 ----

    private fun processRefund(info: RefundInfo) {
        ioScope.launch {
            try {
                val dao = AppDatabase.getInstance(this@LedgerAccessibilityService).billDao()
                val allBills = dao.getAllBillsSync()
                val expenses = allBills.filter { it.type == "expense" && !it.isRefund }

                var match = dao.findMatchingExpense(info.amount)
                if (match == null) {
                    match = expenses
                        .filter { kotlin.math.abs(it.amount - info.amount) < 0.10 }
                        .maxByOrNull { it.createTime }
                }

                if (match != null) dao.delete(match)

                mainHandler.post {
                    if (match != null) {
                        val note = match.note
                        showResultNotification(
                            "已自动抵消 ¥${info.amount}",
                            if (note.isNotBlank()) "原记录: $note" else "已删除匹配的支出记录",
                            success = true
                        )
                    } else {
                        showResultNotification(
                            "未找到等额支出 ¥${info.amount}",
                            "请手动处理退款记账",
                            success = false
                        )
                    }
                    ocrInProgress = false
                }
            } catch (e: Exception) {
                mainHandler.post {
                    showResultNotification("处理退款失败", e.message ?: "未知错误", success = false)
                    ocrInProgress = false
                }
            }
        }
    }

    // ---- 通知 ----

    private fun showResultNotification(title: String, text: String, success: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val icon = if (success) android.R.drawable.ic_menu_edit
                else android.R.drawable.ic_menu_info_details
            val builder = NotificationCompat.Builder(this, SCREEN_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            nm.notify(SCREEN_NOTIFY_ID, builder.build())
        } catch (e: Exception) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(SCREEN_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        SCREEN_CHANNEL_ID,
                        "屏幕识别结果",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "屏幕识别退款抵消结果通知"
                    }
                )
            }
        }
    }

    private fun triggerPetPose(type: String) {
        if (!FloatingWindowService.isRunning) return
        val pose = if (type == "income") PetView.Pose.EATING else PetView.Pose.TOILET
        FloatingWindowService.setTempPose(pose)
    }

    private data class RefundInfo(val amount: Double)
}
