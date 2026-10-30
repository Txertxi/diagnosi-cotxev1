package cat.sergibonet.diagnosi

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Lògica pura (sense Android): es pot provar a part, sense la unitat.
 */

/** Constants que decideixen quan es considera que el cotxe està aturat. */
object Cfg {
    /** Per sota d'aquesta velocitat (km/h) el tauler mostra 0. */
    const val STOP_KMH = 3.0

    /** Per sota d'aquesta velocitat el rumb del GPS no és fiable: es manté l'últim. */
    const val HEADING_MIN_KMH = 5.0
}

object SunTimes {
    /**
     * Sortida i posta de sol (ms d'epoch), o null si no n'hi ha (dia o nit polar).
     * `localNoonEpochMs` és el migdia local del dia que interessa.
     */
    fun compute(latDeg: Double, lonDeg: Double, localNoonEpochMs: Long): LongArray? {
        if (latDeg.isNaN() || lonDeg.isNaN()) return null
        val rad = Math.PI / 180.0
        val jDate = localNoonEpochMs / 86400000.0 + 2440587.5
        val n = floor(jDate - 2451545.0 + lonDeg / 360.0 + 0.5)
        val jStar = n - lonDeg / 360.0
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val mr = m * rad
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lam = (m + c + 180.0 + 102.9372).mod(360.0)
        val lr = lam * rad
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lr)
        val sinD = sin(lr) * sin(23.4397 * rad)
        val cosD = sqrt(1.0 - sinD * sinD)
        val cosW = (sin(-0.833 * rad) - sin(latDeg * rad) * sinD) / (cos(latDeg * rad) * cosD)
        if (cosW.isNaN() || cosW > 1.0 || cosW < -1.0) return null
        val w = Math.toDegrees(acos(cosW))
        return longArrayOf(toMs(jTransit - w / 360.0), toMs(jTransit + w / 360.0))
    }

    private fun toMs(j: Double): Long = ((j - 2440587.5) * 86400000.0).toLong()

    /** Migdia local (ms d'epoch) del dia que conté `timeMs` a la zona `tz`. */
    fun localNoon(timeMs: Long, tz: TimeZone): Long {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = timeMs
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

object Drum {
    /**
     * Posició contínua de cada roda de dígits (índex 0 = unitats), com en un
     * comptaquilòmetres mecànic: la roda de les unitats gira contínuament i les
     * altres només avancen durant l'últim 10 % del cicle de la roda inferior.
     */
    fun positions(value: Double, wheels: Int): DoubleArray {
        val modulus = 10.0.pow(wheels)
        var v = value
        if (v.isNaN() || v < 0.0) v = 0.0
        v %= modulus
        val out = DoubleArray(wheels)
        for (k in 0 until wheels) {
            val s = v / 10.0.pow(k)
            val fl = floor(s)
            val f = s - fl
            out[k] = if (k == 0) s else fl + max(0.0, (f - 0.9) / 0.1)
        }
        return out
    }
}

object Geo {
    fun norm360(a: Double): Double {
        var r = a % 360.0
        if (r < 0.0) r += 360.0
        return r
    }

    /** Diferència amb signe, pel camí més curt, de `from` a `to` (en graus, -180..180). */
    fun shortestDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private val NAMES = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    fun cardinal(deg: Double): String = NAMES[((norm360(deg) + 22.5) / 45.0).toInt() % 8]
}

/** Dades del viatge: distància, temps en marxa i aturat, velocitat màxima. */
class TripCalc {
    var distanceM = 0.0
    var movingMs = 0L
    var stoppedMs = 0L
    var maxKmh = 0.0

    private var lastMs = -1L
    private var moving = false

    fun reset() {
        distanceM = 0.0
        movingMs = 0L
        stoppedMs = 0L
        maxKmh = 0.0
        lastMs = -1L
        moving = false
    }

    fun restore(distanceM: Double, movingMs: Long, stoppedMs: Long, maxKmh: Double) {
        this.distanceM = distanceM
        this.movingMs = movingMs
        this.stoppedMs = stoppedMs
        this.maxKmh = maxKmh
    }

    /** `elapsedMs`: instant de la lectura (rellotge monòton). */
    fun onFix(elapsedMs: Long, speedKmh: Double, accuracyM: Double) {
        if (accuracyM > MAX_ACCURACY_M) return
        val v = if (speedKmh.isNaN() || speedKmh < 0.0 || speedKmh > 350.0) 0.0 else speedKmh
        moving = if (moving) v >= EXIT_KMH else v >= ENTER_KMH
        if (moving && v > maxKmh) maxKmh = v
        val dt = if (lastMs < 0L) 0L else elapsedMs - lastMs
        lastMs = elapsedMs
        if (dt <= 0L || dt > MAX_GAP_MS) return
        if (moving) {
            distanceM += v / 3.6 * dt / 1000.0
            movingMs += dt
        } else {
            stoppedMs += dt
        }
    }

    fun avgKmh(): Double =
        if (movingMs < 1000L) 0.0 else distanceM / 1000.0 / (movingMs / 3600000.0)

    companion object {
        const val ENTER_KMH = 4.0
        const val EXIT_KMH = 2.5
        const val MAX_GAP_MS = 10000L
        const val MAX_ACCURACY_M = 50.0
    }
}

/**
 * Acceleròmetre: força G longitudinal i lateral, i inclinació (cabeceig i escorada).
 * Cal calibrar el nivell amb el cotxe aturat i en pla (guarda la gravetat de referència).
 * Suposa que la pantalla mira el conductor (endavant del cotxe = cap a dins de la pantalla)
 * i es pot corregir amb els tres ajustaments.
 */
class AccelCalc {
    var swap = false
    var invFwd = false
    var invLat = false

    var longG = 0.0
        private set
    var latG = 0.0
        private set
    var pitchDeg = 0.0
        private set
    var rollDeg = 0.0
        private set
    var hasRef = false
        private set
    var ready = false
        private set

    private val fast = DoubleArray(3)
    private val slow = DoubleArray(3)
    private val ref = DoubleArray(3)

    fun setRef(x: Double, y: Double, z: Double) {
        ref[0] = x
        ref[1] = y
        ref[2] = z
        hasRef = true
    }

    fun refVec(): DoubleArray = ref.copyOf()

    /** Desa la lectura actual (filtrada) com a "pla". */
    fun calibrate(): Boolean {
        if (!ready) return false
        setRef(slow[0], slow[1], slow[2])
        return true
    }

    fun feed(x: Double, y: Double, z: Double, dtSec: Double) {
        if (!ready) {
            fast[0] = x; fast[1] = y; fast[2] = z
            slow[0] = x; slow[1] = y; slow[2] = z
            ready = true
        } else {
            val af = 1.0 - exp(-dtSec / 0.25)
            val aSlow = 1.0 - exp(-dtSec / 2.5)
            val raw = doubleArrayOf(x, y, z)
            for (i in 0 until 3) {
                fast[i] += (raw[i] - fast[i]) * af
                slow[i] += (raw[i] - slow[i]) * aSlow
            }
        }
        if (!hasRef) return

        val rn = sqrt(dot(ref, ref))
        if (rn < 1.0) return
        val up = doubleArrayOf(ref[0] / rn, ref[1] / rn, ref[2] / rn)
        val f0 = if (swap) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 0.0, -1.0)
        val d = dot(f0, up)
        val f = doubleArrayOf(f0[0] - d * up[0], f0[1] - d * up[1], f0[2] - d * up[2])
        val fn = sqrt(dot(f, f))
        if (fn < 0.1) return
        for (i in 0 until 3) f[i] /= fn
        val r = cross(f, up)

        val dyn = doubleArrayOf(fast[0] - slow[0], fast[1] - slow[1], fast[2] - slow[2])
        val rel = doubleArrayOf(slow[0] - ref[0], slow[1] - ref[1], slow[2] - ref[2])
        val sf = if (invFwd) -1.0 else 1.0
        val sl = if (invLat) -1.0 else 1.0
        longG = sf * dot(dyn, f) / G
        latG = sl * dot(dyn, r) / G
        pitchDeg = sf * Math.toDegrees(asin(clamp1(dot(rel, f) / G)))
        rollDeg = -sl * Math.toDegrees(asin(clamp1(dot(rel, r) / G)))
    }

    private fun clamp1(v: Double): Double = min(1.0, max(-1.0, v))

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    companion object {
        const val G = 9.80665
    }
}

