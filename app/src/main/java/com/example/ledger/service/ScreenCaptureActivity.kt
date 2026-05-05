package com.example.ledger.service

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * 透明 Activity — 仅用于弹出 MediaProjection 授权对话框。
 * 授权结果回传给 ScreenCaptureManager 后立即 finish()。
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpManager.createScreenCaptureIntent()
        try {
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Failed to start capture intent", e)
            ScreenCaptureManager.onActivityResult(Activity.RESULT_CANCELED, null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            ScreenCaptureManager.onActivityResult(resultCode, data)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
