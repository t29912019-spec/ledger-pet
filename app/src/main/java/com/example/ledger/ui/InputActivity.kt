package com.example.ledger.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledger.R
import com.example.ledger.data.model.Category
import com.example.ledger.util.ClipboardHelper
import com.example.ledger.viewmodel.MainViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.io.File

class InputActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var etAmount: EditText
    private lateinit var tvTime: TextView
    private lateinit var chipExpense: Chip
    private lateinit var chipIncome: Chip
    private lateinit var recyclerCategories: RecyclerView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var selectedCategoryId: String? = null
    private var selectedType: String = "expense"
    private var selectedTime: String = ""  // yyyy-MM-dd HH:mm:ss
    private var pendingCategoryId: String? = null
    private var pendingNewCategory: Category? = null
    private val categoryAdapter = CategoryChipAdapter { category ->
        selectedCategoryId = category.id
        highlightSelectedChip()
    }

    // 图标相关
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 窗口悬浮显示 — 必须在 setContentView 前配置
        window.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.4f)
        }

        setContentView(R.layout.activity_input)
        applyTheme()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        initViews()
        initTime()
        setupRecyclerView()
        setupListeners()
        observeCategories()
        loadLastCategory()

        // 先检查剪贴板，再检查截图预填（截图优先）
        val prefillAmount = intent.getDoubleExtra("amount", 0.0)
        val prefillType = intent.getStringExtra("type")
        val prefillTime = intent.getStringExtra("time")
        if (prefillAmount > 0) {
            etAmount.setText(prefillAmount.toBigDecimal().stripTrailingZeros().toPlainString())
            etAmount.setSelection(etAmount.text.length)
        } else {
            checkClipboard()
        }
        if (prefillType == "income") {
            chipIncome.isChecked = true
            selectedType = "income"
            loadCategories()
        }
        if (!prefillTime.isNullOrEmpty()) {
            selectedTime = prefillTime
            tvTime.text = selectedTime
        }

        onBackPressedDispatcher.addCallback(this) {
            pendingCategoryId?.let { viewModel.deleteCategoryById(it); deleteCustomIcon(it) }
            pendingNewCategory = null
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun initViews() {
        etAmount = findViewById(R.id.et_amount)
        tvTime = findViewById(R.id.tv_time)
        chipExpense = findViewById(R.id.chip_expense)
        chipIncome = findViewById(R.id.chip_income)
        recyclerCategories = findViewById(R.id.recycler_categories)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
    }

    private fun initTime() {
        val now = java.time.LocalDateTime.now()
        selectedTime = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        tvTime.text = selectedTime
    }

    private fun setupRecyclerView() {
        recyclerCategories.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        recyclerCategories.adapter = categoryAdapter
    }

    private fun setupListeners() {
        chipExpense.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedType = "expense"
                selectedCategoryId = null
                loadCategories()
            }
        }
        chipIncome.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedType = "income"
                selectedCategoryId = null
                loadCategories()
            }
        }

        btnSave.setOnClickListener {
            val amountText = etAmount.text.toString().trim()
            val amount = amountText.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "请输入有效金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val categoryId = selectedCategoryId
            if (categoryId == null) {
                Toast.makeText(this, "请选择分类", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.addBill(amount, categoryId, selectedType, "", selectedTime)
            pendingCategoryId = null
            pendingNewCategory = null
            // 触发宠物临时姿势
            FloatingWindowService.setTempPose(
                if (selectedType == "income") PetView.Pose.EATING
                else PetView.Pose.TOILET
            )
            finish()
        }

        btnCancel.setOnClickListener {
            pendingCategoryId?.let { viewModel.deleteCategoryById(it); deleteCustomIcon(it) }
            pendingNewCategory = null
            finish()
        }

        tvTime.setOnClickListener { showTimeEditDialog() }

        // 添加分类按钮
        findViewById<View>(R.id.btn_add_category)?.setOnClickListener {
            showAddCategoryDialog()
        }
    }

    private fun submitCategoriesWithPending(base: List<Category>) {
        val merged = mutableListOf<Category>()
        pendingNewCategory?.let { if (it.type == selectedType) merged.add(it) }
        merged.addAll(base)
        categoryAdapter.submitList(merged)
    }

    private fun observeCategories() {
        viewModel.usedExpenseCategories.observe(this) { categories ->
            if (selectedType == "expense") {
                submitCategoriesWithPending(categories)
            }
        }
        viewModel.usedIncomeCategories.observe(this) { categories ->
            if (selectedType == "income") {
                submitCategoriesWithPending(categories)
            }
        }
    }

    private fun loadCategories() {
        if (selectedType == "expense") {
            viewModel.usedExpenseCategories.value?.let { submitCategoriesWithPending(it) }
        } else {
            viewModel.usedIncomeCategories.value?.let { submitCategoriesWithPending(it) }
        }
    }

    private fun loadLastCategory() {
        viewModel.lastCategoryId.observe(this) { categoryId ->
            if (categoryId != null && selectedCategoryId == null) {
                // 验证该分类属于当前 selectedType，避免收入分类被自动选为支出
                val allCats = if (selectedType == "expense")
                    viewModel.expenseCategories.value
                else
                    viewModel.incomeCategories.value
                if (allCats?.any { it.id == categoryId } == true) {
                    selectedCategoryId = categoryId
                    highlightSelectedChip()
                }
            }
        }
    }

    private fun highlightSelectedChip() {
        // Notify the adapter to refresh highlighting
        categoryAdapter.selectedId = selectedCategoryId
    }

    private fun applyTheme() {
        val c = ThemeManager.getColors(this)
        window.statusBarColor = c.statusBarColor
    }

    private fun checkClipboard() {
        val amount = ClipboardHelper.extractAmount(this)
        if (amount != null && amount > 0) {
            etAmount.setText(amount.toBigDecimal().stripTrailingZeros().toPlainString())
            etAmount.setSelection(etAmount.text.length)
        }
    }

    private fun showTimeEditDialog() {
        val parts = selectedTime.split("[- :]".toRegex())
        if (parts.size < 6) return
        var year = parts[0]; var month = parts[1]; var day = parts[2]
        var hour = parts[3]; var min = parts[4]; var sec = parts[5]

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }

        fun makeRow(labelText: String, value: String): EditText {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(TextView(this@InputActivity).apply {
                text = labelText; textSize = 14f; setTextColor(ThemeManager.getColors(this@InputActivity).textSecondary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    dp(36), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            val et = EditText(this@InputActivity).apply {
                setText(value); textSize = 16f; gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(et)
            root.addView(row)
            return et
        }

        val etYear = makeRow("年", year)
        val etMonth = makeRow("月", month)
        val etDay = makeRow("日", day)
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
                selectedTime = "$y-$mo-$d $h:$mi:$s"
                tvTime.text = selectedTime
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val newId = java.util.UUID.randomUUID().toString()
        var dialogIcon = "💰"
        var hasCustomImage = false

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }

        val iconSize = dp(44)
        val tvIcon = TextView(this).apply {
            text = dialogIcon; textSize = 28f; gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((ThemeManager.getColors(this@InputActivity).primary and 0x00FFFFFF) or 0x15000000)
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
                tvIcon.text = dialogIcon
            }
        }

        val iconClickableView = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            addView(tvIcon); addView(ivIconPreview)
            setOnClickListener {
                showAddIconPickerDialog(dialogIcon, hasCustomImage, newId) { emoji, isCustom ->
                    dialogIcon = emoji; hasCustomImage = isCustom
                    refreshIconButton()
                }
            }
        }

        val nameInput = EditText(this).apply {
            hint = "分类名称"; id = android.R.id.edit
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        }

        root.addView(iconClickableView)
        root.addView(nameInput)

        val title = if (selectedType == "expense") "新增支出分类" else "新增收入分类"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("确定") { dialog, _ ->
                val editText = (dialog as AlertDialog).findViewById<EditText>(android.R.id.edit)
                val name = editText?.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    val icon = if (hasCustomImage) "📷" else dialogIcon
                    viewModel.addCategory(name, selectedType, icon, newId)
                    selectedCategoryId = newId
                    pendingCategoryId = newId
                    pendingNewCategory = com.example.ledger.data.model.Category(id = newId, name = name, type = selectedType, icon = icon)
                    highlightSelectedChip()
                    val base = if (selectedType == "expense")
                        viewModel.usedExpenseCategories.value ?: emptyList()
                    else
                        viewModel.usedIncomeCategories.value ?: emptyList()
                    submitCategoriesWithPending(base)
                    Toast.makeText(this, "分类已添加，已自动选中", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ -> deleteCustomIcon(newId) }
            .show()
    }

    private fun showAddIconPickerDialog(
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
            setColor((ThemeManager.getColors(this@InputActivity).primary and 0x00FFFFFF) or 0x30000000)
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
            addCellView?.let { cell ->
                cell.background = if (isCustomImage) selectedBg else null
            }
        }

        var row: android.widget.LinearLayout? = null
        categoryIcons.forEachIndexed { index, emoji ->
            if (index % columns == 0) {
                row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                root.addView(row)
            }
            val tv = TextView(this).apply {
                text = emoji; textSize = 28f; gravity = Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0, cellSize, 1f).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener {
                    selectedEmoji = emoji; isCustomImage = false
                    updateHighlights()
                }
            }
            iconViews.add(tv); row!!.addView(tv)
        }
        // ➕
        if (categoryIcons.size % columns == 0) {
            row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            root.addView(row)
        }
        val addCell = TextView(this).apply {
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

    // ---- Inner Adapter ----

    class CategoryChipAdapter(
        private val onCategoryClick: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryChipAdapter.ViewHolder>() {

        private var categories: List<Category> = emptyList()
        var selectedId: String? = null
            set(value) {
                field = value
                if (categories.isNotEmpty()) notifyItemRangeChanged(0, categories.size)
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_chip, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(categories[position])
        }

        override fun getItemCount(): Int = categories.size

        fun submitList(list: List<Category>) {
            categories = list
            if (list.isNotEmpty()) notifyItemRangeChanged(0, list.size)
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvIcon: TextView = itemView.findViewById(R.id.tv_chip_icon)
            private val ivCustom: ImageView = itemView.findViewById(R.id.iv_chip_custom)
            private val tvName: TextView = itemView.findViewById(R.id.tv_chip_name)

            fun bind(category: Category) {
                val customFile = File(itemView.context.filesDir, "category_icons/${category.id}.png")
                if (customFile.exists()) {
                    tvIcon.visibility = View.GONE
                    ivCustom.visibility = View.VISIBLE
                    ivCustom.setImageBitmap(BitmapFactory.decodeFile(customFile.absolutePath))
                } else {
                    ivCustom.visibility = View.GONE
                    tvIcon.visibility = View.VISIBLE
                    tvIcon.text = category.icon
                }
                tvName.text = category.name
                tvName.setTextColor(
                    if (category.id == selectedId)
                        android.graphics.Color.WHITE
                    else
                        ThemeManager.getColors(tvName.context).onSurface
                )
                itemView.isSelected = category.id == selectedId
                itemView.setBackgroundResource(
                    if (category.id == selectedId)
                        R.drawable.bg_category_chip_selected
                    else
                        R.drawable.bg_category_chip
                )
                itemView.setOnClickListener { onCategoryClick(category) }
            }
        }
    }
}
