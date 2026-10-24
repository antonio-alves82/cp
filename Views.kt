package com.classicplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// =====================================================================================
//  Modelo de linha de lista
// =====================================================================================

class Item(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val chevron: Boolean = true,
    val key: String? = null
)

// =====================================================================================
//  Barra de título (estilo iPod: degradê claro, título central, play à esquerda, bateria à direita)
// =====================================================================================

class TitleBarView(ctx: Context) : View(ctx) {

    var title: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 0 = nada, 1 = tocando, 2 = pausado */
    var playState: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var batteryPct: Int = 100
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var charging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val bgPaint = Paint()
    private val linePaint = Paint().apply { color = 0xFFA9AFBA.toInt() }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1E.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = spf(16)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1)
        color = Ui.SECONDARY
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dpi(34))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFFFBFBFD.toInt(), 0xFFD3D8E0.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        c.drawRect(0f, 0f, w, h, bgPaint)
        c.drawRect(0f, h - 1f, w, h, linePaint)

        // indicador de reprodução
        val lx = dpf(12)
        if (playState == 1) {
            fill.color = Ui.BLUE
            path.reset()
            path.moveTo(lx, cy - dpf(5.5f))
            path.lineTo(lx + dpf(9), cy)
            path.lineTo(lx, cy + dpf(5.5f))
            path.close()
            c.drawPath(path, fill)
        } else if (playState == 2) {
            fill.color = Ui.SECONDARY
            c.drawRect(lx, cy - dpf(5.5f), lx + dpf(3), cy + dpf(5.5f), fill)
            c.drawRect(lx + dpf(6), cy - dpf(5.5f), lx + dpf(9), cy + dpf(5.5f), fill)
        }

        // bateria
        val bw = dpf(24)
        val bh = dpf(11)
        val left = w - dpf(14) - bw
        val body = RectF(left, cy - bh / 2f, left + bw, cy + bh / 2f)
        c.drawRoundRect(body, dpf(2.5f), dpf(2.5f), stroke)
        val inner = RectF(body.left + dpf(1.8f), body.top + dpf(1.8f), body.right - dpf(1.8f), body.bottom - dpf(1.8f))
        val frac = batteryPct.coerceIn(0, 100) / 100f
        inner.right = inner.left + inner.width() * frac
        fill.color = if (charging || batteryPct > 20) Ui.GREEN else Ui.RED
        c.drawRoundRect(inner, dpf(1), dpf(1), fill)
        fill.color = Ui.SECONDARY
        c.drawRect(body.right + dpf(1), cy - dpf(2.2f), body.right + dpf(2.6f), cy + dpf(2.2f), fill)

        // título
        val maxW = w - dpf(96)
        val text = TextUtils.ellipsize(title, titlePaint, maxW, TextUtils.TruncateAt.END).toString()
        val fm = titlePaint.fontMetrics
        c.drawText(text, w / 2f, cy - (fm.ascent + fm.descent) / 2f, titlePaint)
    }
}

// =====================================================================================
//  Linha de lista (estilo iOS: 17sp, chevron cinza, seleção em azul com degradê)
// =====================================================================================

class RowView(ctx: Context) : View(ctx) {

