package cat.sergibonet.diagnosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pàgina 1: el que cal mirar mentre es condueix. */
class DrivePage(ctx: Context) : BasePage(ctx, 1280f, 520f) {

    private val speedDrum = DrumPainter(170f, 240f, 8f, false, true)
    private val tripDrum = DrumPainter(60f, 84f, 4f, true, true, true)
    private val compass = CompassPainter()
    private val clock = ClockPainter()
    private val white = Txt(Pal.WHITE)
    private val gray = Txt(Pal.GRAY, false)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cal = Calendar.getInstance()

    private var speedDisp = 0.0
    private var headingDisp = 0.0
    private var headingTarget = 0.0
    private var haveHeading = false
    private var sunKey = -1L
    private var sunRise = "--:--"
    private var sunSet = "--:--"

    override fun paintPage(c: Canvas) {
        val fresh = GpsHub.hasFreshFix()
        val raw = GpsHub.speedKmh

        // Velocitat suavitzada: per sota del llindar es considera aturat (0).
        val target = if (fresh && raw >= Cfg.STOP_KMH) min(raw, 999.0) else 0.0
        speedDisp += (target - speedDisp) * (1.0 - exp(-dt / 0.7))
        if (abs(target - speedDisp) < 0.02) speedDisp = target

        // Rumb: només s'actualitza en moviment; parat es manté l'últim.
        val headingOk = fresh && GpsHub.hasBearing && raw >= Cfg.HEADING_MIN_KMH
        if (headingOk) {
            headingTarget = GpsHub.bearing
            if (!haveHeading) {
                haveHeading = true
                headingDisp = headingTarget
            }
        }
        headingDisp = Geo.norm360(
            headingDisp + Geo.shortestDiff(headingDisp, headingTarget) * (1.0 - exp(-dt / 0.35))
        )

        val now = GpsHub.nowMs()
        val tz = TimeZone.getDefault()
        val key = now / 60000L
        if (key != sunKey) {
            sunKey = key
            val r = SunTimes.compute(GpsHub.lat, GpsHub.lon, SunTimes.localNoon(now, tz))
            sunRise = if (r != null) Fmt.hm(r[0], tz) else "--:--"
            sunSet = if (r != null) Fmt.hm(r[1], tz) else "--:--"
        }

        // ---- Columna esquerra: velocitat, alçada, sol
        speedDrum.draw(c, 30f, 14f, Drum.positions(speedDisp, 3).reversedArray())
        gray.draw(c, "km/h", 556f, 292f, 38f, Paint.Align.RIGHT)

        val alt = GpsHub.correctedAltitude()
        val altText = if (alt.isNaN()) "-- m" else Fmt.num(alt, 0) + " m"
        gray.draw(c, "Alçada", 30f, 352f, 38f)
        white.draw(c, altText, 556f, 356f, 60f, Paint.Align.RIGHT, 300f)
        gray.draw(c, "Sortida del sol", 30f, 418f, 38f)
        white.draw(c, sunRise, 556f, 422f, 56f, Paint.Align.RIGHT)
        gray.draw(c, "Posta del sol", 30f, 484f, 38f)
        white.draw(c, sunSet, 556f, 488f, 56f, Paint.Align.RIGHT)

        // ---- Columna central: brúixola, rumb i comptaquilòmetres del viatge
        val dimAlpha = if (headingOk) 255 else 120
        compass.draw(c, 800f, 160f, 135f, headingDisp.toFloat(), dimAlpha)
        val headText = if (haveHeading) {
            "${headingDisp.roundToInt() % 360}° ${Geo.cardinal(headingDisp)}"
        } else {
            "---"
        }
        white.draw(c, headText, 800f, 372f, 70f, Paint.Align.CENTER, 290f, dimAlpha)
        gray.draw(c, "Viatge (km)", 800f, 414f, 30f, Paint.Align.CENTER)
        val tripPos = Drum.positions(GpsHub.trip.distanceM / 100.0, 4).reversedArray()
        tripDrum.draw(c, 800f - tripDrum.width(4) / 2f, 426f, tripPos)

        // ---- Columna dreta: rellotge, hora, data, estat del GPS
        clock.draw(c, 1110f, 132f, 112f, now, tz)
        white.draw(c, Fmt.hm(now, tz), 1110f, 318f, 92f, Paint.Align.CENTER, 300f)
        cal.timeZone = tz
        cal.timeInMillis = now
        white.draw(c, Cat.weekday(cal), 1110f, 366f, 40f, Paint.Align.CENTER, 300f)
        gray.draw(c, Cat.dayMonthYear(cal), 1110f, 410f, 32f, Paint.Align.CENTER, 300f)

        dot.color = GpsHub.statusColor()
        c.drawCircle(985f, 477f, 12f, dot)
        val gpsText = if (fresh) {
            "GPS ${GpsHub.satsUsed}/${GpsHub.satsVisible}"
        } else {
            GpsHub.statusLabel()
        }
        gray.draw(c, gpsText, 1008f, 488f, 34f, Paint.Align.LEFT, 250f)
    }
}

