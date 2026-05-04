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
        val channel = NotificationChannel(
            "floating_service",
            "悬浮窗服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "桌面宠物记账悬浮窗"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
