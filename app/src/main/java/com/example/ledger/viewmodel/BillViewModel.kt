package com.example.ledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.ledger.data.db.AppDatabase
import com.example.ledger.data.model.Bill
import com.example.ledger.data.repository.LedgerRepository
import com.example.ledger.data.model.Category
import kotlinx.coroutines.launch
import java.util.UUID

class BillViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = LedgerRepository(db.billDao(), db.categoryDao())

    // Room LiveData 自动刷新
    val allBills: LiveData<List<Bill>> = repository.getAllBills()

    var currentMonth: String? = null
        private set

    private val _billsByMonth = MutableLiveData<List<Bill>>()
    val billsByMonth: LiveData<List<Bill>> = _billsByMonth

    private val _categories = MutableLiveData<List<Category>>()
    val categories: LiveData<List<Category>> = _categories

    fun deleteBill(bill: Bill) {
        viewModelScope.launch {
            repository.deleteBill(bill)
            currentMonth?.let { loadBillsByMonth(it) }
        }
    }

    fun updateBill(bill: Bill, newAmount: Double, newDate: String, newCategoryId: String) {
        viewModelScope.launch {
            val updated = bill.copy(amount = newAmount, date = newDate, categoryId = newCategoryId)
            repository.updateBill(updated)
            currentMonth?.let { loadBillsByMonth(it) }
        }
    }

    fun addCategory(name: String, type: String, icon: String, id: String) {
        viewModelScope.launch {
            val category = Category(id = id, name = name, type = type, icon = icon)
            repository.addCategory(category)
            loadCategories(type)
        }
    }

    fun loadCategories(type: String) {
        viewModelScope.launch {
            val used = repository.getUsedCategoriesByTypeSync(type)
            _categories.value = if (used.isNotEmpty()) used
                else repository.getAllCategoriesSync().filter { it.type == type }
        }
    }

    fun getAllCategoriesByTypeSync(type: String): List<Category> {
        return kotlinx.coroutines.runBlocking { db.categoryDao().getCategoriesByTypeSync(type) }
    }

    fun deleteCategoryById(id: String) {
        viewModelScope.launch {
            val cat = db.categoryDao().getCategoryById(id)
            if (cat != null) {
                db.categoryDao().delete(cat)
            }
        }
    }

    fun getBillsByCategory(categoryId: String, callback: (List<Bill>) -> Unit) {
        viewModelScope.launch {
            callback(repository.getBillsByCategory(categoryId))
        }
    }

    fun loadBillsByMonth(month: String) {
        currentMonth = month
        viewModelScope.launch {
            _billsByMonth.value = db.billDao().getBillsByMonthSync(month)
        }
    }
}
