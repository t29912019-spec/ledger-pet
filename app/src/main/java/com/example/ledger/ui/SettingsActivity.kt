package com.example.ledger.ui

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ledger.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.switchFloating.isChecked = FloatingWindowService.isRunning
        binding.seekBarOpacity.progress = (FloatingWindowService.opacity * 100).toInt()

        // 主题显示
        val currentTheme = ThemeManager.getCurrentTheme(this)
        binding.tvThemeValue.text = ThemeManager.getThemeLabel(currentTheme)

        binding.switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val intent = Intent(this, FloatingWindowService::class.java)
                startForegroundService(intent)
            } else {
                stopService(Intent(this, FloatingWindowService::class.java))
            }
        }

        binding.seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val opacity = progress / 100f
                FloatingWindowService.applyOpacity(opacity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.rowTheme.setOnClickListener { showThemePickerDialog() }
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        val currentTheme = ThemeManager.getCurrentTheme(this)
        binding.tvThemeValue.text = ThemeManager.getThemeLabel(currentTheme)
    }

    private fun showThemePickerDialog() {
        val themes = ThemeManager.Theme.values()
        val current = ThemeManager.getCurrentTheme(this)
        val labels = themes.map { it.label }.toTypedArray()
        val checkedIdx = themes.indexOf(current)
        var picked = if (checkedIdx >= 0) checkedIdx else 0

        AlertDialog.Builder(this)
            .setTitle("选择界面风格")
            .setSingleChoiceItems(labels, picked) { _, which ->
                picked = which
            }
            .setPositiveButton("确定") { _, _ ->
                val newTheme = themes[picked]
                ThemeManager.setTheme(this, newTheme)
                binding.tvThemeValue.text = ThemeManager.getThemeLabel(newTheme)
                applyTheme()
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
}
