package com.example.ledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.ledger.data.db.AppDatabase
import com.example.ledger.data.model.Bill
import com.example.ledger.data.model.Category
import com.example.ledger.data.repository.LedgerRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class PeriodType { YEAR, MONTH, DAY }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = LedgerRepository(db.billDao(), db.categoryDao())

    val expenseCategories: LiveData<List<Category>> = repository.getCategoriesByType("expense")
    val incomeCategories: LiveData<List<Category>> = repository.getCategoriesByType("income")
    val usedExpenseCategories: LiveData<List<Category>> = repository.getUsedCategoriesByType("expense")
    val usedIncomeCategories: LiveData<List<Category>> = repository.getUsedCategoriesByType("income")
    val recentBills: LiveData<List<Bill>> = repository.getAllBills()

    private val _periodType = MutableLiveData(PeriodType.MONTH)
    val periodType: LiveData<PeriodType> = _periodType

    private val _periodKey = MutableLiveData(
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
    )
    val periodKey: LiveData<String> = _periodKey

    private val _periodLabel = MutableLiveData<String>()
    val periodLabel: LiveData<String> = _periodLabel

    private val _monthlyStats = MutableLiveData<LedgerRepository.MonthlyStats>()
    val monthlyStats: LiveData<LedgerRepository.MonthlyStats> = _monthlyStats

    private val _lastCategoryId = MutableLiveData<String?>(null)
    val lastCategoryId: LiveData<String?> = _lastCategoryId

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    init {
        updatePeriodLabel()
        refreshStats()
        loadLastCategory()
    }

    fun setPeriodType(type: PeriodType) {
        if (_periodType.value == type) return
        _periodType.value = type
        val now = LocalDate.now()
        _periodKey.value = formatKey(now, type)
        updatePeriodLabel()
        refreshStats()
    }

    fun navigatePrev() {
        val key = _periodKey.value ?: return
        val type = _periodType.value ?: return
        val date = parseKey(key, type)
        val prev = when (type) {
            PeriodType.YEAR -> date.minusYears(1)
            PeriodType.MONTH -> date.minusMonths(1)
            PeriodType.DAY -> date.minusDays(1)
        }
        _periodKey.value = formatKey(prev, type)
        updatePeriodLabel()
        refreshStats()
    }

    fun navigateNext() {
        val key = _periodKey.value ?: return
        val type = _periodType.value ?: return
        val date = parseKey(key, type)
        val next = when (type) {
            PeriodType.YEAR -> date.plusYears(1)
            PeriodType.MONTH -> date.plusMonths(1)
            PeriodType.DAY -> date.plusDays(1)
        }
        _periodKey.value = formatKey(next, type)
        updatePeriodLabel()
        refreshStats()
    }

    fun setPeriodDate(year: Int, month: Int, day: Int) {
        val type = _periodType.value ?: return
        val safeDay = if (type == PeriodType.DAY) day else 1
        val date = try {
            LocalDate.of(year, month, safeDay)
        } catch (_: Exception) {
            LocalDate.of(year, month, 1)
        }
        val newKey = formatKey(date, type)
        if (newKey == _periodKey.value) return
        _periodKey.value = newKey
        updatePeriodLabel()
        refreshStats()
    }

    fun resetToCurrentPeriod() {
        val type = _periodType.value ?: PeriodType.MONTH
        val now = LocalDate.now()
        _periodKey.value = formatKey(now, type)
        updatePeriodLabel()
        refreshStats()
    }

    @Deprecated("Use setPeriodType/navigatePrev/navigateNext instead", ReplaceWith("setPeriodType"))
    fun changeMonth(month: String) {
        _periodType.value = PeriodType.MONTH
        _periodKey.value = month
        updatePeriodLabel()
        refreshStats()
    }

    fun refreshMonthlyStats() = refreshStats()

    fun setCustomPeriod(type: PeriodType, key: String) {
        _periodType.value = type
        _periodKey.value = key
        updatePeriodLabel()
        refreshStats()
    }

    fun getCategoryBreakdown(type: String, callback: (List<Triple<String, String, Double>>) -> Unit) {
        viewModelScope.launch {
            val key = _periodKey.value ?: return@launch
            val totals = db.billDao().getCategoryTotals(key, type)
            val categories = db.categoryDao().getCategoriesByTypeSync(type).associateBy { it.id }
            val result = totals.map { t ->
                val cat = categories[t.categoryId]
                Triple(cat?.name ?: "未知", cat?.icon ?: "📌", t.total)
            }
            callback(result)
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            val key = _periodKey.value ?: return@launch
            _monthlyStats.value = repository.getMonthlyStats(key)
        }
    }

    fun addBill(amount: Double, categoryId: String, type: String, note: String = "", customDate: String? = null) {
        viewModelScope.launch {
            val bill = Bill(
                type = type,
                amount = amount,
                categoryId = categoryId,
                date = customDate ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                note = note
            )
            repository.addBill(bill)
            _lastCategoryId.value = categoryId
            refreshStats()
            _toastMessage.value = "记账成功"
        }
    }

    fun deleteBill(bill: Bill) {
        viewModelScope.launch {
            repository.deleteBill(bill)
            refreshStats()
            _toastMessage.value = "已删除"
        }
    }

    fun repeatLastBill() {
        viewModelScope.launch {
            val lastBill = repository.getLastBill()
            if (lastBill != null) {
                val newBill = Bill(
                    type = lastBill.type,
                    amount = lastBill.amount,
                    categoryId = lastBill.categoryId,
                    date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    note = lastBill.note
                )
                repository.addBill(newBill)
                refreshStats()
                _toastMessage.value = "已重复上次记录"
            } else {
                _toastMessage.value = "暂无记录可重复"
            }
        }
    }

    fun addCategory(name: String, type: String) {
        viewModelScope.launch {
            val category = Category(name = name, type = type)
            repository.addCategory(category)
        }
    }

    fun addCategory(name: String, type: String, icon: String, id: String) {
        viewModelScope.launch {
            val category = Category(id = id, name = name, type = type, icon = icon)
            repository.addCategory(category)
        }
    }

    fun deleteCategoryById(id: String) {
        viewModelScope.launch {
            val cat = db.categoryDao().getCategoryById(id)
            if (cat != null) {
                db.categoryDao().delete(cat)
            }
        }
    }

    fun updateBill(bill: Bill, newAmount: Double, newDate: String, newCategoryId: String) {
        viewModelScope.launch {
            val updated = bill.copy(amount = newAmount, date = newDate, categoryId = newCategoryId)
            repository.updateBill(updated)
            refreshStats()
            _toastMessage.value = "账单已更新"
        }
    }

    private fun loadLastCategory() {
        viewModelScope.launch {
            val lastBill = repository.getLastBill()
            _lastCategoryId.value = lastBill?.categoryId
        }
    }

    // ---- helpers ----

    private fun formatKey(date: LocalDate, type: PeriodType): String = when (type) {
        PeriodType.YEAR -> date.format(DateTimeFormatter.ofPattern("yyyy"))
        PeriodType.MONTH -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        PeriodType.DAY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun parseKey(key: String, type: PeriodType): LocalDate = when (type) {
        PeriodType.YEAR -> LocalDate.parse("$key-01-01", DateTimeFormatter.ISO_LOCAL_DATE)
        PeriodType.MONTH -> LocalDate.parse("$key-01", DateTimeFormatter.ISO_LOCAL_DATE)
        PeriodType.DAY -> LocalDate.parse(key, DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun updatePeriodLabel() {
        val key = _periodKey.value ?: return
        val type = _periodType.value ?: return
        _periodLabel.value = when (type) {
            PeriodType.YEAR -> "${key}年"
            PeriodType.MONTH -> {
                val d = LocalDate.parse("$key-01", DateTimeFormatter.ISO_LOCAL_DATE)
                "${d.year}年${d.monthValue}月"
            }
            PeriodType.DAY -> {
                val d = LocalDate.parse(key, DateTimeFormatter.ISO_LOCAL_DATE)
                "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
            }
        }
    }
}
