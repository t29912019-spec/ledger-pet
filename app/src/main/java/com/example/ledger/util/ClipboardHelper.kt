package com.example.ledger.util

import android.content.ClipboardManager
import android.content.Context
import java.util.regex.Pattern

object ClipboardHelper {

    private val AMOUNT_PATTERN = Pattern.compile(
        "(?:[¥￥]\\s*)(\\d+(?:\\.\\d{1,2})?)" +
        "|(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块)"
    )

    fun extractAmount(context: Context): Double? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val text = clip.getItemAt(0).text?.toString() ?: return null
        val matcher = AMOUNT_PATTERN.matcher(text)

        if (matcher.find()) {
            val amount = matcher.group(1) ?: matcher.group(2)
            return amount?.toDoubleOrNull()
        }
        return null
    }
}
