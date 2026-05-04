package com.example.ledger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream

/**
 * 九宫格精灵图解析器。
 * 默认布局 (3×3)：
 *   [0] 吃饭   [1] 睡觉   [2] 上厕所
 *   [3] 坐姿   [4] 探头   [5] 躲在墙里
 *   [6] 行走   [7] 挂墙上  [8] 挥手
 */
class PetSpriteSheet(
    val sourceBitmap: Bitmap,
    val rows: Int = 3,
    val cols: Int = 3
) {
    val cellWidth: Int = sourceBitmap.width / cols
    val cellHeight: Int = sourceBitmap.height / rows

    private val cache = mutableMapOf<Int, Bitmap>()

    fun getPose(index: Int): Bitmap? {
        if (index < 0 || index >= rows * cols) return null
        return cache.getOrPut(index) {
            val row = index / cols
            val col = index % cols
            Bitmap.createBitmap(
                sourceBitmap,
                col * cellWidth,
                row * cellHeight,
                cellWidth,
                cellHeight
            )
        }
    }

    fun recycle() {
        cache.values.forEach { it.recycle() }
        cache.clear()
        if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
    }

    companion object {
        fun fromAssets(context: Context, fileName: String = "pet_sprites.png"): PetSpriteSheet? {
            return try {
                val bitmap = BitmapFactory.decodeStream(context.assets.open(fileName))
                if (bitmap != null) PetSpriteSheet(bitmap) else null
            } catch (_: Exception) {
                null
            }
        }

        fun fromUri(context: Context, uri: Uri): PetSpriteSheet? {
            return try {
                val input: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(input)
                input?.close()
                if (bitmap != null) PetSpriteSheet(bitmap) else null
            } catch (_: Exception) {
                null
            }
        }

        fun fromPath(path: String): PetSpriteSheet? {
            return try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) PetSpriteSheet(bitmap) else null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 从 8-10 张独立图片拼接 3×3 精灵图。
         * assets/pet_parts/ 下的文件：
         *   eating.png, sleeping.png, toilet.png, sitting.png,
         *   peeking.png, hiding_right.png, walking.png, hanging.png, waving.png
         * 另存 hiding_left.png 供左侧时翻转使用。
         */
        fun fromParts(context: Context): PetSpriteSheet? {
            return try {
                val assetManager = context.assets
                val nameToIndex = mapOf(
                    "eating" to 0,
                    "sleeping" to 1,
                    "toilet" to 2,
                    "sitting" to 3,
                    "peeking" to 4,
                    "hiding_right" to 5,
                    "walking" to 6,
                    "hanging" to 7,
                    "waving" to 8,
                )
                val loaded = mutableMapOf<Int, Bitmap>()

                for ((name, index) in nameToIndex) {
                    try {
                        val bmp = BitmapFactory.decodeStream(
                            assetManager.open("pet_parts/$name.png")
                        )
                        if (bmp != null) loaded[index] = bmp
                    } catch (_: Exception) {}
                }

                if (loaded.isEmpty()) return null

                // 如果 slot 5 缺失，用 PEEKING 填补
                if (!loaded.containsKey(5) && loaded.containsKey(4)) {
                    loaded[5] = loaded[4]!!.copy(Bitmap.Config.ARGB_8888, false)
                }

                // 确定 cell 尺寸：取所有图的最大宽高
                var maxW = 0
                var maxH = 0
                for ((_, bmp) in loaded) {
                    if (bmp.width > maxW) maxW = bmp.width
                    if (bmp.height > maxH) maxH = bmp.height
                }
                if (maxW <= 0 || maxH <= 0) return null

                val cols = 3
                val rows = 3
                val sheetWidth = maxW * cols
                val sheetHeight = maxH * rows

                val sheet = Bitmap.createBitmap(sheetWidth, sheetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(sheet)

                for ((index, bmp) in loaded) {
                    val col = index % cols
                    val row = index / cols
                    val left = col * maxW + (maxW - bmp.width) / 2f
                    val top = row * maxH + (maxH - bmp.height) / 2f
                    canvas.drawBitmap(bmp, left, top, null)
                }

                PetSpriteSheet(sheet)
            } catch (_: Exception) {
                null
            }
        }
    }
}
