package com.example.ledger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: String,           // "income" | "expense"
    val amount: Double,
    val categoryId: String,
    val date: String,           // yyyy-MM-dd
    val note: String = "",
    val isRefund: Boolean = false,
    val relatedBillId: String? = null,  // 关联原账单ID
    val createTime: Long = System.currentTimeMillis()
)
