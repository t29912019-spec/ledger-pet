package com.example.ledger.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledger.R
import com.example.ledger.data.model.Bill
import com.example.ledger.data.model.Category
import com.example.ledger.databinding.ActivityCategoryManageBinding
import com.example.ledger.ui.adapter.BillAdapter
import com.example.ledger.ui.adapter.CategoryAdapter
import com.example.ledger.viewmodel.CategoryViewModel
import com.example.ledger.viewmodel.PeriodType
import java.io.File
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CategoryManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryManageBinding
    private lateinit var viewModel: CategoryViewModel
    private lateinit var adapter: CategoryAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCategoryId: String? = null
    private var selectedType: String = "expense"
    private val df = DecimalFormat("#,##0.00")
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    private var periodType = PeriodType.MONTH
    private var periodKey = LocalDate.now().format(monthFormatter)
    private var periodLabel = ""
    private var currentBillsDialog: android.app.Dialog? = null

    private val categoryIcons = listOf(
        "💰", "💵", "💴", "💶", "💷", "💸",
        "🍔", "🍕", "🍜", "🍰", "☕", "🍺",
        "🚗", "🚌", "🚲", "✈️", "🚕", "⛽",
        "🏠", "🏪", "🏥", "🏫", "🎬", "💊",
        "📱", "💻", "🎮", "📚", "👕", "💄",
        "🎁", "🐱", "🎵", "⚽", "🌟", "📌",
    )

    private var pendingCustomIconCategoryId: String? = null
    private var pendingCustomIconCallback: ((Boolean) -> Unit)? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val categoryId = pendingCustomIconCategoryId ?: return@registerForActivityResult
        pendingCustomIconCategoryId = null
        if (uri != null) {
            val success = saveCustomIcon(categoryId, uri)
            pendingCustomIconCallback?.invoke(success)
        } else {
            pendingCustomIconCallback?.invoke(false)
        }
        pendingCustomIconCallback = null
    }

    private fun getCustomIconDir(): File =
        File(filesDir, "category_icons").also { it.mkdirs() }

    private fun getCustomIconFile(categoryId: String): File =
        File(getCustomIconDir(), "$categoryId.png")

    private fun hasCustomIcon(categoryId: String): Boolean =
        getCustomIconFile(categoryId).exists()

    private fun saveCustomIcon(categoryId: String, uri: Uri): Boolean {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return false
            val output = getCustomIconFile(categoryId).outputStream()
            input.copyTo(output)
            input.close()
            output.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteCustomIcon(categoryId: String) {
        getCustomIconFile(categoryId).delete()
    }

    private fun loadCustomIconBitmap(categoryId: String) =
        if (hasCustomIcon(categoryId))
            BitmapFactory.decodeFile(getCustomIconFile(categoryId).absolutePath)
        else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        viewModel = ViewModelProvider(this)[CategoryViewModel::class.java]

        adapter = CategoryAdapter(
            onClick = { category -> showCategoryBillsDialog(category, periodKey, periodType) },
            onLongClick = { category -> showCategoryOptionsDialog(category) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

        // 使用 MaterialButtonToggleGroup — 比 ChipGroup 更可靠
        binding.toggleGroupType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_expense -> {
                        selectedType = "expense"
                        refreshFilteredCategories()
                    }
                    R.id.btn_income -> {
                        selectedType = "income"
                        refreshFilteredCategories()
                    }
                }
            }
        }

        // 观察 Room LiveData — 分类列表变更时按当前周期重新过滤
        viewModel.expenseCategories.observe(this) { _ ->
            if (selectedType == "expense") refreshFilteredCategories()
        }
        viewModel.incomeCategories.observe(this) { _ ->
            if (selectedType == "income") refreshFilteredCategories()
        }

        viewModel.toastMessage.observe(this) { msg ->
            msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }

        binding.btnBack.setOnClickListener { finish() }

        updatePeriodUI()
        binding.cardPeriod.setOnClickListener { showPeriodPickerDialog() }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun updatePeriodUI() {
        periodLabel = buildPeriodLabel(periodKey, periodType)
        binding.tvPeriodLabel.text = periodLabel
        refreshFilteredCategories()
    }

    private fun showPeriodPickerDialog() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val currentDate = parsePeriodKey(periodKey, periodType)
        val curYear = currentDate.year
        val curMonth = currentDate.monthValue
        val curDay = currentDate.dayOfMonth
        val thisYear = LocalDate.now().year

        // 初始值：0=不选
        val initMonth = when (periodType) {
            PeriodType.YEAR -> 0
            else -> curMonth
        }
        val initDay = when (periodType) {
            PeriodType.DAY -> curDay
            else -> 0
        }

        // 根布局
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }

        // 预览标题
        val tvPreview = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER
            setTextColor(ThemeManager.getColors(this@CategoryManageActivity).primary)
            setPadding(0, dp(8), 0, dp(12))
        }

        // 三列滚轮
        val pickerRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // 辅助函数：创建一列
        fun makeColumn(label: String, picker: android.widget.NumberPicker): android.widget.LinearLayout {
            return android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(this@CategoryManageActivity).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(ThemeManager.getColors(this@CategoryManageActivity).textSecondary)
                })
                addView(picker)
            }
        }

        // 年 — 纯数字，无 "不选"
        val yearPicker = android.widget.NumberPicker(this).apply {
            minValue = 2016; maxValue = thisYear + 1; value = curYear
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        pickerRow.addView(makeColumn("年", yearPicker),
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 月 — 固定标签 ["—","1月".."12月"]，打开后不再修改
        val monthLabels = arrayOf("—") + (1..12).map { "${it}月" }.toTypedArray()
        val monthPicker = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 12; value = initMonth
            displayedValues = monthLabels
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        pickerRow.addView(makeColumn("月", monthPicker),
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 日 — 固定标签 ["—","1日".."31日"]，打开后不再修改
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

        // 只更新预览文字，绝不修改 picker 属性（改 maxValue/displayedValues 会导致崩溃）
        fun updatePreview() {
            val y = yearPicker.value
            val m = monthPicker.value
            val d = dayPicker.value
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
                // 若选中的日超出该月实际天数，自动修正为最后一天
                if (m > 0 && d > 0) {
                    val maxD = LocalDate.of(y, m, 1).lengthOfMonth()
                    if (d > maxD) d = maxD
                }
                val (newType, newKey) = when {
                    m == 0 -> Pair(PeriodType.YEAR, formatPeriodKey(LocalDate.of(y, 1, 1), PeriodType.YEAR))
                    d == 0 -> Pair(PeriodType.MONTH, formatPeriodKey(LocalDate.of(y, m, 1), PeriodType.MONTH))
                    else   -> Pair(PeriodType.DAY, formatPeriodKey(LocalDate.of(y, m, d), PeriodType.DAY))
                }
                periodType = newType
                periodKey = newKey
                updatePeriodUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyTheme() {
        val c = ThemeManager.getColors(this)
        window.statusBarColor = c.statusBarColor
        binding.root.setBackgroundColor(c.background)
        binding.headerBar.setBackgroundColor(c.primary)
    }

    private fun formatPeriodKey(date: LocalDate, type: PeriodType): String = when (type) {
        PeriodType.YEAR -> date.format(DateTimeFormatter.ofPattern("yyyy"))
        PeriodType.MONTH -> date.format(monthFormatter)
        PeriodType.DAY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun parsePeriodKey(key: String, type: PeriodType): LocalDate = when (type) {
        PeriodType.YEAR -> LocalDate.parse("$key-01-01", DateTimeFormatter.ISO_LOCAL_DATE)
        PeriodType.MONTH -> LocalDate.parse("$key-01", DateTimeFormatter.ISO_LOCAL_DATE)
        PeriodType.DAY -> LocalDate.parse(key, DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun buildPeriodLabel(key: String, type: PeriodType): String = when (type) {
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

    private fun refreshFilteredCategories() {
        viewModel.getCategoriesWithBills(selectedType, periodKey) { categories, amounts ->
            runOnUiThread {
                adapter.submitListWithAmounts(categories, amounts)
                binding.emptyHint.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showCategoryBillsDialog(category: Category, key: String, type: PeriodType) {
        currentBillsDialog?.dismiss()
        viewModel.getBillsByCategory(category.id, key) { bills ->
            runOnUiThread {
                val title = "「${category.name}」账单  ${buildPeriodLabel(key, type)}"

                if (bills.isEmpty()) {
                    val emptyMsg = when (type) {
                        PeriodType.YEAR -> "该年暂无账单记录"
                        PeriodType.MONTH -> "该月暂无账单记录"
                        PeriodType.DAY -> "该日暂无账单记录"
                    }
                    currentBillsDialog = AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(emptyMsg)
                        .setPositiveButton("确定") { _, _ -> currentBillsDialog = null }
                        .show()
                    return@runOnUiThread
                }

                val totalAmount = bills.sumOf { it.amount }
                val maxVisible = 5
                val density = resources.displayMetrics.density
                val visibleItems = bills.size.coerceAtMost(maxVisible)
                val fixedHeight = (68 * density * visibleItems).toInt()

                val recyclerView = RecyclerView(this).apply {
                    layoutManager = LinearLayoutManager(this@CategoryManageActivity)
                    adapter = CategoryBillAdapter(bills,
                        onEditClick = { bill -> showBillEditDialog(bill) }
                    )
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, fixedHeight
                    )
                }

                // 标题
                val tvTitle = TextView(this).apply {
                    text = title
                    textSize = 20f
                    setTextColor(ThemeManager.getColors(this@CategoryManageActivity).onSurface)
                    setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), 0)
                }
                // 摘要
                val tvSummary = TextView(this).apply {
                    text = "共 ${bills.size} 笔，合计 ¥${df.format(totalAmount)}"
                    textSize = 14f
                    setTextColor(ThemeManager.getColors(this@CategoryManageActivity).textSecondary)
                    setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
                }

                // 导航按钮文字
                val (prevText, nextText) = when (type) {
                    PeriodType.YEAR -> "上一年" to "下一年"
                    PeriodType.MONTH -> "上个月" to "下个月"
                    PeriodType.DAY -> "上一天" to "下一天"
                }

                // 按钮栏
                val btnBar = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
                    addView(com.google.android.material.button.MaterialButton(this@CategoryManageActivity).apply {
                        text = prevText
                        setTextColor(ThemeManager.getColors(this@CategoryManageActivity).primary)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setOnClickListener {
                            val date = parsePeriodKey(key, type)
                            val prev = when (type) {
                                PeriodType.YEAR -> date.minusYears(1)
                                PeriodType.MONTH -> date.minusMonths(1)
                                PeriodType.DAY -> date.minusDays(1)
                            }
                            val newKey = formatPeriodKey(prev, type)
                            showCategoryBillsDialog(category, newKey, type)
                        }
                    }, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ))
                    addView(com.google.android.material.button.MaterialButton(this@CategoryManageActivity).apply {
                        text = "关闭"
                        setOnClickListener { /* dismiss handled by dialog */ }
                    }, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ))
                    addView(com.google.android.material.button.MaterialButton(this@CategoryManageActivity).apply {
                        text = nextText
                        setTextColor(ThemeManager.getColors(this@CategoryManageActivity).primary)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setOnClickListener {
                            val date = parsePeriodKey(key, type)
                            val next = when (type) {
                                PeriodType.YEAR -> date.plusYears(1)
                                PeriodType.MONTH -> date.plusMonths(1)
                                PeriodType.DAY -> date.plusDays(1)
                            }
                            val newKey = formatPeriodKey(next, type)
                            showCategoryBillsDialog(category, newKey, type)
                        }
                    }, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ))
                }

                val rootLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(tvTitle)
                    addView(tvSummary)
                    addView(recyclerView)
                    addView(btnBar)
                }

                val customDialog = android.app.Dialog(this).apply {
                    setContentView(rootLayout)
                    window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ThemeManager.getColors(this@CategoryManageActivity).surface))
                    setCancelable(true)
                    setCanceledOnTouchOutside(true)
                    setOnDismissListener { currentBillsDialog = null }
                }

                // 关闭按钮
                btnBar.getChildAt(1).setOnClickListener { customDialog.dismiss() }

                currentBillsDialog = customDialog
                customDialog.show()
            }
        }
    }

    private fun showBillEditDialog(bill: Bill) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // 时间
        var editDate = bill.date
        var editTime: String
        try {
            java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(bill.createTime),
                java.time.ZoneId.systemDefault()
            ).let {
                editTime = it.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            }
        } catch (_: Exception) {
            editTime = "00:00:00"
        }

        // 分类
        var selectedCategoryId = bill.categoryId
        val categoriesLive = if (bill.type == "income") viewModel.usedIncomeCategories
            else viewModel.usedExpenseCategories

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }

        // 金额
        val amountInput = EditText(this).apply {
            setText(bill.amount.toBigDecimal().stripTrailingZeros().toPlainString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 18f
        }
        root.addView(amountInput, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 时间行
        addClickableRow(root, "时间", "$editDate $editTime", dp) { tv ->
            showTimeEditDialog(editDate, editTime) { d, t ->
                editDate = d; editTime = t
                tv.text = "$editDate $editTime"
            }
        }

        // 分类行
        val catIconSize = dp(28)
        val tvCatIcon = android.widget.TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(catIconSize, catIconSize)
            visibility = View.GONE
        }
        val ivCatIcon = ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(catIconSize, catIconSize)
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
        }
        val tvCatName = android.widget.TextView(this).apply {
            text = "加载中…"; textSize = 14f
        }
        val catRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.TextView(this@CategoryManageActivity).apply {
                text = "分类："; textSize = 14f
                setTextColor(ThemeManager.getColors(this@CategoryManageActivity).textSecondary)
            })
            addView(android.widget.FrameLayout(this@CategoryManageActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(catIconSize, catIconSize)
                addView(tvCatIcon); addView(ivCatIcon)
            })
            addView(tvCatName)
        }
        root.addView(catRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        fun updateCatDisplay(cat: com.example.ledger.data.model.Category?) {
            if (cat == null) {
                tvCatIcon.visibility = View.GONE; ivCatIcon.visibility = View.GONE
                tvCatName.text = "未知分类"; return
            }
            tvCatName.text = " ${cat.name}"
            val bmp = loadCustomIconBitmap(cat.id)
            if (bmp != null) {
                ivCatIcon.visibility = View.VISIBLE; ivCatIcon.setImageBitmap(bmp)
                tvCatIcon.visibility = View.GONE
            } else {
                tvCatIcon.visibility = View.VISIBLE; tvCatIcon.text = cat.icon
                ivCatIcon.visibility = View.GONE
            }
        }

        catRow.setOnClickListener {
            categoriesLive.value?.let { cats ->
                showCategoryPickerDialog(cats, selectedCategoryId) { cat ->
                    selectedCategoryId = cat.id
                    updateCatDisplay(cat)
                }
            }
        }

        // 初始化分类显示
        categoriesLive.observe(this) { categories ->
            val cat = categories.find { it.id == selectedCategoryId }
            if (cat != null) updateCatDisplay(cat)
        }

        AlertDialog.Builder(this)
            .setTitle("编辑账单")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                pendingCategoryId = null
                val newAmount = amountInput.text.toString().trim().toDoubleOrNull()
                if (newAmount != null && newAmount > 0) {
                    viewModel.updateBill(bill, newAmount, editDate, selectedCategoryId)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                pendingCategoryId?.let { viewModel.deleteCategoryById(it); deleteCustomIcon(it) }
            }
            .show()
    }

    private fun addClickableRow(
        parent: android.widget.LinearLayout,
        label: String, value: String,
        dp: (Int) -> Int,
        onClick: (android.widget.TextView) -> Unit
    ): android.widget.TextView {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tvLabel = android.widget.TextView(this).apply {
            text = "$label："; textSize = 14f
            setTextColor(ThemeManager.getColors(this@CategoryManageActivity).textSecondary)
        }
        val tvValue = android.widget.TextView(this).apply {
            text = value; textSize = 14f
        }
        row.addView(tvLabel)
        row.addView(tvValue)
        row.setOnClickListener { onClick(tvValue) }
        parent.addView(row, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return tvValue
    }

    private fun showCategoryPickerDialog(
        categories: List<com.example.ledger.data.model.Category>,
        selectedId: String?,
        onPick: (com.example.ledger.data.model.Category) -> Unit
    ) {
        val type = categories.firstOrNull()?.type ?: "expense"
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val colors = ThemeManager.getColors(this)
        val checkIdx = categories.indexOfFirst { it.id == selectedId }
        var picked = if (checkIdx >= 0) checkIdx else -1

        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(0, 0, 0, dp(8))
        }
        val listLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }

        val rowViews = mutableListOf<android.view.View>()

        fun selectRow(idx: Int) {
            picked = idx
            rowViews.forEachIndexed { i, v ->
                v.setBackgroundColor(if (i == idx) (colors.primary and 0x00FFFFFF or 0x20000000.toInt()) else android.graphics.Color.TRANSPARENT)
            }
        }

        val iconSize = dp(24)

        for ((i, cat) in categories.withIndex()) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { selectRow(i) }
            }

            val iconFrame = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            }
            val tvEmoji = android.widget.TextView(this).apply {
                textSize = 16f; gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(iconSize, iconSize)
            }
            val ivCustom = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(iconSize, iconSize)
                visibility = android.view.View.GONE
            }
            val customFile = java.io.File(filesDir, "category_icons/${cat.id}.png")
            if (customFile.exists()) {
                tvEmoji.visibility = android.view.View.GONE
                ivCustom.visibility = android.view.View.VISIBLE
                ivCustom.setImageBitmap(android.graphics.BitmapFactory.decodeFile(customFile.absolutePath))
            } else {
                ivCustom.visibility = android.view.View.GONE
                tvEmoji.visibility = android.view.View.VISIBLE
                tvEmoji.text = cat.icon
            }
            iconFrame.addView(tvEmoji)
            iconFrame.addView(ivCustom)
            row.addView(iconFrame)

            val tvName = android.widget.TextView(this).apply {
                text = cat.name; textSize = 15f
                setPadding(dp(10), 0, 0, 0)
                setTextColor(colors.onSurface)
            }
            row.addView(tvName)

            listLayout.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            rowViews.add(row)
        }

        // "新增分类" row — will be wired after dialog is created
        val addRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val addIcon = android.widget.TextView(this).apply {
            text = "➕"; textSize = 16f; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
        }
        addRow.addView(addIcon)
        val addLabel = android.widget.TextView(this).apply {
            text = "新增分类"; textSize = 15f
            setPadding(dp(10), 0, 0, 0)
            setTextColor(colors.primary)
        }
        addRow.addView(addLabel)
        listLayout.addView(addRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        scrollView.addView(listLayout)

        if (picked >= 0) selectRow(picked)

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择分类")
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                if (picked >= 0 && picked < categories.size) {
                    onPick(categories[picked])
                }
            }
            .setNegativeButton("取消", null)
            .create()

        addRow.setOnClickListener {
            dialog.dismiss()
            showAddCategoryAndPickDialog(type) { cat -> onPick(cat) }
        }

        dialog.show()
    }

    private fun showAddCategoryAndPickDialog(
        type: String,
        onCreated: (com.example.ledger.data.model.Category) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val newId = java.util.UUID.randomUUID().toString()
        var selectedIcon = "💰"
        var hasCustomImage = false

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }

        val nameInput = EditText(this).apply {
            hint = "分类名称"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(nameInput)

        val iconSize = dp(44)
        val tvIcon = TextView(this).apply {
            text = selectedIcon; textSize = 28f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((ThemeManager.getColors(this@CategoryManageActivity).primary and 0x00FFFFFF) or 0x15000000)
                cornerRadius = 8 * density
            }
        }
        val ivIconPreview = ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
        }

        fun refreshIconButton() {
            if (hasCustomImage) {
                tvIcon.visibility = View.GONE; ivIconPreview.visibility = View.VISIBLE
                loadCustomIconBitmap(newId)?.let { ivIconPreview.setImageBitmap(it) }
            } else {
                ivIconPreview.visibility = View.GONE; tvIcon.visibility = View.VISIBLE
                tvIcon.text = selectedIcon
            }
        }

        val iconRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconClickable = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            addView(tvIcon); addView(ivIconPreview)
            setOnClickListener {
                showIconPickerDialog(selectedIcon, hasCustomImage, newId) { emoji, isCustom ->
                    selectedIcon = emoji; hasCustomImage = isCustom
                    refreshIconButton()
                }
            }
        }
        iconRow.addView(iconClickable)
        iconRow.addView(TextView(this).apply {
            text = "  选择图标"; textSize = 14f
            setTextColor(ThemeManager.getColors(this@CategoryManageActivity).textSecondary)
        })
        root.addView(iconRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        AlertDialog.Builder(this)
            .setTitle(if (type == "income") "新增收入分类" else "新增支出分类")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    val icon = if (hasCustomImage) "📷" else selectedIcon
                    viewModel.addCategory(name, type, icon, newId)
                    val newCat = com.example.ledger.data.model.Category(id = newId, name = name, type = type, icon = icon)
                    pendingCategoryId = newCat.id
                    onCreated(newCat)
                }
            }
            .setNegativeButton("取消") { _, _ -> deleteCustomIcon(newId) }
            .show()
    }

    private fun showTimeEditDialog(
        date: String, time: String,
        onOk: (date: String, time: String) -> Unit
    ) {
        val dateOnly = date.substringBefore(" ")
        val timeOnly = time.substringBefore(" ")
        var (year, month, day) = try {
            val parts = dateOnly.split("-")
            Triple(parts[0], parts[1], parts[2])
        } catch (_: Exception) { Triple("2024", "01", "01") }
        var (hour, min, sec) = try {
            val parts = timeOnly.split(":")
            Triple(parts[0], parts[1], parts[2])
        } catch (_: Exception) { Triple("00", "00", "00") }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }

        fun makeRow(labelText: String, value: String): EditText {
            val row = android.widget.LinearLayout(this@CategoryManageActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(android.widget.TextView(this@CategoryManageActivity).apply {
                text = labelText; textSize = 14f
                setTextColor(ThemeManager.getColors(this@CategoryManageActivity).textSecondary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    dp(36), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            val et = EditText(this@CategoryManageActivity).apply {
                setText(value); textSize = 16f; gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(et)
            root.addView(row)
            return et
        }

        // 日期部分
        root.addView(android.widget.TextView(this).apply {
            text = "日期"; textSize = 12f; setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ThemeManager.getColors(this@CategoryManageActivity).primary)
            setPadding(0, 0, 0, dp(2))
        })
        val etYear = makeRow("年", year)
        val etMonth = makeRow("月", month)
        val etDay = makeRow("日", day)

        // 分隔
        root.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, dp(8), 0, dp(8)) }
            setBackgroundColor((ThemeManager.getColors(this@CategoryManageActivity).primary and 0x00FFFFFF) or 0x20000000)
        })

        // 时间部分
        root.addView(android.widget.TextView(this).apply {
            text = "时间"; textSize = 12f; setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ThemeManager.getColors(this@CategoryManageActivity).primary)
            setPadding(0, 0, 0, dp(2))
        })
        val etHour = makeRow("时", hour)
        val etMin = makeRow("分", min)
        val etSec = makeRow("秒", sec)

        AlertDialog.Builder(this)
            .setTitle("修改时间")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val y = etYear.text.toString().padStart(4, '0').takeLast(4).ifEmpty { year }
                val mo = etMonth.text.toString().padStart(2, '0').takeLast(2).ifEmpty { month }
                val d = etDay.text.toString().padStart(2, '0').takeLast(2).ifEmpty { day }
                val h = etHour.text.toString().padStart(2, '0').takeLast(2).ifEmpty { hour }
                val mi = etMin.text.toString().padStart(2, '0').takeLast(2).ifEmpty { min }
                val s = etSec.text.toString().padStart(2, '0').takeLast(2).ifEmpty { sec }
                onOk("$y-$mo-$d", "$h:$mi:$s")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCategoryOptionsDialog(category: Category) {
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(arrayOf("编辑名称", "修改图标", "删除分类")) { _, which ->
                when (which) {
                    0 -> showEditDialog(category)
                    1 -> showChangeIconDialog(category)
                    2 -> showDeleteDialog(category)
                }
            }
            .show()
    }

    private fun showEditDialog(category: Category) {
        val input = EditText(this).apply {
            setText(category.name)
            id = android.R.id.edit
        }
        AlertDialog.Builder(this)
            .setTitle("编辑分类")
            .setView(input)
            .setPositiveButton("确定") { dialog, _ ->
                val editText = (dialog as AlertDialog).findViewById<EditText>(android.R.id.edit)
                val newName = editText?.text?.toString()?.trim() ?: ""
                if (newName.isNotEmpty()) {
                    viewModel.updateCategory(category, newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showChangeIconDialog(category: Category) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val catId = category.id
        var currentIcon = category.icon
        var currentIsCustom = hasCustomIcon(catId)
        var changed = false

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), 0)
        }

        // 当前图标预览
        val iconSize = dp(44)
        val tvIcon = TextView(this).apply {
            text = currentIcon; textSize = 32f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
        }
        val ivCustomIcon = ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
        }
        fun refreshPreview() {
            if (currentIsCustom) {
                val bmp = loadCustomIconBitmap(catId)
                if (bmp != null) {
                    tvIcon.visibility = View.GONE; ivCustomIcon.visibility = View.VISIBLE
                    ivCustomIcon.setImageBitmap(bmp)
                } else {
                    ivCustomIcon.visibility = View.GONE; tvIcon.visibility = View.VISIBLE
                    tvIcon.text = currentIcon
                }
            } else {
                ivCustomIcon.visibility = View.GONE; tvIcon.visibility = View.VISIBLE
                tvIcon.text = currentIcon
            }
        }
        refreshPreview()
        root.addView(android.widget.LinearLayout(this).apply {
            gravity = Gravity.CENTER; addView(tvIcon); addView(ivCustomIcon)
        })

        // 更换图标按钮
        root.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "更换图标"; textSize = 14f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                showIconPickerDialog(currentIcon, currentIsCustom, catId) { emoji, isCustom ->
                    currentIcon = emoji
                    currentIsCustom = isCustom
                    if (isCustom) {
                        viewModel.updateCategory(category, category.name, emoji)
                    } else {
                        deleteCustomIcon(catId)
                        viewModel.updateCategory(category, category.name, emoji)
                    }
                    refreshPreview()
                    changed = true
                }
            }
        })

        // 恢复默认（仅在有自定义图片时显示）
        val btnReset = com.google.android.material.button.MaterialButton(this).apply {
            text = "恢复默认"; textSize = 14f
            visibility = if (currentIsCustom) View.VISIBLE else View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setOnClickListener {
                currentIsCustom = false
                deleteCustomIcon(catId)
                refreshPreview()
                changed = true
                visibility = View.GONE
            }
        }
        root.addView(btnReset)

        AlertDialog.Builder(this)
            .setTitle("修改「${category.name}」图标")
            .setView(root)
            .setPositiveButton("完成") { _, _ ->
                if (changed) {
                    adapter.refresh()
                    Toast.makeText(this@CategoryManageActivity, "图标已更新", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showIconPickerDialog(
        currentIcon: String,
        hasCustom: Boolean,
        categoryId: String,
        onSelected: (emoji: String, isCustom: Boolean) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val columns = 6
        var selectedEmoji = currentIcon
        var isCustomImage = hasCustom

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val selectedBg = android.graphics.drawable.GradientDrawable().apply {
            setColor((ThemeManager.getColors(this@CategoryManageActivity).primary and 0x00FFFFFF) or 0x30000000)
            cornerRadius = 8 * density
        }

        val cellSize = dp(44)
        val margin = dp(4)
        val iconViews = mutableListOf<TextView>()
        var addCellView: TextView? = null

        fun updateHighlights() {
            iconViews.forEach { tv ->
                tv.background = if (!isCustomImage && tv.text.toString() == selectedEmoji) selectedBg else null
            }
            // ➕ cell highlight when custom image active
            addCellView?.let { cell ->
                cell.background = if (isCustomImage) selectedBg else null
            }
        }

        var row: android.widget.LinearLayout? = null
        categoryIcons.forEachIndexed { index, icon ->
            if (index % columns == 0) {
                row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                root.addView(row)
            }
            val tv = TextView(this).apply {
                text = icon; textSize = 28f; gravity = Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0, cellSize, 1f).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener {
                    selectedEmoji = icon; isCustomImage = false
                    updateHighlights()
                }
            }
            iconViews.add(tv); row!!.addView(tv)
        }
        // ➕ 自定义图片
        if (categoryIcons.size % columns == 0) {
            row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            root.addView(row)
        }
        val addCell = TextView(this).apply {
            text = "➕"; textSize = 28f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, cellSize, 1f).apply {
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener {
                pendingCustomIconCategoryId = categoryId
                pendingCustomIconCallback = { success ->
                    if (success) {
                        isCustomImage = true; selectedEmoji = ""
                        updateHighlights()
                    }
                }
                pickImageLauncher.launch("image/*")
            }
        }
        addCellView = addCell
        row!!.addView(addCell)
        updateHighlights()

        AlertDialog.Builder(this)
            .setTitle("选择图标")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                if (!isCustomImage) deleteCustomIcon(categoryId)
                onSelected(if (isCustomImage) "📷" else selectedEmoji, isCustomImage)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(category: Category) {
        AlertDialog.Builder(this)
            .setTitle("确认删除？")
            .setMessage("删除分类「${category.name}」？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteCategory(category)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class CategoryBillAdapter(
        private val bills: List<Bill>,
        private val onEditClick: (Bill) -> Unit
    ) : RecyclerView.Adapter<CategoryBillAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(bills[position])
        }

        override fun getItemCount(): Int = bills.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val text1: TextView = itemView.findViewById(android.R.id.text1)
            private val text2: TextView = itemView.findViewById(android.R.id.text2)

            fun bind(bill: Bill) {
                val prefix = if (bill.type == "income") "+" else "-"
                text1.text = "$prefix¥${df.format(bill.amount)}  ${bill.note}"
                text2.text = BillAdapter.formatBillDate(bill.date)
                itemView.setOnClickListener { onEditClick(bill) }
            }
        }
    }
}
