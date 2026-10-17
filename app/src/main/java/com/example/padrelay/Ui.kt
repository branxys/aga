package com.example.padrelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.max
import kotlin.math.min

/** Small helpers to build the minimal dark UI in code. */
object Ui {
    val BG = Color.parseColor("#0D0E11")
    val SURFACE = Color.parseColor("#16181D")
    val SURFACE2 = Color.parseColor("#20232B")
    val TEXT = Color.parseColor("#ECEEF2")
    val MUTED = Color.parseColor("#8B91A1")
    val ACCENT = Color.parseColor("#5B8CFF")
    val GOOD = Color.parseColor("#3DDC97")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun text(ctx: Context, s: String, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 18, ctx)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(ctx, 12) }
    }

    /** A rounded, clickable text "button". */
    fun pill(ctx: Context, label: String, bg: Int = SURFACE2, fg: Int = TEXT, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = rounded(bg, 14, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun style(v: TextView, bg: Int, fg: Int) {
        v.background = rounded(bg, 14, v.context)
        v.setTextColor(fg)
    }

    fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    fun field(ctx: Context, hintText: String, value: String): EditText = EditText(ctx).apply {
        hint = hintText
        setText(value)
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        textSize = 15f
        background = rounded(SURFACE2, 12, ctx)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        setSingleLine()
    }

    class Slider(val view: View, val set: (Float) -> Unit)

    fun slider(
        ctx: Context, label: String, lo: Float, hi: Float, value: Float,
        fmt: (Float) -> String, onChange: (Float) -> Unit, onDone: () -> Unit
    ): Slider {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 2))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val name = text(ctx, label, 14f, MUTED)
        val shown = text(ctx, fmt(value), 14f, TEXT)
        head.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(shown)

        val bar = SeekBar(ctx)
        bar.max = 1000
        bar.progress = (((value - lo) / (hi - lo)) * 1000f).toInt().coerceIn(0, 1000)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val x = lo + (hi - lo) * progress / 1000f
                shown.text = fmt(x)
                if (fromUser) onChange(x)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { onDone() }
        })
        root.addView(head)
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        val set: (Float) -> Unit = { x ->
            bar.progress = (((x - lo) / (hi - lo)) * 1000f).toInt().coerceIn(0, 1000)
            shown.text = fmt(x)
        }
        return Slider(root, set)
    }

    class Toggle(val view: View, val sw: SwitchCompat, val set: (Boolean) -> Unit)

    fun toggle(ctx: Context, label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): Toggle {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ctx, label, 15f, TEXT))
        if (sub != null) col.addView(text(ctx, sub, 12f, MUTED))
        val sw = SwitchCompat(ctx)
        var silent = true
        sw.isChecked = checked
        silent = false
        sw.setOnCheckedChangeListener { _, on -> if (!silent) onChange(on) }
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(sw)
        val set: (Boolean) -> Unit = { b ->
            silent = true
            sw.isChecked = b
            silent = false
        }
        return Toggle(row, sw, set)
    }
}

/** Shows one stick: deadzone and outer-limit rings, the raw input (grey) and what the PC receives (blue). */
class StickPreview(ctx: Context, private val isLeftStick: Boolean) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rawX = 0f
    private var rawY = 0f
    private var outX = 0f
    private var outY = 0f

    fun set(rx: Float, ry: Float, ox: Float, oy: Float) {
        rawX = rx; rawY = ry; outX = ox; outY = oy
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cc = Tuning.cfgFor(Tuning.editing)
        val c = if (isLeftStick) cc.left else cc.right
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - Ui.dp(context, 6)

        paint.style = Paint.Style.FILL
        paint.color = Ui.SURFACE2
        canvas.drawCircle(cx, cy, r, paint)

        // inner deadzone
        paint.color = Color.argb(60, 255, 255, 255)
        if (c.radial) {
            canvas.drawCircle(cx, cy, r * c.inner, paint)
        } else {
            canvas.drawRect(cx - r * c.inner, cy - r * c.inner, cx + r * c.inner, cy + r * c.inner, paint)
        }

        // outer limit
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(context, 1).toFloat()
        paint.color = Color.argb(140, 91, 140, 255)
        if (c.radial) {
            canvas.drawCircle(cx, cy, r * c.outer, paint)
        } else {
            canvas.drawRect(cx - r * c.outer, cy - r * c.outer, cx + r * c.outer, cy + r * c.outer, paint)
        }

        // raw input and processed output
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawCircle(cx + rawX * r, cy - rawY * r, Ui.dp(context, 5).toFloat(), paint)
        paint.color = Ui.ACCENT
        canvas.drawCircle(cx + outX * r, cy - outY * r, Ui.dp(context, 6).toFloat(), paint)
    }
}

/** Shows one trigger: start and max markers, raw pull (grey) and processed output (blue). */
class BarPreview(ctx: Context, private val isLeftTrigger: Boolean) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rawV = 0f
    private var outV = 0f

    fun set(raw: Float, out: Float) {
        rawV = raw; outV = out
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cc = Tuning.cfgFor(Tuning.editing)
        val c = if (isLeftTrigger) cc.lt else cc.rt
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2f

        paint.style = Paint.Style.FILL
        paint.color = Ui.SURFACE2
        canvas.drawRoundRect(0f, 0f, w, h, rad, rad, paint)

        paint.color = Color.argb(90, 255, 255, 255)
        if (rawV > 0f) canvas.drawRoundRect(0f, 0f, max(w * rawV, h), h, rad, rad, paint)
        paint.color = Ui.ACCENT
        if (outV > 0f) canvas.drawRoundRect(0f, h * 0.25f, max(w * outV, h * 0.5f), h * 0.75f, rad, rad, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(context, 1).toFloat()
        paint.color = Ui.TEXT
        canvas.drawLine(w * c.start, 0f, w * c.start, h, paint)
        canvas.drawLine(w * c.end, 0f, w * c.end, h, paint)
    }
}
