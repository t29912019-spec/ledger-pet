package com.example.ledger.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ledger.R
import com.example.ledger.data.model.Category
import com.example.ledger.databinding.ItemCategoryBinding
import java.text.DecimalFormat

class CategoryAdapter(
    private val onClick: (Category) -> Unit,
    private val onLongClick: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    private var amounts: Map<String, Double> = emptyMap()
    private val df = DecimalFormat("#,##0.00")

    fun refresh() {
        if (currentList.isNotEmpty()) notifyItemRangeChanged(0, currentList.size)
    }

    fun submitListWithAmounts(list: List<Category>, amounts: Map<String, Double>) {
        this.amounts = amounts
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: Category) {
            val customFile = java.io.File(
                binding.root.context.filesDir,
                "category_icons/${category.id}.png"
            )
            if (customFile.exists()) {
                binding.tvIcon.visibility = android.view.View.GONE
                binding.ivCustomIcon.visibility = android.view.View.VISIBLE
                binding.ivCustomIcon.setImageBitmap(
                    android.graphics.BitmapFactory.decodeFile(customFile.absolutePath)
                )
            } else {
                binding.ivCustomIcon.visibility = android.view.View.GONE
                binding.tvIcon.visibility = android.view.View.VISIBLE
                binding.tvIcon.text = category.icon
            }
            binding.tvName.text = category.name
            binding.tvType.text = if (category.type == "expense") "支出" else "收入"
            val amt = amounts[category.id]
            if (amt != null && amt != 0.0) {
                binding.tvAmount.visibility = android.view.View.VISIBLE
                binding.tvAmount.text = "¥${df.format(amt)}"
                binding.tvAmount.setTextColor(
                    if (category.type == "expense")
                        binding.root.context.getColor(com.example.ledger.R.color.expense)
                    else
                        binding.root.context.getColor(com.example.ledger.R.color.income)
                )
            } else {
                binding.tvAmount.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onClick(category) }
            binding.root.setOnLongClickListener {
                onLongClick(category)
                true
            }
        }
    }
}

class CategoryDiffCallback : DiffUtil.ItemCallback<Category>() {
    override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean =
        oldItem == newItem
}
