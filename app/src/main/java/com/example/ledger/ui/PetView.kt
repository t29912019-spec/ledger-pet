package com.example.ledger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class PetView(context: Context) : View(context) {

    enum class Pose(val index: Int) {
        EATING(0),
        SLEEPING(1),
        TOILET(2),
        SITTING(3),
        PEEKING(4),
        HIDING(5),
        WALKING(6),
        HANGING(7),
        WAVING(8);

        companion object {
            fun fromIndex(i: Int): Pose = entries.firstOrNull { it.index == i } ?: SITTING
        }
    }

    enum class Edge { LEFT, RIGHT, BOTTOM }

    var spriteSheet: PetSpriteSheet? = null
    var currentPose: Pose = Pose.SITTING
        set(value) {
            field = value
            invalidate()
        }
    var mirror: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var edge: Edge = Edge.BOTTOM
        set(value) {
            field = value
            invalidate()
        }
    var isCompact: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var petColor = 0xFFFF9800.toInt()
    var strokeColor = 0xFFE65100.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.FILL
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.FILL
    }
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8800BCD4.toInt(); style = Paint.Style.FILL
    }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7070.toInt(); style = Paint.Style.FILL
    }

    // Pre-allocated RectF to avoid allocations during onDraw
    private val bitmapDest = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = spriteSheet?.getPose(currentPose.index)
        if (bitmap != null) {
            bitmapDest.set(0f, 0f, width.toFloat(), height.toFloat())
            if (mirror) {
                canvas.save()
                canvas.scale(-1f, 1f, width / 2f, height / 2f)
                canvas.drawBitmap(bitmap, null, bitmapDest, null)
                canvas.restore()
            } else {
                canvas.drawBitmap(bitmap, null, bitmapDest, null)
            }
        } else {
            drawFallback(canvas)
        }
    }

    // ========================================================================
    // Canvas 回退 — 每种姿势有不同的表情
    // ========================================================================

    private fun drawFallback(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f
        if (r <= 0) return

        // compact 模式下将圆心向吸附边偏移 3/4，仅露出 1/4
        val (dcx, dcy) = if (isCompact) {
            val offset = r * 1.5f
            when (edge) {
                Edge.BOTTOM -> cx to (cy + offset)
                Edge.LEFT   -> (cx - offset) to cy
                Edge.RIGHT  -> (cx + offset) to cy
            }
        } else {
            cx to cy
        }

        // Body
        fillPaint.color = petColor
        strokePaint.color = strokeColor
        if (currentPose == Pose.HANGING) {
            val bw = r * 0.65f; val bh = r * 1.15f
            canvas.drawOval(RectF(dcx - bw, dcy - bh, dcx + bw, dcy + bh), fillPaint)
            canvas.drawOval(RectF(dcx - bw, dcy - bh, dcx + bw, dcy + bh), strokePaint)
        } else {
            canvas.drawCircle(dcx, dcy, r, fillPaint)
            canvas.drawCircle(dcx, dcy, r, strokePaint)
        }

        // compact 模式不画五官；非 compact 画对应表情
        if (!isCompact) {
            when (currentPose) {
                Pose.SITTING, Pose.WALKING -> drawSittingFace(canvas, dcx, dcy, r)
                Pose.SLEEPING -> drawSleepingFace(canvas, dcx, dcy, r)
                Pose.HIDING, Pose.PEEKING -> drawPeekingFace(canvas, dcx, dcy, r)
                Pose.EATING -> drawEatingFace(canvas, dcx, dcy, r)
                Pose.TOILET -> drawToiletFace(canvas, dcx, dcy, r)
                Pose.WAVING -> drawWavingFace(canvas, dcx, dcy, r)
                Pose.HANGING -> drawHangingFace(canvas, dcx, dcy, r)
            }
        }
    }

    // ---- SITTING / WALKING: 正常笑脸 ----

    private fun drawSittingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.2f
        drawEye(canvas, cx - r * 0.28f, eyeY, r * 0.12f)
        drawEye(canvas, cx + r * 0.28f, eyeY, r * 0.12f)
        // blush
        blushPaint.color = 0x55FF4081.toInt()
        canvas.drawCircle(cx - r * 0.4f, cy + r * 0.08f, r * 0.1f, blushPaint)
        canvas.drawCircle(cx + r * 0.4f, cy + r * 0.08f, r * 0.1f, blushPaint)
        // smile
        drawMouthSmile(canvas, cx, cy + r * 0.15f, r * 0.18f)
    }

    // ---- SLEEPING: 闭眼 + Zzz ----

    private fun drawSleepingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.15f
        val halfW = r * 0.22f
        darkPaint.strokeWidth = r * 0.08f
        darkPaint.style = Paint.Style.STROKE
        canvas.drawLine(cx - r * 0.28f - halfW, eyeY, cx - r * 0.28f + halfW, eyeY, darkPaint)
        canvas.drawLine(cx + r * 0.28f - halfW, eyeY, cx + r * 0.28f + halfW, eyeY, darkPaint)
        darkPaint.style = Paint.Style.FILL
        // 打鼾小嘴
        canvas.drawCircle(cx, cy + r * 0.28f, r * 0.07f, darkPaint)
        // Zzz
        textPaint.textSize = r * 0.5f
        canvas.drawText("Z", cx + r * 0.4f, cy - r * 0.5f, textPaint)
        textPaint.textSize = r * 0.35f
        canvas.drawText("z", cx + r * 0.65f, cy - r * 0.2f, textPaint)
    }

    // ---- EATING: ^^眼 + 张大嘴 ----

    private fun drawEatingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 开心弧线眼 ^ ^
        darkPaint.strokeWidth = r * 0.08f
        darkPaint.style = Paint.Style.STROKE
        val eyeY = cy - r * 0.22f
        val eyeR = r * 0.14f
        drawHappyEye(canvas, cx - r * 0.28f, eyeY, eyeR)
        drawHappyEye(canvas, cx + r * 0.28f, eyeY, eyeR)
        darkPaint.style = Paint.Style.FILL
        // 张嘴
        val mw = r * 0.22f; val mh = r * 0.32f
        val mouthRect = RectF(cx - mw, cy + r * 0.08f, cx + mw, cy + r * 0.08f + mh)
        darkPaint.color = 0xFF333333.toInt()
        canvas.drawOval(mouthRect, darkPaint)
        // 舌头
        canvas.drawOval(RectF(cx - mw * 0.5f, cy + r * 0.2f, cx + mw * 0.5f, cy + r * 0.08f + mh + r * 0.04f), tonguePaint)
    }

    // ---- TOILET: 小点眼 + 波浪嘴 + 汗滴 ----

    private fun drawToiletFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 小点眼睛
        val eyeY = cy - r * 0.18f
        canvas.drawCircle(cx - r * 0.28f, eyeY, r * 0.06f, darkPaint)
        canvas.drawCircle(cx + r * 0.28f, eyeY, r * 0.06f, darkPaint)
        // 汗滴
        canvas.drawCircle(cx + r * 0.55f, cy - r * 0.55f, r * 0.1f, sweatPaint)
        // 波浪嘴
        drawWavyMouth(canvas, cx, cy + r * 0.22f, r * 0.15f, r * 0.08f)
        // 腮红
        blushPaint.color = 0x55FF4081.toInt()
        canvas.drawCircle(cx - r * 0.38f, cy + r * 0.05f, r * 0.08f, blushPaint)
        canvas.drawCircle(cx + r * 0.38f, cy + r * 0.05f, r * 0.08f, blushPaint)
    }

    // ---- PEEKING: 大眼 + 好奇小嘴 ----

    private fun drawPeekingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.2f
        val eyeR = r * 0.15f
        // 白色大眼
        canvas.drawCircle(cx - r * 0.28f, eyeY, eyeR, whitePaint)
        canvas.drawCircle(cx + r * 0.28f, eyeY, eyeR, whitePaint)
        // 瞳孔偏中心
        canvas.drawCircle(cx - r * 0.28f, eyeY - r * 0.02f, eyeR * 0.5f, darkPaint)
        canvas.drawCircle(cx + r * 0.28f, eyeY - r * 0.02f, eyeR * 0.5f, darkPaint)
        // 高光
        canvas.drawCircle(cx - r * 0.2f, eyeY - eyeR * 0.35f, eyeR * 0.22f, whitePaint)
        canvas.drawCircle(cx + r * 0.36f, eyeY - eyeR * 0.35f, eyeR * 0.22f, whitePaint)
        // 小嘴
        drawMouthSmile(canvas, cx, cy + r * 0.15f, r * 0.12f)
    }

    // ---- HIDING: 单眼 + 极小嘴（半边隐藏的胆怯表情） ----

    private fun drawHidingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.18f
        val eyeR = r * 0.1f
        // 只画可见侧的眼（右侧），左侧只留一点痕迹
        canvas.drawCircle(cx + r * 0.2f, eyeY + r * 0.05f, eyeR, whitePaint)
        canvas.drawCircle(cx + r * 0.2f, eyeY + r * 0.03f, eyeR * 0.5f, darkPaint)
        canvas.drawCircle(cx + r * 0.26f, eyeY - r * 0.02f, eyeR * 0.2f, whitePaint)
        // 微小嘴
        drawMouthSmile(canvas, cx + r * 0.1f, cy + r * 0.2f, r * 0.08f)
        // 腮红（胆怯）
        blushPaint.color = 0x66FF4081.toInt()
        canvas.drawCircle(cx + r * 0.35f, cy + r * 0.08f, r * 0.08f, blushPaint)
    }

    // ---- WAVING: 闪亮大眼 + 大笑 ----

    private fun drawWavingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.2f
        val eyeR = r * 0.13f
        // 正常眼 + 额外高光
        drawEye(canvas, cx - r * 0.28f, eyeY, eyeR)
        drawEye(canvas, cx + r * 0.28f, eyeY, eyeR)
        // 额外星星高光
        canvas.drawCircle(cx - r * 0.22f, eyeY - eyeR * 0.4f, eyeR * 0.15f, whitePaint)
        canvas.drawCircle(cx + r * 0.34f, eyeY - eyeR * 0.4f, eyeR * 0.15f, whitePaint)
        // 腮红
        blushPaint.color = 0x55FF4081.toInt()
        canvas.drawCircle(cx - r * 0.4f, cy + r * 0.08f, r * 0.1f, blushPaint)
        canvas.drawCircle(cx + r * 0.4f, cy + r * 0.08f, r * 0.1f, blushPaint)
        // 大笑
        drawMouthSmile(canvas, cx, cy + r * 0.12f, r * 0.22f)
    }

    // ---- HANGING: 向下看 + 微张嘴 ----

    private fun drawHangingFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.22f
        val eyeR = r * 0.12f
        // 眼睛向下看（瞳孔偏下）
        canvas.drawCircle(cx - r * 0.28f, eyeY, eyeR, whitePaint)
        canvas.drawCircle(cx + r * 0.28f, eyeY, eyeR, whitePaint)
        canvas.drawCircle(cx - r * 0.28f, eyeY + eyeR * 0.25f, eyeR * 0.5f, darkPaint)
        canvas.drawCircle(cx + r * 0.28f, eyeY + eyeR * 0.25f, eyeR * 0.5f, darkPaint)
        canvas.drawCircle(cx - r * 0.22f, eyeY - eyeR * 0.3f, eyeR * 0.2f, whitePaint)
        canvas.drawCircle(cx + r * 0.34f, eyeY - eyeR * 0.3f, eyeR * 0.2f, whitePaint)
        // 微张嘴（小圆）
        val mo = r * 0.1f
        darkPaint.style = Paint.Style.FILL
        canvas.drawOval(RectF(cx - mo * 0.6f, cy + r * 0.15f, cx + mo * 0.6f, cy + r * 0.15f + mo * 1.5f), darkPaint)
    }

    // ========================================================================
    // 面部组件
    // ========================================================================

    private fun drawEye(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, whitePaint)
        canvas.drawCircle(x, y - r * 0.05f, r * 0.55f, darkPaint)
        canvas.drawCircle(x + r * 0.3f, y - r * 0.3f, r * 0.22f, whitePaint)
    }

    private fun drawHappyEye(canvas: Canvas, x: Float, y: Float, r: Float) {
        val path = Path().apply {
            moveTo(x - r, y + r * 0.2f)
            quadTo(x, y - r, x + r, y + r * 0.2f)
        }
        canvas.drawPath(path, darkPaint)
    }

    private fun drawMouthSmile(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val mouthRect = RectF(cx - r, cy, cx + r, cy + r * 2)
        darkPaint.strokeWidth = r * 0.4f
        darkPaint.style = Paint.Style.STROKE
        canvas.drawArc(mouthRect, 0f, 180f, false, darkPaint)
        darkPaint.style = Paint.Style.FILL
    }

    private fun drawWavyMouth(canvas: Canvas, cx: Float, cy: Float, r: Float, w: Float) {
        // 波浪线 ~~~
        darkPaint.strokeWidth = w
        darkPaint.style = Paint.Style.STROKE
        val dx = r * 0.4f
        val path = Path().apply {
            moveTo(cx - r, cy)
            quadTo(cx - dx, cy - r * 0.6f, cx, cy)
            quadTo(cx + dx, cy + r * 0.6f, cx + r, cy)
        }
        canvas.drawPath(path, darkPaint)
        darkPaint.style = Paint.Style.FILL
    }
}
