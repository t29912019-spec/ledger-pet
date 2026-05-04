package com.example.ledger.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.ledger.R
import com.example.ledger.databinding.ActivityStatisticsBinding
import com.example.ledger.viewmodel.MainViewModel
import com.example.ledger.viewmodel.PeriodType
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class StatisticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatisticsBinding
    private lateinit var viewModel: MainViewModel
    private val df = DecimalFormat("#,##0.00")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatisticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        viewModel.monthlyStats.observe(this) { stats ->
            binding.tvTotalExpense.text = df.format(stats.totalExpense)
            binding.tvTotalIncome.text = df.format(stats.totalIncome)
            binding.tvNetWorth.text = df.format(stats.netWorth)
            if (stats.isSurplus) {
                binding.tvStatus.text = getString(R.string.surplus)
                binding.tvStatus.setTextColor(ThemeManager.getColors(this).income)
            } else {
                binding.tvStatus.text = getString(R.string.deficit)
                binding.tvStatus.setTextColor(ThemeManager.getColors(this).expense)
            }
        }

        viewModel.periodLabel.observe(this) { label ->
            binding.tvPeriodLabel.text = label
            binding.tvTitle.text = "${label}统计"
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.cardPeriod.setOnClickListener { showPeriodPickerDialog() }

        binding.rowExpense.setOnClickListener { showBreakdownDialog("expense") }
        binding.rowIncome.setOnClickListener { showBreakdownDialog("income") }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun showBreakdownDialog(type: String) {
        viewModel.getCategoryBreakdown(type) { items ->
            runOnUiThread {
                if (items.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(if (type == "expense") "支出来源" else "收入来源")
                        .setMessage("该周期暂无数据")
                        .setPositiveButton("确定", null)
                        .show()
                    return@runOnUiThread
                }
                val sorted = items.sortedByDescending { it.third }
                val sb = StringBuilder()
                for ((name, icon, amt) in sorted) {
                    sb.append("$icon $name  ¥${df.format(amt)}\n")
                }
                val title = if (type == "expense") "支出来源" else "收入来源"
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun showPeriodPickerDialog() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // 从 ViewModel 读取当前周期
        val curType = viewModel.periodType.value ?: PeriodType.MONTH
        val curKey = viewModel.periodKey.value ?: ""
        val curDate = parsePeriodKey(curKey, curType)
        val curYear = curDate.year
        val curMonth = curDate.monthValue
        val curDay = curDate.dayOfMonth
        val thisYear = LocalDate.now().year

        // 初始值：0=不选
        val initMonth = when (curType) {
            PeriodType.YEAR -> 0
            else -> curMonth
        }
        val initDay = when (curType) {
            PeriodType.DAY -> curDay
            else -> 0
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }

        val tvPreview = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER
            setTextColor(ThemeManager.getColors(this@StatisticsActivity).primary)
            setPadding(0, dp(8), 0, dp(12))
        }

        val pickerRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun makeColumn(label: String, picker: android.widget.NumberPicker): android.widget.LinearLayout {
            return android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(this@StatisticsActivity).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(ThemeManager.getColors(this@StatisticsActivity).textSecondary)
                })
                addView(picker)
            }
        }

        val yearPicker = android.widget.NumberPicker(this).apply {
            minValue = 2016; maxValue = thisYear + 1; value = curYear
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        pickerRow.addView(makeColumn("年", yearPicker),
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val monthLabels = arrayOf("—") + (1..12).map { "${it}月" }.toTypedArray()
        val monthPicker = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 12; value = initMonth
            displayedValues = monthLabels
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        pickerRow.addView(makeColumn("月", monthPicker),
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val dayLabels = arrayOf("—") + (1..31).map { "${it}日" }.toTypedArray()
        val dayPicker = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 31; value = initDay
            displayedValues = dayLabels
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        pickerRow.addView(makeColumn("日", dayPicker),
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(tvPreview)
        root.addView(pickerRow)

        fun updatePreview() {
            val y = yearPicker.value; val m = monthPicker.value; val d = dayPicker.value
            tvPreview.text = when {
                m == 0 -> "${y}年"
                d == 0 -> "${y}年${m}月"
                else -> "${y}年${m}月${d}日"
            }
        }
        updatePreview()

        yearPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        monthPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        dayPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }

        AlertDialog.Builder(this)
            .setTitle("选择周期")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val y = yearPicker.value
                val m = monthPicker.value
                var d = dayPicker.value
                if (m > 0 && d > 0) {
                    val maxD = LocalDate.of(y, m, 1).lengthOfMonth()
                    if (d > maxD) d = maxD
                }
                val (newType, newKey) = when {
                    m == 0 -> Pair(PeriodType.YEAR, formatPeriodKey(LocalDate.of(y, 1, 1), PeriodType.YEAR))
                    d == 0 -> Pair(PeriodType.MONTH, formatPeriodKey(LocalDate.of(y, m, 1), PeriodType.MONTH))
                    else   -> Pair(PeriodType.DAY, formatPeriodKey(LocalDate.of(y, m, d), PeriodType.DAY))
                }
                viewModel.setCustomPeriod(newType, newKey)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- helpers (same as CategoryManageActivity) ----

    private fun formatPeriodKey(date: LocalDate, type: PeriodType): String = when (type) {
        PeriodType.YEAR -> date.format(DateTimeFormatter.ofPattern("yyyy"))
        PeriodType.MONTH -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        PeriodType.DAY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun parsePeriodKey(key: String, type: PeriodType): LocalDate = when (type) {
        PeriodType.YEAR -> LocalDate.parse("$key-01-01", DateTimeFormatter.ISO_LOCAL_DATE)
        PeriodType.MONTH -> LocalDate.parse("$key-01", DateTimeFormatter.ISO_LOCAL_DATE)
        PeriodType.DAY -> LocalDate.parse(key, DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun applyTheme() {
        val c = ThemeManager.getColors(this)
        window.statusBarColor = c.statusBarColor
        binding.root.setBackgroundColor(c.background)
        binding.headerBar.setBackgroundColor(c.primary)
    }
}