object Fmt {
    fun hm(epochMs: Long, tz: TimeZone): String {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = epochMs
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    fun hms(ms: Long): String {
        val s = max(0L, ms) / 1000L
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600L, (s / 60L) % 60L, s % 60L)
    }

    /** Latitud o longitud amb l'hemisferi, p. ex. "41.15322° N". */
    fun coord(v: Double, pos: String, neg: String): String {
        if (v.isNaN()) return "--"
        return String.format(Locale.US, "%.5f° %s", abs(v), if (v >= 0.0) pos else neg)
    }

    fun num(v: Double, decimals: Int): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)

    fun signed(v: Double, decimals: Int): String =
        String.format(Locale.US, "%+.${decimals}f", v)
}

object Cat {
    private val DAYS = arrayOf(
        "diumenge", "dilluns", "dimarts", "dimecres", "dijous", "divendres", "dissabte"
    )
    private val MONTHS = arrayOf(
        "de gener", "de febrer", "de març", "d'abril", "de maig", "de juny",
        "de juliol", "d'agost", "de setembre", "d'octubre", "de novembre", "de desembre"
    )

    fun weekday(cal: Calendar): String = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]

    fun dayMonthYear(cal: Calendar): String =
        "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]} de ${cal.get(Calendar.YEAR)}"
}