    private var item: Item? = null
    private var highlighted = false
    private var marker = false

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = spf(17) }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = spf(12.5f) }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = spf(16) }
    private val sepPaint = Paint().apply { color = Ui.SEPARATOR }
    private val hiPaint = Paint()
    private val chevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun bind(i: Item, hi: Boolean, mk: Boolean) {
        item = i
        highlighted = hi
        marker = mk
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        hiPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFF5BB0FF.toInt(), 0xFF0B6FE6.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val row = item ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val pad = dpf(16)

        if (highlighted) {
            c.drawRect(0f, 0f, w, h, hiPaint)
        } else {
            c.drawRect(pad, h - 1f, w, h, sepPaint)
        }

        val mainColor = if (highlighted) Color.WHITE else Ui.LABEL
        val subColor = if (highlighted) 0xFFDCEBFF.toInt() else Ui.SECONDARY
        val accent = if (highlighted) Color.WHITE else Ui.BLUE

        var right = w - pad

        if (row.chevron) {
            chevPaint.color = if (highlighted) Color.WHITE else Ui.CHEVRON
            val tipX = w - pad + dpf(2)
            path.reset()
            path.moveTo(tipX - dpf(6), cy - dpf(6.5f))
            path.lineTo(tipX, cy)
            path.lineTo(tipX - dpf(6), cy + dpf(6.5f))
            c.drawPath(path, chevPaint)
            right = tipX - dpf(6) - dpf(12)
        }

        if (marker) {
            fill.color = accent
            path.reset()
            path.moveTo(right - dpf(9), cy - dpf(6))
            path.lineTo(right, cy)
            path.lineTo(right - dpf(9), cy + dpf(6))
            path.close()
            c.drawPath(path, fill)
            right -= dpf(20)
        }

        val value = row.value
        if (value != null) {
            valuePaint.color = if (highlighted) 0xFFDCEBFF.toInt() else Ui.SECONDARY
            val vw = valuePaint.measureText(value)
            val fmv = valuePaint.fontMetrics
            c.drawText(value, right - vw, cy - (fmv.ascent + fmv.descent) / 2f, valuePaint)
            right -= vw + dpf(10)
        }

        val avail = (right - pad).coerceAtLeast(dpf(40))
        titlePaint.color = mainColor
        val titleText = TextUtils.ellipsize(row.title, titlePaint, avail, TextUtils.TruncateAt.END).toString()

        val sub = row.subtitle
        if (sub == null) {
            val fm = titlePaint.fontMetrics
            c.drawText(titleText, pad, cy - (fm.ascent + fm.descent) / 2f, titlePaint)
        } else {
            subPaint.color = subColor
            val subText = TextUtils.ellipsize(sub, subPaint, avail, TextUtils.TruncateAt.END).toString()
            val fm = titlePaint.fontMetrics
            val fs = subPaint.fontMetrics
            c.drawText(titleText, pad, h * 0.42f - (fm.ascent + fm.descent) / 2f, titlePaint)
            c.drawText(subText, pad, h * 0.77f - (fs.ascent + fs.descent) / 2f, subPaint)
        }
    }
}

class ItemAdapter(private val ctx: Context) : BaseAdapter() {

    var items: List<Item> = emptyList()
    var selected = 0
    var rowHeight = 0
    var currentUri: String? = null

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = (convertView as? RowView) ?: RowView(ctx)
        v.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
        val it = items[position]
        val isCurrent = it.key != null && it.key == currentUri
        v.bind(it, position == selected, isCurrent)
        return v
    }
}

// =====================================================================================
//  Click wheel (anel giratório + botão central + MENU / ⏮ / ⏭ / ⏯)
// =====================================================================================

