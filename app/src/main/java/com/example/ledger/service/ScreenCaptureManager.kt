package com.example.ledger.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * 屏幕截图管理器 — 通过 MediaProjection + ImageReader 捕获屏幕。
 *
 * Android 10 (API 29) 不支持 AccessibilityService.takeScreenshot()，
 * 但 MediaProjection 在 API 21+ 即可用，只需用户授权一次。
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCapture"
    private const val REQUEST_CODE = 9001

    private var cachedIntent: Intent? = null
    private var pendingCallback: ((Bitmap?) -> Unit)? = null

    fun hasPermission(): Boolean = cachedIntent != null

    /**
     * 如果已授权则直接截图，否则先弹出授权页。
     */
    fun captureOrRequest(context: Context, callback: (Bitmap?) -> Unit) {
        if (cachedIntent != null) {
            captureAsync(context, callback)
        } else {
            pendingCallback = callback
            val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // ---- 由 ScreenCaptureActivity 调用 ----

    fun onActivityResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingCallback?.invoke(null)
            pendingCallback = null
            return
        }
        cachedIntent = data
        pendingCallback?.let { cb ->
            pendingCallback = null
            // data 中包含了 MediaProjection 的 Intent，但需要 Activity 上下文才能 getMediaProjection
            // 所以这里用 accessibility service 上下文
            val ctx = LedgerAccessibilityService.instance
            if (ctx != null) {
                captureAsync(ctx, cb)
            } else {
                cb(null)
            }
        }
    }

    // ---- 内部实现 ----

    @Suppress("DEPRECATION")
    private fun captureAsync(context: Context, callback: (Bitmap?) -> Unit) {
        val intent = cachedIntent
        if (intent == null) {
            callback(null)
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpManager.getMediaProjection(Activity.RESULT_OK, intent.clone() as Intent)
        if (mp == null) {
            callback(null)
            return
        }

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        val vDisplay: VirtualDisplay = mp.createVirtualDisplay(
            "screen_capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        val mainHandler = Handler(Looper.getMainLooper())

        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            var bitmap: Bitmap? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null && image.planes.isNotEmpty()) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    buffer.rewind()
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val paddedWidth = width + rowPadding / pixelStride
                    bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap!!.copyPixelsFromBuffer(buffer)

                    if (rowPadding > 0 && bitmap != null) {
                        val cropped = Bitmap.createBitmap(bitmap!!, 0, 0, width, height)
                        bitmap!!.recycle()
                        bitmap = cropped
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ImageReader error", e)
            } finally {
                image?.close()
                imageReader.close()
                vDisplay.release()
                mp.stop()
                callback(bitmap)
            }
        }, mainHandler)
    }
}
