package com.example.ledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.ledger.data.db.AppDatabase
import com.example.ledger.data.model.Bill
import com.example.ledger.data.dao.CategoryTotal
import com.example.ledger.data.model.Category
import com.example.ledger.data.repository.LedgerRepository
import kotlinx.coroutines.launch
import java.util.UUID

class CategoryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = LedgerRepository(db.billDao(), db.categoryDao())

    val allCategories: LiveData<List<Category>> = repository.getAllCategories()
    val expenseCategories: LiveData<List<Category>> = repository.getCategoriesByType("expense")
    val incomeCategories: LiveData<List<Category>> = repository.getCategoriesByType("income")
    val usedExpenseCategories: LiveData<List<Category>> = repository.getUsedCategoriesByType("expense")
    val usedIncomeCategories: LiveData<List<Category>> = repository.getUsedCategoriesByType("income")

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    fun addCategory(name: String, type: String, icon: String = "📌", id: String = UUID.randomUUID().toString()) {
        viewModelScope.launch {
            val category = Category(id = id, name = name, type = type, icon = icon)
            repository.addCategory(category)
            _toastMessage.value = "分类已添加"
        }
    }

    fun updateCategory(category: Category, newName: String, newIcon: String? = null) {
        viewModelScope.launch {
            val updated = category.copy(
                name = newName,
                icon = newIcon ?: category.icon
            )
            db.categoryDao().update(updated)
        }
    }

    fun getCategoriesWithBills(type: String, periodKey: String, callback: (List<Category>, Map<String, Double>) -> Unit) {
        viewModelScope.launch {
            val activeIds = db.billDao().getActiveCategoryIds(periodKey).toSet()
            val all = db.categoryDao().getCategoriesByTypeSync(type)
            val totals = db.billDao().getCategoryTotals(periodKey, type)
                .associate { it.categoryId to it.total }
            callback(all.filter { it.id in activeIds }, totals)
        }
    }

    fun getBillsByCategory(categoryId: String, month: String, callback: (List<Bill>) -> Unit) {
        viewModelScope.launch {
            callback(repository.getBillsByCategoryAndMonth(categoryId, month))
        }
    }

    fun updateBill(bill: Bill, newAmount: Double, newDate: String, newCategoryId: String) {
        viewModelScope.launch {
            val updated = bill.copy(amount = newAmount, date = newDate, categoryId = newCategoryId)
            repository.updateBill(updated)
            _toastMessage.value = "账单已更新"
        }
    }

    fun deleteCategoryById(id: String) {
        viewModelScope.launch {
            val cat = db.categoryDao().getCategoryById(id)
            if (cat != null) {
                val bills = db.billDao().getBillsByCategory(id)
                if (bills.isEmpty()) {
                    repository.deleteCategory(cat)
                }
            }
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            // 检查是否有账单使用此分类
            val bills = db.billDao().getBillsByCategory(category.id)
            if (bills.isNotEmpty()) {
                _toastMessage.value = "该分类下有账单，无法删除"
                return@launch
            }
            repository.deleteCategory(category)
            _toastMessage.value = "分类已删除"
        }
    }
}
