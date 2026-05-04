package com.example.ledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.ledger.data.dao.BillDao
import com.example.ledger.data.dao.CategoryDao
import com.example.ledger.data.model.Bill
import com.example.ledger.data.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Bill::class, Category::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledger.db"
                )
                    .addCallback(DatabaseCallback())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    prefillCategories(database)
                }
            }
        }
    }
}

suspend fun prefillCategories(database: AppDatabase) {
    val defaultExpenseCategories = listOf(
        Category(name = "餐饮", type = "expense", icon = "🍽️"),
        Category(name = "交通", type = "expense", icon = "🚗"),
        Category(name = "购物", type = "expense", icon = "🛒"),
        Category(name = "娱乐", type = "expense", icon = "🎮"),
        Category(name = "房租", type = "expense", icon = "🏠")
    )
    val defaultIncomeCategories = listOf(
        Category(name = "工资", type = "income", icon = "💼"),
        Category(name = "奖金", type = "income", icon = "🎁"),
        Category(name = "兼职", type = "income", icon = "💻"),
        Category(name = "退款", type = "income", icon = "↩️")
    )

    (defaultExpenseCategories + defaultIncomeCategories).forEach {
        database.categoryDao().insert(it)
    }
}
