package com.example.ledger.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.ledger.data.model.Category

@Dao
interface CategoryDao {

    @Insert
    suspend fun insert(category: Category)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name")
    fun getCategoriesByType(type: String): LiveData<List<Category>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name")
    suspend fun getCategoriesByTypeSync(type: String): List<Category>

    @Query("SELECT * FROM categories ORDER BY type, name")
    fun getAllCategories(): LiveData<List<Category>>

    @Query("SELECT * FROM categories ORDER BY type, name")
    suspend fun getAllCategoriesSync(): List<Category>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategoryById(id: String): Category?

    @Query("SELECT COUNT(*) FROM categories WHERE type = :type")
    suspend fun getCategoryCount(type: String): Int

    @Query("SELECT DISTINCT c.* FROM categories c INNER JOIN bills b ON c.id = b.categoryId WHERE c.type = :type ORDER BY c.name")
    suspend fun getUsedCategoriesByTypeSync(type: String): List<Category>

    @Query("SELECT DISTINCT c.* FROM categories c INNER JOIN bills b ON c.id = b.categoryId WHERE c.type = :type ORDER BY c.name")
    fun getUsedCategoriesByType(type: String): LiveData<List<Category>>
}
