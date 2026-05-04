package com.example.ledger.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ledger.R
import com.example.ledger.data.model.Bill
import com.example.ledger.databinding.ActivityMainBinding
import com.example.ledger.ui.adapter.BillAdapter
import com.example.ledger.viewmodel.MainViewModel
import com.example.ledger.viewmodel.PeriodType
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: BillAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val df = DecimalFormat("#,##0.00")

    private val categoryIcons = listOf(
        "💰", "💵", "💴", "💶", "💷", "💸",
        "🍔", "🍕", "🍜", "🍰", "☕", "🍺",
        "🚗", "🚌", "🚲", "✈️", "🚕", "⛽",
        "🏠", "🏪", "🏥", "🏫", "🎬", "💊",
        "📱", "💻", "🎮", "📚", "👕", "💄",
        "🎁", "🐱", "🎵", "⚽", "🌟", "📌",
    )
    private var pendingCustomIconId: String? = null
    private var pendingCustomIconCallback: ((Boolean) -> Unit)? = null
    private var pendingCategoryId: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val categoryId = pendingCustomIconId ?: return@registerForActivityResult
        pendingCustomIconId = null
        if (uri != null) {
            val ok = saveCustomIcon(categoryId, uri)
            pendingCustomIconCallback?.invoke(ok)
        } else {
            pendingCustomIconCallback?.invoke(false)
        }
        pendingCustomIconCallback = null
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            startFloatingService()
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能使用桌面宠物", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        if (!hasOverlayPermission()) {
            requestOverlayPermission()
        } else {
            startFloatingService()
        }
    }

    private fun setupRecyclerView() {
        adapter = BillAdapter(
            onEditClick = { bill -> showEditDialog(bill) },
            onDeleteClick = { bill -> viewModel.deleteBill(bill) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
    }

    private fun setupObservers() {
        viewModel.recentBills.observe(this) { bills ->
            adapter.submitList(bills?.take(20) ?: emptyList())
            binding.emptyHint.visibility = if (bills.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

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

        viewModel.periodType.observe(this) { type ->
            updatePeriodHighlight(type)
        }

        viewModel.periodLabel.observe(this) { label ->
            binding.tvPeriodLabel.text = "${label}概览"
        }

        viewModel.toastMessage.observe(this) { msg ->
            msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupClickListeners() {
        binding.fabAdd.setOnClickListener {
            Intent(this, InputActivity::class.java).also { startActivity(it) }
        }

        binding.btnYear.setOnClickListener { viewModel.setPeriodType(PeriodType.YEAR) }
        binding.btnMonth.setOnClickListener { viewModel.setPeriodType(PeriodType.MONTH) }
        binding.btnDay.setOnClickListener { viewModel.setPeriodType(PeriodType.DAY) }

        binding.btnRepeat.setOnClickListener {
            showIconPicker()
        }

        binding.cardBills.setOnClickListener {
            startActivity(Intent(this, BillListActivity::class.java))
        }

        binding.cardStats.setOnClickListener {
            startActivity(Intent(this, StatisticsActivity::class.java))
        }

        binding.cardCategories.setOnClickListener {
            startActivity(Intent(this, CategoryManageActivity::class.java))
        }

        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun startFloatingService() {
        if (!FloatingWindowService.isRunning) {
            val intent = Intent(this, FloatingWindowService::class.java)
            startForegroundService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        viewModel.resetToCurrentPeriod()
    }

    @SuppressLint("DiscouragedApi")
    private fun showIconPicker() {
        val icons = listOf(
            "bg_pet" to "橙色",
            "bg_pet_blue" to "蓝色",
            "bg_pet_green" to "绿色",
            "bg_pet_pink" to "粉色",
            "bg_pet_purple" to "紫色",
            "bg_pet_red" to "红色",
        )

        val density = resources.displayMetrics.density
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        icons.forEach { (name, label) ->
            val resId = resources.getIdentifier(name, "drawable", packageName)
            val itemLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val margin = (8 * density).toInt()
                val params = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = android.widget.GridLayout.spec(
                        android.widget.GridLayout.UNDEFINED, 1f
                    )
                    setMargins(margin, margin, margin, margin)
                }
                layoutParams = params
            }

            val preview = View(this).apply {
                setBackgroundResource(resId)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (48 * density).toInt(), (48 * density).toInt()
                )
            }
            itemLayout.addView(preview)

            val tv = android.widget.TextView(this).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            itemLayout.addView(tv)

            itemLayout.setOnClickListener {
                FloatingWindowService.saveIconPref(this, name)
                Toast.makeText(this, "图标已更换为$label", Toast.LENGTH_SHORT).show()
            }
            grid.addView(itemLayout)
        }

        AlertDialog.Builder(this)
            .setTitle("选择悬浮窗图标")
            .setView(grid)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun getCustomIconDir() = File(filesDir, "category_icons").also { it.mkdirs() }
    private fun getCustomIconFile(id: String) = File(getCustomIconDir(), "$id.png")
    private fun hasCustomIcon(id: String) = getCustomIconFile(id).exists()
    private fun saveCustomIcon(id: String, uri: Uri): Boolean {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return false
            getCustomIconFile(id).outputStream().use { input.copyTo(it) }
            true
        } catch (_: Exception) { false }
    }
    private fun deleteCustomIcon(id: String) { getCustomIconFile(id).delete() }
    private fun loadCustomIconBitmap(id: String) =
        if (hasCustomIcon(id)) BitmapFactory.decodeFile(getCustomIconFile(id).absolutePath) else null

    private fun showEditDialog(bill: Bill) {
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
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "分类："; textSize = 14f
                setTextColor(ThemeManager.getColors(this@MainActivity).textSecondary)
            })
            addView(android.widget.FrameLayout(this@MainActivity).apply {
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
            updateCatDisplay(cat) // cat 可能为 null，updateCatDisplay 会显示"未知分类"
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
            setTextColor(ThemeManager.getColors(this@MainActivity).textSecondary)
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
        val tvIcon = android.widget.TextView(this).apply {
            text = selectedIcon; textSize = 28f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((ThemeManager.getColors(this@MainActivity).primary and 0x00FFFFFF) or 0x15000000)
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
        iconRow.addView(android.widget.TextView(this).apply {
            text = "  选择图标"; textSize = 14f
            setTextColor(ThemeManager.getColors(this@MainActivity).textSecondary)
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
            setColor((ThemeManager.getColors(this@MainActivity).primary and 0x00FFFFFF) or 0x30000000)
            cornerRadius = 8 * density
        }

        val cellSize = dp(44)
        val margin = dp(4)
        val iconViews = mutableListOf<android.widget.TextView>()
        var addCellView: android.widget.TextView? = null

        fun updateHighlights() {
            iconViews.forEach { tv ->
                tv.background = if (!isCustomImage && tv.text.toString() == selectedEmoji) selectedBg else null
            }
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
            val tv = android.widget.TextView(this).apply {
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
        if (categoryIcons.size % columns == 0) {
            row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            root.addView(row)
        }
        val addCell = android.widget.TextView(this).apply {
            text = "➕"; textSize = 28f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, cellSize, 1f).apply {
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener {
                pendingCustomIconId = categoryId
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
            val row = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(android.widget.TextView(this@MainActivity).apply {
                text = labelText; textSize = 14f
                setTextColor(ThemeManager.getColors(this@MainActivity).textSecondary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    dp(36), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            val et = EditText(this@MainActivity).apply {
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
            setTextColor(ThemeManager.getColors(this@MainActivity).primary)
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
            setBackgroundColor((ThemeManager.getColors(this@MainActivity).primary and 0x00FFFFFF) or 0x20000000)
        })

        // 时间部分
        root.addView(android.widget.TextView(this).apply {
            text = "时间"; textSize = 12f; setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ThemeManager.getColors(this@MainActivity).primary)
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

    private fun updatePeriodHighlight(type: PeriodType) {
        val c = ThemeManager.getColors(this)
        binding.btnYear.setTextColor(if (type == PeriodType.YEAR) c.primary else c.textSecondary)
        binding.btnMonth.setTextColor(if (type == PeriodType.MONTH) c.primary else c.textSecondary)
        binding.btnDay.setTextColor(if (type == PeriodType.DAY) c.primary else c.textSecondary)
    }

    private fun applyTheme() {
        val c = ThemeManager.getColors(this)
        window.statusBarColor = c.statusBarColor
        binding.root.setBackgroundColor(c.background)
        binding.cardSummary.setCardBackgroundColor(c.surface)
        binding.cardBills.setCardBackgroundColor(c.surface)
        binding.cardCategories.setCardBackgroundColor(c.surface)
        binding.cardStats.setCardBackgroundColor(c.surface)
        binding.cardSettings.setCardBackgroundColor(c.surface)
    }
}
