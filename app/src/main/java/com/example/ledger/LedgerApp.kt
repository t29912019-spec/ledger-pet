package com.example.ledger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.ledger.data.db.AppDatabase

class LedgerApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // 悬浮窗服务通知渠道
        val floatChannel = NotificationChannel(
            "floating_service",
            "悬浮窗服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "桌面宠物记账悬浮窗"
        }
        manager.createNotificationChannel(floatChannel)

        // 自动记账支付通知渠道
        val paymentChannel = NotificationChannel(
            "payment_book",
            "自动记账提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "检测到微信/支付宝支付时推送记账提醒"
        }
        manager.createNotificationChannel(paymentChannel)
    }
}
