package com.example.ledger.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.ledger.data.model.Bill

@Dao
interface BillDao {

    @Insert
    suspend fun insert(bill: Bill)

    @Update
    suspend fun update(bill: Bill)

    @Delete
    suspend fun delete(bill: Bill)

    @Query("SELECT * FROM bills ORDER BY createTime DESC")
    fun getAllBills(): LiveData<List<Bill>>

    @Query("SELECT * FROM bills ORDER BY createTime DESC")
    suspend fun getAllBillsSync(): List<Bill>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBillById(id: String): Bill?

    @Query("SELECT * FROM bills WHERE date LIKE :month || '%' ORDER BY createTime DESC")
    fun getBillsByMonth(month: String): LiveData<List<Bill>>

    @Query("SELECT * FROM bills WHERE date LIKE :month || '%' ORDER BY createTime DESC")
    suspend fun getBillsByMonthSync(month: String): List<Bill>

    @Query("SELECT SUM(amount) FROM bills WHERE type = 'expense' AND isRefund = 0 AND date LIKE :month || '%'")
    suspend fun getTotalExpense(month: String): Double?

    @Query("SELECT SUM(amount) FROM bills WHERE type = 'income' AND date LIKE :month || '%'")
    suspend fun getTotalIncome(month: String): Double?

    @Query("SELECT SUM(amount) FROM bills WHERE isRefund = 1 AND date LIKE :month || '%'")
    suspend fun getTotalRefund(month: String): Double?

    @Query("SELECT * FROM bills WHERE categoryId = :categoryId ORDER BY createTime DESC")
    suspend fun getBillsByCategory(categoryId: String): List<Bill>

    @Query("SELECT * FROM bills WHERE categoryId = :categoryId AND date LIKE :month || '%' ORDER BY createTime DESC")
    suspend fun getBillsByCategoryAndMonth(categoryId: String, month: String): List<Bill>

    @Query("SELECT * FROM bills ORDER BY createTime DESC LIMIT 1")
    suspend fun getLastBill(): Bill?

    @Query("SELECT DISTINCT categoryId FROM bills WHERE date LIKE :periodKey || '%'")
    suspend fun getActiveCategoryIds(periodKey: String): List<String>

    @Query("SELECT categoryId, SUM(amount) as total FROM bills WHERE date LIKE :periodKey || '%' AND type = :type AND isRefund = 0 GROUP BY categoryId")
    suspend fun getCategoryTotals(periodKey: String, type: String): List<CategoryTotal>
}

data class CategoryTotal(val categoryId: String, val total: Double)
