package cat.sergibonet.diagnosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.min

object Pal {
    val BG = 0xFF000000.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val GRAY = 0xFFB4BCC4.toInt()
    val AMBER = 0xFFFFB300.toInt()
    val GREEN = 0xFF3DDC84.toInt()
    val RED = 0xFFFF5252.toInt()
    val PANEL = 0xFF14181C.toInt()
    val DRUM = 0xFF1A1E23.toInt()
    val RING = 0xFF59626B.toInt()
    val BUTTON = 0xFF2B3036.toInt()
    val BUTTON_PRESSED = 0xFF4A535D.toInt()
}

/** Pintor de text amb ajust automàtic a una amplada màxima. */
class Txt(color: Int, bold: Boolean = true) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun draw(
        c: Canvas, s: String, x: Float, y: Float, size: Float,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, alpha: Int = 255
    ) {
        p.textSize = size
        if (maxW > 0f) {
            val w = p.measureText(s)
            if (w > maxW) p.textSize = size * maxW / w
        }
        p.textAlign = align
        p.alpha = alpha
        c.drawText(s, x, y, p)
    }
}

/** Rodes de dígits com un comptaquilòmetres mecànic. */
class DrumPainter(
    private val cellW: Float,
    private val cellH: Float,
    private val gap: Float,
    private val accentLast: Boolean,
    private val dimLeading: Boolean,
    private val decimalBeforeLast: Boolean = false
) {
    /** Espai extra per a la coma decimal, abans de l'última roda. */
    private val sepW = if (decimalBeforeLast) cellW * 0.4f else 0f
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = cellH * 0.78f
        color = Pal.AMBER
    }
    private val bg = Paint().apply { color = Pal.DRUM }
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = cellH * 0.78f
    }
    private val shade = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, cellH,
            intArrayOf(0xF0000000.toInt(), 0x00000000, 0x00000000, 0xF0000000.toInt()),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Pal.RING
    }
    private val baseline = cellH / 2f - (digit.descent() + digit.ascent()) / 2f

    fun width(count: Int): Float = count * cellW + (count - 1) * gap + sepW

    /** `pos`: posicions de les rodes, començant per la de més a l'esquerra. */
    fun draw(c: Canvas, x: Float, y: Float, pos: DoubleArray) {
        if (decimalBeforeLast && pos.size > 1) {
            val last = pos.size - 1
            val sx = x + last * (cellW + gap) + sepW / 2f - gap / 2f
            c.drawText(",", sx, y + baseline, sepPaint)
        }
        for (i in pos.indices) {
            val p = pos[i]
            var higherZero = true
            for (j in 0 until i) if (pos[j] >= 1.0) higherZero = false
            val leading = dimLeading && i < pos.size - 1 && higherZero && p < 1.0
            val cx = x + i * (cellW + gap) + (if (decimalBeforeLast && i == pos.size - 1) sepW else 0f)
            c.save()
            c.translate(cx, y)
            c.clipRect(0f, 0f, cellW, cellH)
            c.drawRect(0f, 0f, cellW, cellH, bg)
            val fl = floor(p)
            val frac = (p - fl).toFloat()
            val d0 = (fl.toLong() % 10L).toInt()
            val d1 = (d0 + 1) % 10
            digit.color = if (accentLast && i == pos.size - 1) Pal.AMBER else Pal.WHITE
            drawDigit(c, d0, baseline - frac * cellH, leading)
            drawDigit(c, d1, baseline + (1f - frac) * cellH, false)
            c.drawRect(0f, 0f, cellW, cellH, shade)
            c.restore()
            c.drawRect(cx, y, cx + cellW, y + cellH, border)
        }
    }

    private fun drawDigit(c: Canvas, d: Int, y: Float, dim: Boolean) {
        digit.alpha = if (dim && d == 0) 70 else 255
        c.drawText(d.toString(), cellW / 2f, y, digit)
    }
}

/** Brúixola dibuixada: la targeta gira i el rumb actual queda a dalt. */
class CompassPainter {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Pal.RING
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.GRAY
        strokeWidth = 3f
    }
    private val tickBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeWidth = 5f
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.AMBER }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.WHITE }
    private val north = Txt(Pal.RED)
    private val cardinal = Txt(Pal.WHITE)
    private val inter = Txt(Pal.GRAY)
    private val path = Path()
    private val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, headingDeg: Float, alpha: Int) {
        tick.alpha = alpha
        tickBig.alpha = alpha
        arrow.alpha = alpha
        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, ring)

        c.save()
        c.rotate(-headingDeg, cx, cy)
        for (i in 0 until 36) {
            val big = i % 3 == 0
            c.save()
            c.rotate(i * 10f, cx, cy)
            c.drawLine(cx, cy - r + 6f, cx, cy - r + (if (big) 30f else 18f), if (big) tickBig else tick)
            c.restore()
        }
        for (i in 0 until 8) {
            val isCardinal = i % 2 == 0
            c.save()
            c.rotate(i * 45f, cx, cy)
            val painter = if (i == 0) north else if (isCardinal) cardinal else inter
            painter.draw(
                c, names[i], cx, cy - r + (if (isCardinal) 80f else 70f),
                if (isCardinal) 52f else 30f, Paint.Align.CENTER, 0f, alpha
            )
            c.restore()
        }
        c.restore()

        // Marcador fix a dalt: indica la direcció de la marxa.
        path.reset()
        path.moveTo(cx - 15f, cy - r - 6f)
        path.lineTo(cx + 15f, cy - r - 6f)
        path.lineTo(cx, cy - r + 24f)
        path.close()
        c.drawPath(path, marker)

        // Fletxa central fixa.
        path.reset()
        path.moveTo(cx, cy - 30f)
        path.lineTo(cx + 18f, cy + 24f)
        path.lineTo(cx, cy + 10f)
        path.lineTo(cx - 18f, cy + 24f)
        path.close()
        c.drawPath(path, arrow)
    }
}

