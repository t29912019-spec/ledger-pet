package com.example.ledger.data.repository

import androidx.lifecycle.LiveData
import com.example.ledger.data.dao.BillDao
import com.example.ledger.data.dao.CategoryDao
import com.example.ledger.data.model.Bill
import com.example.ledger.data.model.Category
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class LedgerRepository(
    private val billDao: BillDao,
    private val categoryDao: CategoryDao
) {
    // Bill
    fun getAllBills(): LiveData<List<Bill>> = billDao.getAllBills()

    fun getBillsByMonth(yearMonth: String): LiveData<List<Bill>> = billDao.getBillsByMonth(yearMonth)

    suspend fun addBill(bill: Bill) = billDao.insert(bill)

    suspend fun updateBill(bill: Bill) = billDao.update(bill)

    suspend fun getBillsByCategory(categoryId: String): List<Bill> =
        billDao.getBillsByCategory(categoryId)

    suspend fun getBillsByCategoryAndMonth(categoryId: String, month: String): List<Bill> =
        billDao.getBillsByCategoryAndMonth(categoryId, month)

    suspend fun deleteBill(bill: Bill) {
        // 删除关联的退款记录
        billDao.getAllBillsSync().filter {
            it.relatedBillId == bill.id && it.isRefund
        }.forEach {
            billDao.delete(it)
        }
        billDao.delete(bill)
    }

    suspend fun addRefund(originalBill: Bill, amount: Double) {
        val refundBill = Bill(
            type = "income",
            amount = amount,
            categoryId = "", // 退款分类由系统处理
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            note = "退款: ${originalBill.note}",
            isRefund = false,
            relatedBillId = originalBill.id
        )
        billDao.insert(refundBill)
    }

    suspend fun getLastBill(): Bill? = billDao.getLastBill()

    // Category
    fun getCategoriesByType(type: String): LiveData<List<Category>> =
        categoryDao.getCategoriesByType(type)

    fun getAllCategories(): LiveData<List<Category>> = categoryDao.getAllCategories()

    suspend fun getAllCategoriesSync(): List<Category> = categoryDao.getAllCategoriesSync()

    fun getUsedCategoriesByType(type: String): LiveData<List<Category>> =
        categoryDao.getUsedCategoriesByType(type)

    suspend fun getUsedCategoriesByTypeSync(type: String): List<Category> =
        categoryDao.getUsedCategoriesByTypeSync(type)

    suspend fun addCategory(category: Category) = categoryDao.insert(category)

    suspend fun deleteCategory(category: Category) = categoryDao.delete(category)

    // Statistics
    suspend fun getMonthlyStats(yearMonth: String): MonthlyStats {
        val totalExpense = billDao.getTotalExpense(yearMonth) ?: 0.0
        val totalIncome = billDao.getTotalIncome(yearMonth) ?: 0.0
        val totalRefund = billDao.getTotalRefund(yearMonth) ?: 0.0
        val netExpense = totalExpense - totalRefund
        val netWorth = totalIncome - netExpense

        return MonthlyStats(
            totalExpense = netExpense,
            totalIncome = totalIncome,
            netWorth = netWorth
        )
    }

    data class MonthlyStats(
        val totalExpense: Double,
        val totalIncome: Double,
        val netWorth: Double
    ) {
        val isSurplus: Boolean get() = netWorth >= 0
    }
}
