package com.example.ledger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ledger.data.db.AppDatabase
import com.example.ledger.ui.FloatingWindowService
import com.example.ledger.ui.InputActivity
import com.example.ledger.ui.PetView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 支付通知拦截服务 — 监听支付/退款通知，自动记账。
 *
 * 支付通知 → 弹出 InputActivity 预填
 * 退款通知 → 自动匹配最近一笔等额支出并删除抵消
 */
class PaymentNotificationService : NotificationListenerService() {

    companion object {
        var isRunning = false
            private set

        private const val TAG = "PaymentNotify"
        const val PAYMENT_CHANNEL_ID = "payment_book"
        private const val PAYMENT_NOTIFY_ID = 1001

        private val SHOPPING_APPS = mapOf(
            "com.taobao.taobao" to "淘宝",
            "com.tmall.wireless" to "天猫",
            "com.jingdong.app.mall" to "京东",
            "com.xunmeng.pinduoduo" to "拼多多",
            "com.sankuai.meituan" to "美团",
            "com.dianping.v1" to "大众点评",
            "me.ele" to "饿了么",
            "com.sankuai.meituan.takeoutnew" to "美团外卖",
            "ctrip.android.view" to "携程",
            "com.qunar" to "去哪儿",
            "com.taobao.idlefish" to "闲鱼",
            "com.shizhuang.duapp" to "得物",
            "com.xingin.xhs" to "小红书",
        )

        private val REFUND_KEYWORDS = listOf(
            "退款", "已退款", "退款成功", "退款到账", "退款进度",
            "原路退回", "退款已", "退票", "退货退款",
        )
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---- 生命周期 ----

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
    }

    // ---- 通知回调 ----

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val pkg = sbn.packageName
        val title = extras.getString("android.title") ?: ""