/** Rellotge analògic. */
class ClockPainter {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Pal.RING
    }
    private val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.GRAY
        strokeCap = Paint.Cap.ROUND
    }
    private val handP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val secP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.RED
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
    }
    private val num = Txt(Pal.WHITE)
    private val cal = Calendar.getInstance()

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, timeMs: Long, tz: TimeZone) {
        cal.timeZone = tz
        cal.timeInMillis = timeMs
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val minute = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = cal.get(Calendar.HOUR) + minute / 60f

        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, ring)
        for (i in 0 until 60) {
            val big = i % 5 == 0
            tickP.strokeWidth = if (big) 5f else 2f
            c.save()
            c.rotate(i * 6f, cx, cy)
            c.drawLine(cx, cy - r + 8f, cx, cy - r + (if (big) 26f else 16f), tickP)
            c.restore()
        }
        val ns = r * 0.30f
        num.draw(c, "12", cx, cy - r + 34f + ns * 0.55f, ns, Paint.Align.CENTER)
        num.draw(c, "6", cx, cy + r - 34f + ns * 0.1f, ns, Paint.Align.CENTER)
        num.draw(c, "3", cx + r - 38f, cy + ns * 0.35f, ns, Paint.Align.CENTER)
        num.draw(c, "9", cx - r + 38f, cy + ns * 0.35f, ns, Paint.Align.CENTER)

        c.save()
        c.rotate(hour * 30f, cx, cy)
        handP.strokeWidth = 11f
        c.drawLine(cx, cy + r * 0.10f, cx, cy - r * 0.50f, handP)
        c.restore()

        c.save()
        c.rotate(minute * 6f, cx, cy)
        handP.strokeWidth = 7f
        c.drawLine(cx, cy + r * 0.12f, cx, cy - r * 0.76f, handP)
        c.restore()

        c.save()
        c.rotate(sec * 6f, cx, cy)
        c.drawLine(cx, cy + r * 0.20f, cx, cy - r * 0.84f, secP)
        c.restore()

        c.drawCircle(cx, cy, 9f, secP)
    }
}

/**
 * Pàgina dibuixada amb coordenades virtuals fixes (la pantalla s'hi adapta),
 * amb un bucle d'animació d'uns 30 fotogrames per segon.
 */
abstract class BasePage(ctx: Context, private val vw: Float, private val vh: Float) : View(ctx) {

    /** Segons transcorreguts des del fotograma anterior. */
    protected var dt = 0.03f

    private var lastNs = 0L
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.BUTTON }
    private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Pal.RING
    }
    private val btnTxt = Txt(Pal.WHITE)
    private val labelTxt = Txt(Pal.GRAY, false)
    private val valueTxt = Txt(Pal.WHITE)

    protected abstract fun paintPage(c: Canvas)

    protected open fun onTap(x: Float, y: Float) {}

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        dt = if (lastNs == 0L) 0.03f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastNs = now
        canvas.drawColor(Pal.BG)
        scale = min(width / vw, height / vh)
        offX = (width - vw * scale) / 2f
        offY = (height - vh * scale) / 2f
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        paintPage(canvas)
        canvas.restore()
        postInvalidateDelayed(33L)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            onTap((e.x - offX) / scale, (e.y - offY) / scale)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    protected fun drawButton(c: Canvas, r: RectF, label: String, size: Float = 40f) {
        c.drawRoundRect(r, 18f, 18f, btnFill)
        c.drawRoundRect(r, 18f, 18f, btnStroke)
        btnTxt.draw(c, label, r.centerX(), r.centerY() + size * 0.35f, size, Paint.Align.CENTER, r.width() - 24f)
    }

    /** Fila d'informació: etiqueta a l'esquerra i valor alineat a la dreta. */
    protected fun row(
        c: Canvas, label: String, value: String, y: Float, x0: Float, x1: Float,
        labelSize: Float, valueSize: Float, valueColor: Int = Pal.WHITE
    ) {
        labelTxt.draw(c, label, x0, y, labelSize, Paint.Align.LEFT, (x1 - x0) * 0.55f)
        if (valueColor == Pal.WHITE) {
            valueTxt.draw(c, value, x1, y, valueSize, Paint.Align.RIGHT, (x1 - x0) * 0.62f)
        } else {
            Txt(valueColor).draw(c, value, x1, y, valueSize, Paint.Align.RIGHT, (x1 - x0) * 0.62f)
        }
    }
}
