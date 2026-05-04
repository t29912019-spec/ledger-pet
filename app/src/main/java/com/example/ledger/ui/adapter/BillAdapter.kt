package com.example.ledger.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ledger.data.model.Bill
import com.example.ledger.databinding.ItemBillBinding
import com.example.ledger.ui.ThemeManager
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BillAdapter(
    private val onEditClick: (Bill) -> Unit,
    private val onDeleteClick: (Bill) -> Unit
) : ListAdapter<Bill, BillAdapter.BillViewHolder>(BillDiffCallback()) {

    private val df = DecimalFormat("#,##0.00")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BillViewHolder {
        val binding = ItemBillBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BillViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BillViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BillViewHolder(
        private val binding: ItemBillBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bill: Bill) {
            val prefix = if (bill.type == "income") "+" else "-"
            binding.tvAmount.text = "$prefix¥${df.format(bill.amount)}"
            binding.tvAmount.setTextColor(
                if (bill.type == "income")
                    ThemeManager.getColors(itemView.context).income
                else
                    ThemeManager.getColors(itemView.context).expense
            )
            binding.tvDate.text = formatBillDate(bill.date)
            binding.tvNote.text = bill.note
            binding.btnEdit.setOnClickListener { onEditClick(bill) }
            binding.btnDelete.setOnClickListener { onDeleteClick(bill) }
        }
    }

    companion object {
        fun formatBillDate(dateStr: String): String {
            return try {
                if (dateStr.contains(" ")) {
                    val dt = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    "${dt.monthValue}月${dt.dayOfMonth}日 ${dt.hour}:${dt.minute.toString().padStart(2, '0')}"
                } else {
                    val d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    "${d.monthValue}月${d.dayOfMonth}日"
                }
            } catch (_: Exception) {
                dateStr
            }
        }
    }
}

class BillDiffCallback : DiffUtil.ItemCallback<Bill>() {
    override fun areItemsTheSame(oldItem: Bill, newItem: Bill): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Bill, newItem: Bill): Boolean =
        oldItem == newItem
}
