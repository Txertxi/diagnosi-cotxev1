package cat.sergibonet.diagnosi

import android.location.Location
import android.os.SystemClock

/**
 * Estat compartit del GPS. El servei l'actualitza i les pantalles el llegeixen.
 * Tot passa pel fil principal, per això no cal sincronitzar res.
 */
object GpsHub {
    var lat = Double.NaN
    var lon = Double.NaN
    var accuracy = Double.NaN
    var speedKmh = 0.0
    var bearing = 0.0
    var hasBearing = false
    var altitudeRaw = Double.NaN
    var satsUsed = 0
    var satsVisible = 0
    var lastFixElapsedMs = 0L
    var permissionOk = false
    var serviceRunning = false
    val trip = TripCalc()

    private var clockOffsetMs = 0L
    private var haveClock = false
    private var restored = false
    private var lastSaveElapsed = 0L

    /** Carrega el viatge i l'última posició guardats (una sola vegada per procés). */
    fun restore() {
        if (restored) return
        restored = true
        Prefs.loadTrip(trip)
        val pos = Prefs.lastPos()
        if (pos != null) {
            lat = pos[0]
            lon = pos[1]
        }
    }

    fun fixAgeMs(): Long =
        if (lastFixElapsedMs == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastFixElapsedMs

    fun hasFreshFix(): Boolean = fixAgeMs() < 5000L

    fun hasPosition(): Boolean = !lat.isNaN() && !lon.isNaN()

    /** Hora actual: la del sistema, corregida amb l'hora GPS quan n'hi ha hagut. */
    fun nowMs(): Long = System.currentTimeMillis() + clockOffsetMs

    fun correctedAltitude(): Double =
        if (altitudeRaw.isNaN()) Double.NaN else altitudeRaw + Prefs.altOffset

    fun onLocation(l: Location) {
        lat = l.latitude
        lon = l.longitude
        accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else Double.NaN
        speedKmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        hasBearing = l.hasBearing()
        if (hasBearing) bearing = l.bearing.toDouble()
        altitudeRaw = if (l.hasAltitude()) l.altitude else Double.NaN
        lastFixElapsedMs = SystemClock.elapsedRealtime()

        trip.onFix(l.elapsedRealtimeNanos / 1_000_000L, speedKmh, if (accuracy.isNaN()) 0.0 else accuracy)

        // Hora GPS: es guarda la diferència amb el rellotge del sistema.
        val off = l.time - System.currentTimeMillis()
        if (!haveClock) {
            clockOffsetMs = off
            haveClock = true
        } else {
            clockOffsetMs = (clockOffsetMs * 0.8 + off * 0.2).toLong()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveElapsed > 15000L) {
            lastSaveElapsed = now
            persist()
        }
    }

    fun onSatellites(used: Int, visible: Int) {
        satsUsed = used
        satsVisible = visible
    }

    fun resetTrip() {
        trip.reset()
        Prefs.saveTrip(trip)
    }

    fun persist() {
        Prefs.saveTrip(trip)
        if (hasPosition()) Prefs.saveLastPos(lat, lon)
    }

    fun statusLabel(): String = when {
        !permissionOk -> "Sense permís d'ubicació"
        hasFreshFix() -> "Posició fixada"
        lastFixElapsedMs == 0L -> "Buscant satèl·lits..."
        else -> "Senyal perdut"
    }

    fun statusColor(): Int = when {
        !permissionOk -> Pal.RED
        hasFreshFix() -> Pal.GREEN
        lastFixElapsedMs == 0L -> Pal.AMBER
        else -> Pal.AMBER
    }
}

/** Acceleròmetre compartit entre l'activitat (que rep les dades) i la pantalla de sensors. */
object AccelHub {
    val calc = AccelCalc()
    var available = false
    private var lastTs = 0L

    fun loadSettings() {
        calc.swap = Prefs.accSwap
        calc.invFwd = Prefs.accInvFwd
        calc.invLat = Prefs.accInvLat
        val r = Prefs.accRef()
        if (r != null) calc.setRef(r[0], r[1], r[2])
    }

    fun onValues(x: Double, y: Double, z: Double, tsNs: Long) {
        val dt = if (lastTs == 0L) 0.06 else (tsNs - lastTs) / 1e9
        lastTs = tsNs
        calc.feed(x, y, z, dt.coerceIn(0.005, 0.5))
    }

    fun calibrate(): Boolean {
        val ok = calc.calibrate()
        if (ok) Prefs.setAccRef(calc.refVec())
        return ok
    }

    fun resetClock() {
        lastTs = 0L
    }
}
