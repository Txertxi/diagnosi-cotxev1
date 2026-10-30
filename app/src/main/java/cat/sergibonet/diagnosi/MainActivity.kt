package cat.sergibonet.diagnosi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tauler del cotxe: quatre pàgines (Conducció, Viatge, Sensors, Ajustaments)
 * i una barra inferior amb botons grans per obrir altres apps.
 */
class MainActivity : Activity(), SensorEventListener {

    private class AppEntry(val label: String, val pkg: String, val cls: String)

    private val tabNames = arrayOf("Conducció", "Viatge", "Sensors", "Ajustaments")
    private val tabButtons = ArrayList<Button>()
    private val slotButtons = ArrayList<Button>()
    private lateinit var pages: Array<View>
    private lateinit var settingsRoot: ScrollView
    private var current = 0

    // Peces de la pantalla d'ajustaments que s'han d'actualitzar.
    private lateinit var altInfo: TextView
    private lateinit var gpsInfo: TextView
    private lateinit var swapBtn: Button
    private lateinit var invFwdBtn: Button
    private lateinit var invLatBtn: Button
    private val slotRowButtons = ArrayList<Button>()

    private var sensorManager: SensorManager? = null

    // ------------------------------------------------------------------ cicle de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        GpsHub.restore()
        applyDefaults()
        AccelHub.loadSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        selectTab(0)
        ensureGps()
    }

    override fun onResume() {
        super.onResume()
        GpsHub.permissionOk = hasLocationPermission()
        if (GpsHub.permissionOk && !GpsHub.serviceRunning) startGpsService()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        AccelHub.available = acc != null
        AccelHub.resetClock()
        if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        if (current == TAB_SETTINGS) refreshSettings()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        GpsHub.persist()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GpsHub.permissionOk = hasLocationPermission()
        if (GpsHub.permissionOk) startGpsService()
        if (current == TAB_SETTINGS) refreshSettings()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            AccelHub.onValues(
                event.values[0].toDouble(), event.values[1].toDouble(),
                event.values[2].toDouble(), event.timestamp
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ GPS i permisos

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureGps() {
        GpsHub.permissionOk = hasLocationPermission()
        if (GpsHub.permissionOk) {
            startGpsService()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
    }

    private fun startGpsService() {
        try {
            startForegroundService(Intent(this, GpsService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut engegar el GPS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ construcció de la pantalla

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundBg(normal: Int, pressed: Int): StateListDrawable {
        val radius = dp(14f).toFloat()
        val n = GradientDrawable()
        n.setColor(normal)
        n.cornerRadius = radius
        val p = GradientDrawable()
        p.setColor(pressed)
        p.cornerRadius = radius
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), p)
        s.addState(intArrayOf(), n)
        return s
    }

    private fun makeButton(text: String, sp: Float, normal: Int, pressed: Int, textColor: Int): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        b.setTextColor(textColor)
        b.background = roundBg(normal, pressed)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.maxLines = 2
        b.setPadding(dp(4f), 0, dp(4f), 0)
        return b
    }

    private fun weightParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        lp.setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        return lp
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        // Pestanyes de dalt
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        for (i in tabNames.indices) {
            val b = makeButton(tabNames[i], 24f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
            b.setOnClickListener { selectTab(i) }
            tabs.addView(b, weightParams())
            tabButtons.add(b)
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f)))

        // Pàgines
        val drive = DrivePage(this)
        val trip = TripPage(this)
        trip.onReset = { confirmResetTrip() }
        val sensors = SensorsPage(this)
        sensors.onCalibrate = { calibrateLevel() }
        settingsRoot = buildSettings()
        pages = arrayOf(drive, trip, sensors, settingsRoot)

        val content = FrameLayout(this)
        for (p in pages) {
            content.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Barra de botons d'apps
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until Prefs.SLOTS) {
            val b = makeButton(Prefs.SLOT_NAMES[i], 24f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
            b.setOnClickListener { launchSlot(i) }
            b.setOnLongClickListener {
                chooseApp(i)
                true
            }
            bar.addView(b, weightParams())
            slotButtons.add(b)
        }
        val home = makeButton("Inici", 24f, 0xFF1D4E89.toInt(), 0xFF2F6DB8.toInt(), Color.WHITE)
        home.setOnClickListener { goHome() }
        bar.addView(home, weightParams())
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104f)))

        return root
    }

    private fun selectTab(i: Int) {
        current = i
        for (j in pages.indices) {
            pages[j].visibility = if (j == i) View.VISIBLE else View.GONE
        }
        for (j in tabButtons.indices) {
            val sel = j == i
            tabButtons[j].background = if (sel) roundBg(Pal.AMBER, Pal.AMBER) else roundBg(Pal.BUTTON, Pal.BUTTON_PRESSED)
            tabButtons[j].setTextColor(if (sel) Color.BLACK else Color.WHITE)
        }
        if (i == TAB_SETTINGS) refreshSettings()
    }

    // ------------------------------------------------------------------ botons d'apps

    private fun applyDefaults() {
        if (Prefs.defaultsDone) return
        val defaults = mapOf(
            3 to "com.google.android.apps.maps",
            4 to "com.spotify.music",
            5 to "com.google.android.projection.gearhead"
        )
        for ((slot, pkg) in defaults) {
            val cn = packageManager.getLaunchIntentForPackage(pkg)?.component
            if (cn != null) Prefs.setSlot(slot, cn.packageName, cn.className)
        }
        Prefs.defaultsDone = true
    }

    private fun launchSlot(i: Int) {
        val pkg = Prefs.slotPkg(i)
        val cls = Prefs.slotCls(i)
        if (pkg.isEmpty() || cls.isEmpty()) {
            Toast.makeText(this, "«${Prefs.SLOT_NAMES[i]}»: tria quina app s'ha d'obrir", Toast.LENGTH_LONG).show()
            chooseApp(i)
            return
        }
        try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut obrir «${Prefs.SLOT_NAMES[i]}»: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut tornar a l'inici: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun listApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = ArrayList<AppEntry>()
        for (r in pm.queryIntentActivities(intent, 0)) {
            if (r.activityInfo.packageName == packageName) continue
            out.add(AppEntry(r.loadLabel(pm).toString(), r.activityInfo.packageName, r.activityInfo.name))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    private fun appLabel(pkg: String, cls: String): String {
        if (pkg.isEmpty() || cls.isEmpty()) return "Sense assignar (toca per triar)"
        return try {
            val pm = packageManager
            pm.getActivityInfo(android.content.ComponentName(pkg, cls), 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            "$pkg (no instal·lada?)"
        }
    }

    private fun chooseApp(slot: Int) {
        val apps = listApps()
        val labels = ArrayList<String>()
        labels.add("(Cap: esborra l'assignació)")
        for (a in apps) labels.add(a.label)
        val lv = ListView(this)
        lv.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                v.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                return v
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("App per a «${Prefs.SLOT_NAMES[slot]}»")
            .setView(lv)
            .setNegativeButton("Cancel·la", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos == 0) {
                Prefs.setSlot(slot, "", "")
            } else {
                val a = apps[pos - 1]
                Prefs.setSlot(slot, a.pkg, a.cls)
            }
            dlg.dismiss()
            refreshSettings()
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ viatge i nivell

    private fun confirmResetTrip() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Posar el viatge a zero?")
            .setMessage("S'esborren la distància, els temps i la velocitat màxima.")
            .setPositiveButton("Posa a zero") { _, _ -> GpsHub.resetTrip() }
            .setNegativeButton("Cancel·la", null)
            .create()
        dlg.show()
        dlg.findViewById<TextView>(android.R.id.message)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    private fun calibrateLevel() {
        val ok = AccelHub.calibrate()
        val msg = if (ok) "Nivell calibrat" else "L'acceleròmetre encara no ha donat dades"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ pàgina d'ajustaments

    private fun label(text: String, sp: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        return t
    }

    private fun header(col: LinearLayout, text: String) {
        val t = label(text, 30f, Pal.AMBER)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(22f), 0, dp(6f))
        col.addView(t)
    }

    private fun hint(col: LinearLayout, text: String) {
        val t = label(text, 20f, Pal.GRAY)
        t.setPadding(0, dp(4f), 0, dp(4f))
        col.addView(t)
    }

    private fun bigButton(text: String, onClick: () -> Unit): Button {
        val b = makeButton(text, 24f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addButton(col: LinearLayout, b: Button) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f))
        lp.setMargins(0, dp(4f), 0, dp(4f))
        col.addView(b, lp)
    }

    private fun buildSettings(): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24f), dp(8f), dp(24f), dp(32f))
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- Alçada
        header(col, "Correcció de l'alçada")
        altInfo = label("", 26f, Color.WHITE)
        col.addView(altInfo)
        val altRow = LinearLayout(this)
        altRow.orientation = LinearLayout.HORIZONTAL
        for (d in intArrayOf(-50, -10, -1, 1, 10, 50)) {
            val b = bigButton(if (d > 0) "+$d" else "$d") {
                Prefs.altOffset = Prefs.altOffset + d
                refreshSettings()
            }
            val lp = LinearLayout.LayoutParams(0, dp(64f), 1f)
            lp.setMargins(dp(3f), dp(4f), dp(3f), dp(4f))
            altRow.addView(b, lp)
        }
        col.addView(altRow)
        addButton(col, bigButton("Treu la correcció (0 m)") {
            Prefs.altOffset = 0.0
            refreshSettings()
        })
        hint(col, "Compara amb l'alçada real d'on ets (un mapa, un rètol) i ajusta fins que coincideixi. Es desa sola.")

        // --- Botons d'apps
        header(col, "Botons d'apps")
        for (i in 0 until Prefs.SLOTS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val name = label(Prefs.SLOT_NAMES[i], 26f, Color.WHITE)
            name.typeface = Typeface.DEFAULT_BOLD
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val b = bigButton("") { chooseApp(i) }
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            slotRowButtons.add(b)
            val lp = LinearLayout.LayoutParams(0, dp(64f), 2f)
            lp.setMargins(0, dp(4f), 0, dp(4f))
            row.addView(b, lp)
            col.addView(row)
        }
        hint(col, "També pots mantenir premut un botó de la barra de baix per canviar-ne l'app.")

        // --- Acceleròmetre
        header(col, "Acceleròmetre")
        addButton(col, bigButton("Calibra el nivell ara (cotxe aturat i en pla)") { calibrateLevel() })
        swapBtn = bigButton("") {
            Prefs.accSwap = !Prefs.accSwap
            AccelHub.loadSettings()
            refreshSettings()
        }
        addButton(col, swapBtn)
        invFwdBtn = bigButton("") {
            Prefs.accInvFwd = !Prefs.accInvFwd
            AccelHub.loadSettings()
            refreshSettings()
        }
        addButton(col, invFwdBtn)
        invLatBtn = bigButton("") {
            Prefs.accInvLat = !Prefs.accInvLat
            AccelHub.loadSettings()
            refreshSettings()
        }
        addButton(col, invLatBtn)
        hint(col, "Si en accelerar la bola va cap avall, o en girar cap a la dreta va cap a l'esquerra, canvia aquests ajustaments. Després torna a calibrar.")

        // --- Viatge
        header(col, "Viatge")
        addButton(col, bigButton("Posa el viatge a zero") { confirmResetTrip() })

        // --- GPS
        header(col, "GPS")
        gpsInfo = label("", 24f, Color.WHITE)
        col.addView(gpsInfo)
        addButton(col, bigButton("Demana el permís d'ubicació") { ensureGps() })
        addButton(col, bigButton("Atura el GPS i tanca l'app") {
            GpsHub.persist()
            stopService(Intent(this, GpsService::class.java))
            finishAndRemoveTask()
        })

        // --- Diagnosi
        header(col, "Diagnosi")
        addButton(col, bigButton("Obre la diagnosi de tecles, GPS i sistema") {
            startActivity(Intent(this, DiagActivity::class.java))
        })
        hint(col, "Per veure quines tecles del volant arriben a l'app. Per tornar, fes servir el botó de tornar de la unitat.")

        val ver = label("Tauler cotxe 0.2", 18f, Pal.GRAY)
        ver.setPadding(0, dp(24f), 0, 0)
        col.addView(ver)
        return sv
    }

    private fun onOff(v: Boolean) = if (v) "sí" else "no"

    private fun refreshSettings() {
        val raw = GpsHub.altitudeRaw
        val off = Prefs.altOffset
        altInfo.text = if (raw.isNaN()) {
            "Correcció: " + Fmt.signed(off, 0) + " m   (encara no hi ha alçada del GPS)"
        } else {
            "GPS brut: " + Fmt.num(raw, 0) + " m   Correcció: " + Fmt.signed(off, 0) +
                " m   Es mostra: " + Fmt.num(raw + off, 0) + " m"
        }
        for (i in slotRowButtons.indices) {
            slotRowButtons[i].text = appLabel(Prefs.slotPkg(i), Prefs.slotCls(i))
        }
        swapBtn.text = "Eixos intercanviats: " + onOff(Prefs.accSwap)
        invFwdBtn.text = "Inverteix accelera/frena: " + onOff(Prefs.accInvFwd)
        invLatBtn.text = "Inverteix esquerra/dreta: " + onOff(Prefs.accInvLat)
        gpsInfo.text = GpsHub.statusLabel() + " — satèl·lits " + GpsHub.satsUsed + "/" + GpsHub.satsVisible +
            "   Servei: " + (if (GpsHub.serviceRunning) "actiu" else "aturat")
    }

    private companion object {
        const val REQ_LOCATION = 1
        const val TAB_SETTINGS = 3
    }
}