/** Pàgina 2: viatge i dades del GPS. */
class TripPage(ctx: Context) : BasePage(ctx, 1280f, 520f) {

    var onReset: (() -> Unit)? = null

    private val drum = DrumPainter(130f, 190f, 8f, true, true, true)
    private val gray = Txt(Pal.GRAY, false)
    private val resetRect = RectF(940f, 428f, 1260f, 508f)
    private var distDisp = 0.0

    override fun paintPage(c: Canvas) {
        val t = GpsHub.trip

        distDisp += (t.distanceM - distDisp) * (1.0 - exp(-dt / 0.4))
        if (abs(t.distanceM - distDisp) < 0.5) distDisp = t.distanceM

        gray.draw(c, "Distància del viatge (km)", 40f, 44f, 36f)
        drum.draw(c, 40f, 62f, Drum.positions(distDisp / 100.0, 4).reversedArray())

        row(c, "En marxa", Fmt.hms(t.movingMs), 322f, 40f, 600f, 38f, 48f)
        row(c, "Aturat", Fmt.hms(t.stoppedMs), 378f, 40f, 600f, 38f, 48f)
        row(c, "Velocitat mitjana", Fmt.num(t.avgKmh(), 0) + " km/h", 434f, 40f, 600f, 38f, 48f)
        row(c, "Velocitat màxima", Fmt.num(t.maxKmh, 0) + " km/h", 490f, 40f, 600f, 38f, 48f)

        val x0 = 680f
        val x1 = 1260f
        row(c, "Estat", GpsHub.statusLabel(), 58f, x0, x1, 34f, 40f, GpsHub.statusColor())
        row(c, "Satèl·lits", "${GpsHub.satsUsed} usats de ${GpsHub.satsVisible}", 114f, x0, x1, 34f, 40f)
        row(c, "Precisió", if (GpsHub.accuracy.isNaN()) "--" else "±" + Fmt.num(GpsHub.accuracy, 0) + " m", 170f, x0, x1, 34f, 40f)
        row(c, "Latitud", Fmt.coord(GpsHub.lat, "N", "S"), 226f, x0, x1, 34f, 40f)
        row(c, "Longitud", Fmt.coord(GpsHub.lon, "E", "O"), 282f, x0, x1, 34f, 40f)
        val alt = GpsHub.correctedAltitude()
        row(c, "Alçada", if (alt.isNaN()) "--" else Fmt.num(alt, 0) + " m", 338f, x0, x1, 34f, 40f)
        val raw = GpsHub.altitudeRaw
        val corr = Prefs.altOffset
        val rawText = if (raw.isNaN()) "--" else Fmt.num(raw, 0) + " m (corr. " + Fmt.signed(corr, 0) + ")"
        row(c, "Alçada GPS", rawText, 388f, x0, x1, 28f, 30f)

        drawButton(c, resetRect, "Posa a zero", 38f)
    }

    override fun onTap(x: Float, y: Float) {
        if (resetRect.contains(x, y)) onReset?.invoke()
    }
}

/** Pàgina 3: força G i inclinació. */
class SensorsPage(ctx: Context) : BasePage(ctx, 1280f, 520f) {

    var onCalibrate: (() -> Unit)? = null