class ClickWheelView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onRotate(steps: Int)
        fun onCenter()
        fun onMenu()
        fun onNext()
        fun onPrev()
        fun onPlayPause()
    }

    var listener: Listener? = null

    private val STEP_DEG = 20.0

    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f
    private var innerR = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1)
        color = 0xFFB8B8BF.toInt()
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1)
        color = 0xFFC7C7CC.toInt()
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C6C70.toInt() }
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6C6C70.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = spf(13)
        letterSpacing = 0.1f
    }
    private val path = Path()

    private var tracking = false
    private var inCenter = false
    private var lastAngle = 0.0
    private var downAngle = 0.0
    private var accum = 0.0
    private var travelled = 0.0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        outerR = min(w, h) / 2f - dpf(3)
        innerR = outerR * 0.36f
        ringPaint.shader = LinearGradient(
            0f, cy - outerR, 0f, cy + outerR,
            0xFFF6F6FA.toInt(), 0xFFD5D5DB.toInt(), Shader.TileMode.CLAMP
        )
        centerPaint.shader = LinearGradient(
            0f, cy - innerR, 0f, cy + innerR,
            0xFFFFFFFF.toInt(), 0xFFEDEDF2.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, outerR, ringPaint)
        c.drawCircle(cx, cy, outerR, ringStroke)
        c.drawCircle(cx, cy, innerR, centerPaint)
        c.drawCircle(cx, cy, innerR, centerStroke)

        val d = outerR * 0.70f
        val fm = menuPaint.fontMetrics
        c.drawText("MENU", cx, cy - d - (fm.ascent + fm.descent) / 2f, menuPaint)

        val u = outerR * 0.07f
        drawSkip(c, cx + d, cy, u, true)
        drawSkip(c, cx - d, cy, u, false)
        drawPlayPause(c, cx, cy + d, u)
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, u: Float, forward: Boolean) {
        c.save()
        if (!forward) c.scale(-1f, 1f, x, y)
        val x0 = x - 1.35f * u
        path.reset()
        path.moveTo(x0, y - u)
        path.lineTo(x0 + 1.1f * u, y)
        path.lineTo(x0, y + u)
        path.close()
        path.moveTo(x0 + 1.1f * u, y - u)
        path.lineTo(x0 + 2.2f * u, y)
        path.lineTo(x0 + 1.1f * u, y + u)
        path.close()
        c.drawPath(path, glyph)
        c.drawRect(x0 + 2.3f * u, y - u, x0 + 2.7f * u, y + u, glyph)
        c.restore()
    }

    private fun drawPlayPause(c: Canvas, x: Float, y: Float, u: Float) {
        val x0 = x - 1.3f * u
        path.reset()
        path.moveTo(x0, y - u)
        path.lineTo(x0 + 1.3f * u, y)
        path.lineTo(x0, y + u)
        path.close()
        c.drawPath(path, glyph)
        c.drawRect(x0 + 1.7f * u, y - u, x0 + 2.0f * u, y + u, glyph)
        c.drawRect(x0 + 2.3f * u, y - u, x0 + 2.6f * u, y + u, glyph)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = (e.x - cx).toDouble()
        val dy = (e.y - cy).toDouble()
        val r = hypot(dx, dy)
        val ang = Math.toDegrees(atan2(dy, dx))

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (r > outerR + dpf(6)) return false
                tracking = true
                inCenter = r <= innerR
                lastAngle = ang
                downAngle = ang
                accum = 0.0
                travelled = 0.0
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (inCenter) {
                    if (r > innerR * 1.25) {
                        inCenter = false
                        lastAngle = ang
                    }
                    return true
                }
                if (r < innerR * 0.5) return true
                var d = ang - lastAngle
                if (d > 180) d -= 360 else if (d < -180) d += 360
                lastAngle = ang
                travelled += abs(d)
                accum += d
                while (accum >= STEP_DEG) {
                    accum -= STEP_DEG
                    tick(1)
                }
                while (accum <= -STEP_DEG) {
                    accum += STEP_DEG
                    tick(-1)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (tracking && travelled < 10.0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (inCenter) {
                        listener?.onCenter()
                    } else {
                        val a = downAngle
                        when {
                            a > -135 && a < -45 -> listener?.onMenu()
                            a >= -45 && a <= 45 -> listener?.onNext()
                            a > 45 && a < 135 -> listener?.onPlayPause()
                            else -> listener?.onPrev()
                        }
                    }
                }
                tracking = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun tick(dir: Int) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        listener?.onRotate(dir)
    }
}

// =====================================================================================
//  Tela "Reproduzindo" (capa à esquerda, textos à direita, barra de progresso/volume)
// =====================================================================================

class NowPlayingView(ctx: Context) : View(ctx) {

    private var track: Track? = null
    private var art: Bitmap? = null
    private var pos = 0
    private var dur = 0
    private var shuffle = false
    private var repeat = 0
    private var volume = 0f
    private var volumeVisible = false

