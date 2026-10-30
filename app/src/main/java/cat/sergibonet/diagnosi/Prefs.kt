package cat.sergibonet.diagnosi

import android.content.Context
import android.content.SharedPreferences

/** Ajustaments i dades que s'han de recordar entre sessions. */
object Prefs {
    const val SLOTS = 6
    val SLOT_NAMES = arrayOf("Telèfon", "Ràdio", "Música local", "Navegador", "Música mòbil", "Android Auto")

    private var sp: SharedPreferences? = null

    private fun p(): SharedPreferences =
        sp ?: throw IllegalStateException("Prefs.init no s'ha cridat")

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences("tauler", Context.MODE_PRIVATE)
        }
    }

    private fun getD(key: String, def: Double): Double = p().getString(key, null)?.toDoubleOrNull() ?: def

    private fun putD(key: String, v: Double) {
        p().edit().putString(key, v.toString()).apply()
    }

    // ---------------------------------------------------------------- alçada
    var altOffset: Double
        get() = getD("alt_offset", 0.0)
        set(v) = putD("alt_offset", v)

    // ---------------------------------------------------------------- botons d'apps
    var defaultsDone: Boolean
        get() = p().getBoolean("defaults_done", false)
        set(v) {
            p().edit().putBoolean("defaults_done", v).apply()
        }

    fun slotPkg(i: Int): String = p().getString("slot${i}_pkg", "") ?: ""

    fun slotCls(i: Int): String = p().getString("slot${i}_cls", "") ?: ""

    fun setSlot(i: Int, pkg: String, cls: String) {
        p().edit().putString("slot${i}_pkg", pkg).putString("slot${i}_cls", cls).apply()
    }

    // ---------------------------------------------------------------- acceleròmetre
    var accSwap: Boolean
        get() = p().getBoolean("acc_swap", false)
        set(v) {
            p().edit().putBoolean("acc_swap", v).apply()
        }

    var accInvFwd: Boolean
        get() = p().getBoolean("acc_inv_fwd", false)
        set(v) {
            p().edit().putBoolean("acc_inv_fwd", v).apply()
        }

    var accInvLat: Boolean
        get() = p().getBoolean("acc_inv_lat", false)
        set(v) {
            p().edit().putBoolean("acc_inv_lat", v).apply()
        }

    fun accRef(): DoubleArray? {
        val s = p().getString("acc_ref", null) ?: return null
        val parts = s.split(",").mapNotNull { it.toDoubleOrNull() }
        return if (parts.size == 3) parts.toDoubleArray() else null
    }

    fun setAccRef(v: DoubleArray) {
        p().edit().putString("acc_ref", "${v[0]},${v[1]},${v[2]}").apply()
    }

    // ---------------------------------------------------------------- última posició (per a la sortida del sol)
    fun lastPos(): DoubleArray? {
        val la = getD("last_lat", Double.NaN)
        val lo = getD("last_lon", Double.NaN)
        return if (la.isNaN() || lo.isNaN()) null else doubleArrayOf(la, lo)
    }

    fun saveLastPos(lat: Double, lon: Double) {
        putD("last_lat", lat)
        putD("last_lon", lon)
    }

    // ---------------------------------------------------------------- viatge
    fun loadTrip(t: TripCalc) {
        t.restore(
            getD("trip_dist", 0.0),
            p().getLong("trip_moving", 0L),
            p().getLong("trip_stopped", 0L),
            getD("trip_max", 0.0)
        )
    }

    fun saveTrip(t: TripCalc) {
        p().edit()
            .putString("trip_dist", t.distanceM.toString())
            .putLong("trip_moving", t.movingMs)
            .putLong("trip_stopped", t.stoppedMs)
            .putString("trip_max", t.maxKmh.toString())
            .apply()
    }
}