    private val gray = Txt(Pal.GRAY, false)
    private val white = Txt(Pal.WHITE)
    private val amber = Txt(Pal.AMBER)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Pal.RING
    }
    private val faceP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.AMBER }
    private val skyP = Paint().apply { color = 0xFF1F5C8C.toInt() }
    private val groundP = Paint().apply { color = 0xFF7A5230.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeWidth = 4f
    }
    private val wingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.AMBER
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val clip = Path()
    private val calRect = RectF(500f, 416f, 810f, 502f)

    private var gx = 0.0
    private var gy = 0.0
    private var pitch = 0.0
    private var roll = 0.0

    override fun paintPage(c: Canvas) {
        val a = AccelHub.calc
        if (!AccelHub.available) {
            white.draw(c, "Aquest aparell no té acceleròmetre", 640f, 270f, 50f, Paint.Align.CENTER, 1100f)
            return
        }

        val k = 1.0 - exp(-dt / 0.12)
        gx += (a.latG - gx) * k
        gy += (a.longG - gy) * k
        pitch += (a.pitchDeg - pitch) * k
        roll += (a.rollDeg - roll) * k

        // ---- Força G: bola en un cercle, 0,8 G al límit
        val cx = 260f
        val cy = 262f
        val r = 190f
        val perG = r / 0.8f
        c.drawCircle(cx, cy, r, faceP)
        for (i in 1..4) c.drawCircle(cx, cy, r * i / 4f, ringP)
        c.drawLine(cx - r, cy, cx + r, cy, ringP)
        c.drawLine(cx, cy - r, cx, cy + r, ringP)
        var bx = (gx * perG).toFloat()
        var by = (-gy * perG).toFloat()
        val len = kotlin.math.sqrt(bx * bx + by * by)
        if (len > r) {
            bx *= r / len
            by *= r / len
        }
        c.drawCircle(cx + bx, cy + by, 22f, dotP)
        gray.draw(c, "Accelera", cx, cy - r - 12f, 28f, Paint.Align.CENTER)
        gray.draw(c, "Frena", cx, cy + r + 34f, 28f, Paint.Align.CENTER)

        // ---- Valors numèrics
        gray.draw(c, "Accel. / frenada", 500f, 120f, 34f)
        white.draw(c, Fmt.signed(a.longG, 2) + " G", 500f, 190f, 70f, Paint.Align.LEFT, 300f)
        gray.draw(c, "Lateral", 500f, 262f, 34f)
        white.draw(c, Fmt.signed(a.latG, 2) + " G", 500f, 332f, 70f, Paint.Align.LEFT, 300f)

        // ---- Horitzó artificial
        val hx = 1000f
        val hy = 215f
        val hr = 150f
        c.save()
        clip.reset()
        clip.addCircle(hx, hy, hr, Path.Direction.CW)
        c.clipPath(clip)
        c.rotate((-roll).toFloat(), hx, hy)
        c.translate(0f, (pitch * 5.0).toFloat())
        c.drawRect(hx - 400f, hy - 600f, hx + 400f, hy, skyP)
        c.drawRect(hx - 400f, hy, hx + 400f, hy + 600f, groundP)
        c.drawLine(hx - 400f, hy, hx + 400f, hy, linePaint)
        for (deg in intArrayOf(-20, -10, 10, 20)) {
            val yy = hy - deg * 5f
            c.drawLine(hx - 30f, yy, hx + 30f, yy, linePaint)
        }
        c.restore()
        c.drawCircle(hx, hy, hr, ringP)
        c.drawLine(hx - 70f, hy, hx - 20f, hy, wingP)
        c.drawLine(hx + 20f, hy, hx + 70f, hy, wingP)
        c.drawCircle(hx, hy, 6f, wingP)

        val pitchText = (if (pitch >= 0) "Morro amunt " else "Morro avall ") + Fmt.num(abs(pitch), 1) + "°"
        val rollText = if (abs(roll) < 0.05) "Escorada 0.0°" else
            "Escorada " + Fmt.num(abs(roll), 1) + "° a la " + (if (roll >= 0) "dreta" else "esquerra")
        white.draw(c, pitchText, hx, 418f, 38f, Paint.Align.CENTER, 330f)
        white.draw(c, rollText, hx, 466f, 38f, Paint.Align.CENTER, 330f)

        if (!a.hasRef) {
            amber.draw(c, "Cal calibrar: cotxe aturat i en pla, i toca «Calibra nivell»", 640f, 502f, 32f, Paint.Align.CENTER, 1200f)
        }
        drawButton(c, calRect, "Calibra nivell", 36f)
    }

    override fun onTap(x: Float, y: Float) {
        if (calRect.contains(x, y)) onCalibrate?.invoke()
    }
}
