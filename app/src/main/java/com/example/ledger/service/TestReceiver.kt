package com.example.ledger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.ledger.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 测试用广播接收器 — 从 ADB 触发退款识别以验证端到端流程。
 *
 * adb shell am broadcast -a com.example.ledger.TEST_REFUND \
 *   -n com.example.ledger/.service.TestReceiver --es text "退款 ¥3.90"
 */
class TestReceiver : BroadcastReceiver() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val testText = intent.getStringExtra("text")
        if (testText.isNullOrBlank()) {
            // 无测试文本时，直接触发无障碍服务屏幕识别
            LedgerAccessibilityService.requestScreenCapture(context)
            return
        }

        // 有测试文本时，直接走解析+匹配流程
        ioScope.launch {
            val parsed = extractAmount(testText) ?: return@launch
            if (parsed <= 0) return@launch

            try {
                val dao = AppDatabase.getInstance(context).billDao()
                val allBills = dao.getAllBillsSync()
                val expenses = allBills.filter { it.type == "expense" && !it.isRefund }

                var match = dao.findMatchingExpense(parsed)
                if (match == null) {
                    match = expenses
                        .filter { kotlin.math.abs(it.amount - parsed) < 0.10 }
                        .maxByOrNull { it.createTime }
                }

                if (match != null) {
                    dao.delete(match)
                    Log.i("TestReceiver", "REFUND MATCH: deleted expense ¥${match.amount} (${match.note})")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "测试成功: 已抵消 ¥$parsed (原记录: ${match.note})", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.i("TestReceiver", "REFUND NO MATCH: no expense found for ¥$parsed")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "测试结果: 未找到等额支出 ¥$parsed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("TestReceiver", "REFUND ERROR", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "测试失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractAmount(text: String): Double? {
        // 1) "退款金额 ¥X.XX" or "退款金额 X.XX"
        val refundAmountIdx = text.indexOf("退款金额")
        if (refundAmountIdx >= 0) {
            val after = text.substring(refundAmountIdx)
            Regex("""¥\s*(\d+\.?\d*)""").find(after)?.groupValues?.get(1)
                ?.toDoubleOrNull()?.let { if (it > 0) return it }
            Regex("""(\d+\.\d{2})""").find(after)?.groupValues?.get(1)
                ?.toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 2) ¥ or ￥ prefix
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
        // 4) Refund-keyword proximity
        val refundKw = listOf("退款", "已退款", "退款成功", "退款到账", "退款进度",
            "原路退回", "退款已", "退票", "退货退款", "退回", "退款金额", "退款详情")
        val amounts = Regex("""\b(\d+\.\d{2})\b""").findAll(text).toList()
        if (amounts.isEmpty()) return null
        val refundWordIdx = refundKw.mapNotNull { kw ->
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
}