        // 收集通知中所有文本
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add(title)
        fun collect(key: String) {
            extras.getString(key)?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        collect("android.text")
        collect("android.subText")
        collect("android.summaryText")
        collect("android.bigText")
        collect("android.tickerText")
        extras.getCharSequenceArray("android.textLines")?.forEach { line ->
            line.toString().trim().takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        val fullText = parts.joinToString(" ")

        when {
            pkg == "com.tencent.mm" && title == "微信支付" -> {
                parseWechat(fullText)?.let { onPaymentDetected(it) }
            }
            pkg == "com.eg.android.AlipayGphone" && title == "交易提醒" -> {
                parseAlipay(fullText)?.let { onPaymentDetected(it) }
            }
            pkg in SHOPPING_APPS -> {
                parseRefund(fullText, SHOPPING_APPS[pkg]!!)?.let { onPaymentDetected(it) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    // ---- 解析引擎 ----

    private fun parseWechat(text: String): PaymentInfo? {
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null

        val isRefund = text.contains("退款")
        val isIncome = isRefund || text.contains("收款") || text.contains("入账") ||
                text.contains("到账") || text.contains("转入")
        val isExpense = text.contains("付款") || text.contains("支出") ||
                text.contains("消费") || text.contains("扣款")

        val type = when {
            isIncome -> "income"
            isExpense -> "expense"
            text.contains("支付") -> "expense"
            else -> "expense"
        }

        return PaymentInfo(amount, type, "微信", isRefund)
    }

    private fun parseAlipay(text: String): PaymentInfo? {
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null

        val isRefund = text.contains("退款")
        val isIncome = isRefund || text.contains("收到") || text.contains("转账") ||
                text.contains("收款") || text.contains("入账") || text.contains("转入") ||
                text.contains("到账")
        val isExpense = text.contains("付款") || text.contains("支出") ||
                text.contains("扣除") || text.contains("消费") || text.contains("扣款")

        val type = when {
            isIncome -> "income"
            isExpense -> "expense"
            text.contains("支付") -> "expense"
            else -> "expense"
        }

        return PaymentInfo(amount, type, "支付宝", isRefund)
    }

    private fun parseRefund(text: String, appName: String): PaymentInfo? {
        if (REFUND_KEYWORDS.none { text.contains(it) }) return null
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null

        return PaymentInfo(amount, "income", appName, isRefund = true)
    }

    // ---- 金额提取 ----

    private fun extractAmount(text: String): Double? {
        // 策略1: ¥ 或 ￥
        val symIdx = text.indexOfFirst { it == '¥' || it == '￥' }
        if (symIdx >= 0) {
            val numStr = text.substring(symIdx + 1).trim()
                .takeWhile { it.isDigit() || it == '.' || it == ',' }
                .replace(",", "")
            numStr.toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 策略2: 数字+元
        for (match in Regex("""(\d+\.?\d*)\s*元""").findAll(text)) {
            match.groupValues[1].toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 策略3: 纯数字 xx.xx
        return Regex("""\b(\d+\.\d{2})\b""").findAll(text)
            .map { it.groupValues[1].toDoubleOrNull() }
            .filterNotNull()
            .filter { it in 0.01..99999.0 }
            .maxOrNull()
    }

    // ---- 事件分发 ----

    private fun onPaymentDetected(info: PaymentInfo) {
        if (info.isRefund) {
            handleRefund(info)
        } else {
            openInputActivity(info)
            triggerPetPose(info)
            showPaymentNotification(info)
        }
    }

    // ---- 退款自动抵消 ----

    private fun handleRefund(info: PaymentInfo) {
        ioScope.launch {
            try {
                val dao = AppDatabase.getInstance(this@PaymentNotificationService).billDao()
                val allBills = dao.getAllBillsSync()
                val expenses = allBills.filter { it.type == "expense" && !it.isRefund }
                var m = dao.findMatchingExpense(info.amount)
                if (m == null) {
                    m = expenses
                        .filter { kotlin.math.abs(it.amount - info.amount) < 0.10 }
                        .maxByOrNull { it.createTime }
                }
                if (m != null) dao.delete(m)

                mainHandler.post {
                    if (m != null) {
                        showRefundResultNotification(info.amount, m.note, success = true)
                    } else {
                        openInputActivity(info)
                        showRefundResultNotification(info.amount, "", success = false)
                    }
                    triggerPetPose(info)
                }
            } catch (e: Exception) {
                Log.e(TAG, "退款处理异常", e)
                mainHandler.post {
                    openInputActivity(info)
                    triggerPetPose(info)
                }
            }
        }
    }

    private fun showRefundResultNotification(amount: Double, matchedNote: String, success: Boolean) {
        val title = if (success) "已自动抵消 ¥$amount" else "未找到等额支出记录"
        val text = if (success) {
            if (matchedNote.isNotBlank()) "原记录: $matchedNote" else "已删除匹配的支出记录"
        } else {
            "已打开记账窗口，请手动处理"
        }

        val pi = if (!success) {
            val intent = Intent(this, InputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("amount", amount)
                putExtra("type", "income")
            }
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        try {
            val builder = NotificationCompat.Builder(this, PAYMENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            if (pi != null) builder.setContentIntent(pi)

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(PAYMENT_NOTIFY_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "发送退款结果通知失败", e)
        }
    }

    // ---- 普通支付：弹出窗口 ----

    private fun openInputActivity(info: PaymentInfo) {
        try {
            val intent = Intent(this, InputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("amount", info.amount)
                putExtra("type", info.type)
                putExtra("payWay", info.payWay)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动 InputActivity 失败", e)
        }
    }

    private fun showPaymentNotification(info: PaymentInfo) {
        val intent = Intent(this, InputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("amount", info.amount)
            putExtra("type", info.type)
            putExtra("payWay", info.payWay)
        }

        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeLabel = if (info.type == "income") "收入" else "支出"

        try {
            val notification = NotificationCompat.Builder(this, PAYMENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("检测到${info.payWay}$typeLabel")
                .setContentText("¥${info.amount} — 点击记账")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(PAYMENT_NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "发送通知失败", e)
        }
    }

    private fun triggerPetPose(info: PaymentInfo) {
        if (!FloatingWindowService.isRunning) return
        val pose = if (info.type == "income") PetView.Pose.EATING else PetView.Pose.TOILET
        FloatingWindowService.setTempPose(pose)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(PAYMENT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        PAYMENT_CHANNEL_ID,
                        "自动记账提醒",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "检测到支付/退款通知时自动处理"
                    }
                )
            }
        }
    }

    // ---- 数据模型 ----

    private data class PaymentInfo(
        val amount: Double,
        val type: String,      // "expense" | "income"
        val payWay: String,    // "微信" | "支付宝" | 购物App名
        val isRefund: Boolean  // 退款 → 自动抵消；非退款 → 弹出窗口
    )
}