    var scrub: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.LABEL
        typeface = Typeface.DEFAULT_BOLD
        textSize = spf(17)
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.LABEL
        textSize = spf(15)
    }
    private val albumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.SECONDARY
        textSize = spf(15)
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.SECONDARY
        typeface = Typeface.DEFAULT_BOLD
        textSize = spf(12)
    }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.BLUE
        textSize = spf(11)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1)
        color = Ui.SEPARATOR
    }
    private val barBlue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderIcon = context.getDrawable(R.drawable.ic_stat_music)?.mutate()
    private val src = Rect()

    fun update(t: Track?, a: Bitmap?, p: Int, d: Int, sh: Boolean, rp: Int) {
        track = t
        art = a
        pos = p
        dur = d
        shuffle = sh
        repeat = rp
        invalidate()
    }

    fun showVolume(v: Float, visible: Boolean) {
        volume = v
        volumeVisible = visible
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dpf(14)
        c.drawColor(Color.WHITE)

        val t = track
        if (t == null) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Ui.SECONDARY
                textSize = spf(16)
                textAlign = Paint.Align.CENTER
            }
            c.drawText("Nenhuma música", w / 2f, h / 2f, p)
            return
        }

        // capa
        val artSize = min(w * 0.40f, h * 0.46f)
        val artRect = RectF(pad, pad, pad + artSize, pad + artSize)
        val radius = dpf(6)
        val bmp = art
        if (bmp != null) {
            val clip = Path()
            clip.addRoundRect(artRect, radius, radius, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val side = min(bmp.width, bmp.height)
            src.set((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
            c.drawBitmap(bmp, src, artRect, null)
            c.restore()
        } else {
            fill.color = 0xFFEDEDF2.toInt()
            c.drawRoundRect(artRect, radius, radius, fill)
            val ic = placeholderIcon
            if (ic != null) {
                ic.setTint(0xFFB4B4BC.toInt())
                val s = (artSize * 0.5f).toInt()
                val l = (artRect.centerX() - s / 2f).toInt()
                val tp = (artRect.centerY() - s / 2f).toInt()
                ic.setBounds(l, tp, l + s, tp + s)
                ic.draw(c)
            }
        }
        c.drawRoundRect(artRect, radius, radius, stroke)

        // textos
        val tx = artRect.right + dpf(12)
        val tw = (w - tx - pad).coerceAtLeast(dpf(40))
        var y = artRect.top + dpf(20)
        c.drawText(TextUtils.ellipsize(t.title, titlePaint, tw, TextUtils.TruncateAt.END).toString(), tx, y, titlePaint)
        y += dpf(24)
        c.drawText(TextUtils.ellipsize(t.artist, artistPaint, tw, TextUtils.TruncateAt.END).toString(), tx, y, artistPaint)
        y += dpf(22)
        c.drawText(TextUtils.ellipsize(t.album, albumPaint, tw, TextUtils.TruncateAt.END).toString(), tx, y, albumPaint)

        // barra
        val barLeft = pad
        val barRight = w - pad
        val barTop = h - dpf(40)
        val barH = dpf(8)
        val barRect = RectF(barLeft, barTop, barRight, barTop + barH)
        val timeY = barTop - dpf(8)

        fill.color = 0xFFE5E5EA.toInt()
        c.drawRoundRect(barRect, barH / 2f, barH / 2f, fill)
        c.drawRoundRect(barRect, barH / 2f, barH / 2f, stroke)

        val frac: Float
        if (volumeVisible) {
            frac = volume.coerceIn(0f, 1f)
            val label = "Volume"
            val lw = timePaint.measureText(label)
            c.drawText(label, w / 2f - lw / 2f, timeY, timePaint)
        } else {
            frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            c.drawText(formatTime(pos), barLeft, timeY, timePaint)
            if (dur > 0) {
                val rt = "-" + formatTime(dur - pos)
                c.drawText(rt, barRight - timePaint.measureText(rt), timeY, timePaint)
            }
        }

        val fillRect = RectF(barLeft, barTop, barLeft + (barRight - barLeft) * frac, barTop + barH)
        if (fillRect.width() > barH) {
            barBlue.shader = LinearGradient(
                0f, barTop, 0f, barTop + barH,
                0xFF5BB0FF.toInt(), 0xFF0B6FE6.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRoundRect(fillRect, barH / 2f, barH / 2f, barBlue)
        }

        if (scrub && !volumeVisible) {
            val kx = barLeft + (barRight - barLeft) * frac
            val ky = barTop + barH / 2f
            fill.color = Color.WHITE
            c.drawCircle(kx, ky, dpf(9), fill)
            stroke.color = Ui.BLUE
            c.drawCircle(kx, ky, dpf(9), stroke)
            stroke.color = Ui.SEPARATOR
        }

        // status
        val statusY = h - dpf(8)
        if (shuffle) c.drawText("Aleatório", barLeft, statusY, statusPaint)
        if (repeat != 0) {
            val rt = if (repeat == 1) "Repetir tudo" else "Repetir faixa"
            c.drawText(rt, barRight - statusPaint.measureText(rt), statusY, statusPaint)
        }
    }
}
