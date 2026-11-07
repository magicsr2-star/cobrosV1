package com.vts.cobros

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vts.cobros.databinding.ActivityMainBinding
import com.vts.cobros.databinding.DialogCobroBinding
import com.vts.cobros.databinding.ItemCreditoBinding
import com.vts.cobros.databinding.ItemDiaBinding
import com.vts.cobros.databinding.ItemMovBinding
import com.vts.cobros.databinding.ItemTiendaBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: CobrosDb
    private lateinit var printer: PrinterManager

    private var filtro = "todos" // todos | vencidos | hoy | cobrados
    private var plazoSel = 2 // 2 | 7 | 0 = otro
    private var histTiendaId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(db.toJsonString().toByteArray()) }
            toast("Respaldo guardado")
        } catch (_: Exception) { toast("No se pudo guardar el respaldo") }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            AlertDialog.Builder(this)
                .setTitle("Importar respaldo")
                .setMessage("Esto reemplaza todos los datos actuales. ¿Continuar?")
                .setPositiveButton("Importar") { _, _ ->
                    if (db.importJson(text)) { render(); toast("Respaldo importado") }
                    else toast("Archivo no válido")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } catch (_: Exception) { toast("No se pudo leer el archivo") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = CobrosDb(this)
        db.load()
        printer = PrinterManager(this)

        binding.tvFecha.textContent()
        pedirPermisos()
        setupNav()
        setupHoy()
        setupAdd()
        setupBuscar()
        setupHist()
        detectar()
        render()
    }

    private fun TextView.textContent() {
        text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "MX")).format(Date())
            .replaceFirstChar { it.uppercase() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- Permisos ----------
    private fun pedirPermisos() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun tienePermisoUbicacion(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------- Navegacion ----------
    private fun setupNav() {
        val secs = mapOf(
            binding.navHoy to binding.secHoy,
            binding.navAdd to binding.secAdd,
            binding.navBuscar to binding.secBuscar,
            binding.navHist to binding.secHist
        )
        secs.forEach { (btn, sec) ->
            btn.setOnClickListener {
                secs.values.forEach { it.visibility = View.GONE }
                sec.visibility = View.VISIBLE
                secs.keys.forEach { it.setTextColor(ContextCompat.getColor(this, R.color.mute)) }
                btn.setTextColor(ContextCompat.getColor(this, R.color.ink))
                render()
            }
        }
    }

    private fun goHoy() {
        binding.navHoy.performClick()
    }

    // ---------- Hoy ----------
    private fun setupHoy() {
        binding.btnDetectar.setOnClickListener { detectar() }
        val chips = mapOf(
            binding.chipTodos to "todos",
            binding.chipVencidos to "vencidos",
            binding.chipHoy to "hoy",
            binding.chipCobrados to "cobrados"
        )
        chips.forEach { (btn, f) ->
            btn.setOnClickListener {
                filtro = f
                chips.keys.forEach {
                    it.setBackgroundResource(R.drawable.bg_btn_secondary)
                    it.setTextColor(ContextCompat.getColor(this, R.color.ink))
                }
                btn.setBackgroundResource(R.drawable.bg_btn_primary)
                btn.setTextColor(ContextCompat.getColor(this, R.color.white))
                renderHoy()
            }
        }
        binding.etVenta.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toDoubleOrNull() ?: 0.0
                db.ventas[hoy()] = v
                db.save()
                actualizarResultado()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.btnResumen.setOnClickListener { imprimir(Tickets.ticketDia(db)) }
        binding.btnProbar.setOnClickListener { imprimir(Tickets.ticketPrueba()) }
    }

    private fun actualizarResultado() {
        val (p, a, n) = db.totalesDia(hoy())
        val v = db.ventas[hoy()] ?: 0.0
        binding.tvResultado.text = fmt(v + p + a - n)
    }

    private fun renderHoy() {
        val h = hoy()
        val lista = db.porCobrarHoy().filter { c ->
            when (filtro) {
                "vencidos" -> c.estado == "pendiente" && difDias(c.fechaCobro) < 0
                "hoy" -> c.estado == "pendiente" && difDias(c.fechaCobro) == 0L
                "cobrados" -> db.cobradoHoy(c) > 0
                else -> true
            }
        }
        val pend = db.porCobrarHoy().filter { it.estado == "pendiente" }
        val falta = pend.sumOf { it.saldo }
        val cobrado = db.porCobrarHoy().sumOf { db.cobradoHoy(it) }
        val total = falta + cobrado
        binding.kpiTotal.text = fmt(total)
        binding.kpiCobrado.text = fmt(cobrado)
        binding.kpiFalta.text = fmt(falta)
        binding.kpiFalta.setTextColor(
            ContextCompat.getColor(this, if (falta > 0) R.color.warn else R.color.ok)
        )
        val pct = if (total > 0) (cobrado / total * 100).toInt() else 0
        binding.pbProgreso.progress = pct
        binding.tvProgreso.text = "$pct% cobrado"

        val list = binding.listHoy
        list.removeAllViews()
        if (lista.isEmpty()) {
            list.addView(emptyView(if (total > 0) "Todo cobrado por hoy." else "No tienes cobros programados para hoy."))
        } else {
            lista.forEach { c -> list.addView(creditoView(c, true)) }
        }

        // Movimientos del dia
        val movs = binding.listMovs
        movs.removeAllViews()
        val ms = db.movsDia(h)
        if (ms.isEmpty()) {
            movs.addView(emptyView("Aún no registras movimientos hoy."))
        } else {
            ms.forEach { m ->
                val b = ItemMovBinding.inflate(LayoutInflater.from(this), movs, false)
                b.imConcepto.text = m.concepto
                b.imMontos.text = "P:${m.pagados}  A:${m.abono}  N:${m.nuevos}"
                b.imPrint.setOnClickListener { imprimir(Tickets.ticketMov(db, m)) }
                b.imAnular.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Anular movimiento")
                        .setMessage("Se anula el movimiento de ${m.concepto} y se devuelven los saldos. ¿Continuar?")
                        .setPositiveButton("Anular") { _, _ ->
                            if (db.anularMov(m)) { render(); toast("Movimiento anulado") }
                            else toast("No se pudo anular este movimiento")
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                movs.addView(b.root)
            }
        }
        val venta = db.ventas[h] ?: 0.0
        if (binding.etVenta.text.toString() != (if (venta == 0.0) "" else venta.toString())) {
            binding.etVenta.setText(if (venta == 0.0) "" else venta.toString())
        }
        actualizarResultado()
    }

    private fun creditoView(c: Credito, conBoton: Boolean): View {
        val b = ItemCreditoBinding.inflate(LayoutInflater.from(this), binding.listHoy, false)
        val t = db.tienda(c.tiendaId)
        b.itNombre.text = t?.nombre ?: "Tienda"
        if (filtro == "cobrados" || db.cobradoHoy(c) > 0) {
            b.itSaldo.text = fmt(db.cobradoHoy(c))
            b.itSaldo.setTextColor(ContextCompat.getColor(this, R.color.ok))
        } else {
            b.itSaldo.text = fmt(c.saldo)
        }
        b.itSub.text = "Crédito a ${plazoTxt(c.plazo)}, ${venceTxt(c)}"
        if (conBoton) {
            b.itBtn.setOnClickListener { cobrar(c.tiendaId, c.id) }
        } else {
            b.itBtn.visibility = View.GONE
        }
        return b.root
    }

    private fun emptyView(texto: String): View {
        val v = android.widget.TextView(this)
        v.text = texto
        v.setTextColor(ContextCompat.getColor(this, R.color.mute))
        v.textAlignment = View.TEXT_ALIGNMENT_CENTER
        v.setPadding(0, 18, 0, 18)
        return v
    }

    // ---------- Cobrar ----------
    private fun cobrar(tiendaId: String, creditoId: String? = null) {
        val c = creditoId?.let { id -> db.creditos.find { it.id == id } }
        val t = db.tienda(tiendaId) ?: return
        val dlgB = DialogCobroBinding.inflate(LayoutInflater.from(this))
        dlgB.dlgTitulo.text = t.nombre
        val saldo = c?.saldo ?: db.saldoTienda(tiendaId)
        dlgB.dlgSaldo.text = "Saldo pendiente: ${fmt(saldo)}"
        dlgB.dlgMonto.setText(saldo.toString())
        AlertDialog.Builder(this)
            .setView(dlgB.root)
            .setPositiveButton("Registrar cobro") { d, _ ->
                val m = dlgB.dlgMonto.text.toString().toDoubleOrNull() ?: 0.0
                if (m <= 0) { toast("Escribe el monto cobrado"); return@setPositiveButton }
                val total = m >= saldo - 0.009
                val h = hoy()
                val aplicado = db.aplicarPago(tiendaId, m, h, creditoId)
                val mov = Mov(
                    id = uid(), fecha = h, tiendaId = tiendaId, concepto = t.nombre,
                    pagados = if (total) m else 0.0, abono = if (total) 0.0 else m, nuevos = 0.0,
                    aplicado = aplicado.toMutableList()
                )
                db.movs.add(mov)
                db.save()
                d.dismiss()
                render()
                AlertDialog.Builder(this)
                    .setTitle("Cobro registrado")
                    .setMessage("¿Imprimir ticket?")
                    .setPositiveButton("Imprimir") { _, _ -> imprimir(Tickets.ticketMov(db, mov)) }
                    .setNegativeButton("No", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun imprimir(bytes: ByteArray) {
        if (!printer.hasPrinter) {
            toast("Elige una impresora en Buscar primero")
            binding.navBuscar.performClick()
            return
        }
        printer.print(bytes) { ok, msg ->
            runOnUiThread { toast(msg) }
        }
    }

    // ---------- Agregar ----------
    private fun setupAdd() {
        val nombres = db.tiendas.map { it.nombre }
        binding.etNombre.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, nombres)
        )
        val plazoBtns = mapOf(binding.btnP2 to 2, binding.btnP7 to 7, binding.btnPx to 0)
        plazoBtns.forEach { (btn, p) ->
            btn.setOnClickListener {
                plazoSel = p
                plazoBtns.keys.forEach {
                    it.setBackgroundResource(R.drawable.bg_btn_secondary)
                    it.setTextColor(ContextCompat.getColor(this, R.color.ink))
                }
                btn.setBackgroundResource(R.drawable.bg_btn_primary)
                btn.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.etDias.visibility = if (p == 0) View.VISIBLE else View.GONE
            }
        }
        binding.btnGuardar.setOnClickListener { guardar() }
    }

    private fun guardar() {
        val nombre = binding.etNombre.text.toString().trim()
        if (nombre.isEmpty()) { toast("Escribe la tienda o concepto"); return }
        val pag = binding.etPag.text.toString().toDoubleOrNull() ?: 0.0
        val ab = binding.etAb.text.toString().toDoubleOrNull() ?: 0.0
        val nue = binding.etNue.text.toString().toDoubleOrNull() ?: 0.0
        if (pag == 0.0 && ab == 0.0 && nue == 0.0) { toast("Captura al menos un monto"); return }
        val plazo = if (plazoSel == 0) (binding.etDias.text.toString().toIntOrNull() ?: 0) else plazoSel
        if (nue > 0 && plazo < 1) { toast("Escribe los días de plazo"); return }
        val h = hoy()
        val radio = (binding.etRadio.text.toString().toIntOrNull() ?: 100).coerceIn(20, 500)
        val loc = ultimaUbicacion()
        var t = db.getTienda(nombre)
        if (t == null) {
            t = Tienda(uid(), nombre, null, null, radio)
            db.tiendas.add(t)
        }
        if (loc != null && (t.lat == null || binding.cbAct.isChecked)) {
            t.lat = loc.latitude
            t.lng = loc.longitude
        }
        t.radio = radio
        var aplicado: List<Aplicado> = emptyList()
        if (pag > 0) aplicado = db.aplicarPago(t.id, pag, h)
        if (ab > 0) aplicado = aplicado + db.aplicarPago(t.id, ab, h)
        var creditoNuevoId: String? = null
        if (nue > 0) {
            val c = Credito(
                id = uid(), tiendaId = t.id, monto = nue, saldo = nue, plazo = plazo,
                fechaOtorgado = h, fechaCobro = sumaDias(h, plazo),
                estado = "pendiente", pagos = mutableListOf()
            )
            db.creditos.add(c)
            creditoNuevoId = c.id
        }
        val mov = Mov(
            id = uid(), fecha = h, tiendaId = t.id, concepto = t.nombre,
            pagados = pag, abono = ab, nuevos = nue,
            aplicado = aplicado.toMutableList(), creditoNuevoId = creditoNuevoId
        )
        db.movs.add(mov)
        db.save()
        binding.etNombre.setText("")
        binding.etPag.setText("")
        binding.etAb.setText("")
        binding.etNue.setText("")
        binding.etDias.setText("")
        binding.cbAct.isChecked = false
        toast(if (loc != null) "Guardado con ubicación GPS" else "Guardado sin GPS: revisa el permiso de ubicación")
        goHoy()
        AlertDialog.Builder(this)
            .setTitle("Guardado")
            .setMessage("¿Imprimir ticket de ${t.nombre}?")
            .setPositiveButton("Imprimir") { _, _ -> imprimir(Tickets.ticketMov(db, mov)) }
            .setNegativeButton("No", null)
            .show()
    }

    // ---------- Buscar ----------
    private fun setupBuscar() {
        binding.btnPrinter.setOnClickListener { elegirImpresora() }
        binding.etBuscar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderBuscar() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.btnBackup.setOnClickListener {
            exportLauncher.launch("cobros-respaldo-${hoy()}.json")
        }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("application/json")) }
        actualizarPrinter()
    }

    private fun actualizarPrinter() {
        binding.tvPrinter.text = if (printer.hasPrinter)
            "Impresora: ${printer.printerName}" else "Impresora: sin elegir"
    }

    private fun elegirImpresora() {
        val devices = printer.pairedDevices()
        if (devices.isEmpty()) {
            toast("No hay dispositivos Bluetooth vinculados. Vincula la impresora en Ajustes > Bluetooth.")
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elegir impresora")
            .setItems(names) { _, which ->
                printer.setPrinter(devices[which])
                actualizarPrinter()
                toast("Impresora: ${names[which]}")
            }
            .setNeutralButton("Quitar impresora") { _, _ ->
                printer.clearPrinter()
                actualizarPrinter()
            }
            .show()
    }

    private fun renderBuscar() {
        actualizarPrinter()
        val q = binding.etBuscar.text.toString().trim().lowercase()
        val lista = db.tiendas.filter { it.nombre.lowercase().contains(q) }
            .sortedByDescending { db.saldoTienda(it.id) }
        binding.tvCartera.text = "Total por cobrar: ${fmt(db.totalCartera())}"
        binding.tvCarteraSub.text = "${db.tiendasConDeuda()} tiendas con deuda"
        val list = binding.listTiendas
        list.removeAllViews()
        if (lista.isEmpty()) {
            list.addView(emptyView("No hay tiendas que coincidan."))
            return
        }
        lista.forEach { t ->
            val b = ItemTiendaBinding.inflate(LayoutInflater.from(this), list, false)
            val saldo = db.saldoTienda(t.id)
            b.tiNombre.text = t.nombre
            b.tiSaldo.text = fmt(saldo)
            b.tiSaldo.setTextColor(
                ContextCompat.getColor(this, if (saldo > 0) R.color.warn else R.color.ok)
            )
            val ps = db.pendientes(t.id)
            val det = b.tiDetalle
            det.removeAllViews()
            if (ps.isEmpty()) {
                det.addView(subView("Sin crédito pendiente"))
            } else {
                ps.forEach { c ->
                    det.addView(subView("${fmt(c.saldo)} de ${fmt(c.monto)}, plazo ${plazoTxt(c.plazo)}, cobro el ${c.fechaCobro} (${venceTxt(c)})"))
                }
            }
            det.addView(subView(if (t.lat != null) "GPS guardado, margen ${t.radio} m" else "Sin ubicación GPS"))
            if (saldo > 0) b.tiCobrar.setOnClickListener { cobrar(t.id) }
            else b.tiCobrar.visibility = View.GONE
            b.tiGps.setOnClickListener {
                val loc = ultimaUbicacion()
                if (loc == null) { toast("No pude obtener tu ubicación"); return@setOnClickListener }
                t.lat = loc.latitude
                t.lng = loc.longitude
                db.save()
                toast("Ubicación guardada")
                renderBuscar()
            }
            b.tiMovs.setOnClickListener {
                histTiendaId = t.id
                binding.btnVerTodo.visibility = View.VISIBLE
                binding.navHist.performClick()
            }
            list.addView(b.root)
        }
    }

    private fun subView(texto: String): View {
        val v = android.widget.TextView(this)
        v.text = texto
        v.setTextColor(ContextCompat.getColor(this, R.color.mute))
        v.textSize = 13f
        return v
    }

    // ---------- Historial ----------
    private fun setupHist() {
        binding.btnVerTodo.setOnClickListener {
            histTiendaId = null
            binding.btnVerTodo.visibility = View.GONE
            renderHist()
        }
    }

    private fun renderHist() {
        val list = binding.listHist
        list.removeAllViews()
        val fechas = (0..13).map { i ->
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.DAY_OF_YEAR, -i)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        }
        var vacio = true
        fechas.forEach { f ->
            var ms = db.movsDia(f)
            if (histTiendaId != null) ms = ms.filter { it.tiendaId == histTiendaId }
            if (ms.isEmpty()) return@forEach
            vacio = false
            val (p, a, n) = Triple(ms.sumOf { it.pagados }, ms.sumOf { it.abono }, ms.sumOf { it.nuevos })
            val v = db.ventas[f] ?: 0.0
            val b = ItemDiaBinding.inflate(LayoutInflater.from(this), list, false)
            val nombreTienda = histTiendaId?.let { db.tienda(it)?.nombre }
            b.hdFecha.text = if (nombreTienda != null) "$f · $nombreTienda" else f
            b.hdResultado.text = fmt(v + p + a - n)
            b.hdTotales.text = "Pagados ${fmt(p)} · Abono ${fmt(a)} · Nuevos ${fmt(n)} · Venta ${fmt(v)}"
            val det = b.hdDetalle
            ms.forEach { m ->
                det.addView(subView("${m.concepto}: pagados ${fmt(m.pagados)}, abono ${fmt(m.abono)}, nuevos ${fmt(m.nuevos)}"))
            }
            b.hdToggle.setOnClickListener {
                val open = det.visibility == View.VISIBLE
                det.visibility = if (open) View.GONE else View.VISIBLE
                b.hdToggle.text = if (open) "Ver detalle" else "Ocultar"
            }
            list.addView(b.root)
        }
        if (vacio) list.addView(emptyView("No hay movimientos en los últimos 14 días."))
    }

    // ---------- GPS ----------
    private var lastLoc: Location? = null

    private fun ultimaUbicacion(): Location? {
        if (!tienePermisoUbicacion()) return lastLoc
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lastLoc
        } catch (_: SecurityException) { lastLoc }
    }

    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val dl = Math.toRadians(la2 - la1)
        val dn = Math.toRadians(lo2 - lo1)
        val h = sin(dl / 2).let { it * it } +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dn / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    private fun detectar() {
        binding.tvGps.text = "Buscando tu ubicación…"
        if (!tienePermisoUbicacion()) {
            binding.tvGps.text = "Activa el permiso de ubicación para detectar la tienda."
            return
        }
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = ultimaUbicacion()
            if (last != null) mostrarTiendaCercana(last)
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    lastLoc = loc
                    mostrarTiendaCercana(loc)
                    try { lm.removeUpdates(this) } catch (_: Exception) { }
                }
            }
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
        } catch (e: SecurityException) {
            binding.tvGps.text = "No pude obtener tu ubicación. Activa el permiso de ubicación."
        } catch (_: Exception) {
            binding.tvGps.text = "No pude obtener tu ubicación."
        }
    }

    private fun mostrarTiendaCercana(loc: Location) {
        val cerca = db.tiendas.filter { it.lat != null }
            .map { t -> t to haversine(loc.latitude, loc.longitude, t.lat!!, t.lng!!) }
            .filter { it.second <= it.first.radio }
            .sortedBy { it.second }
        if (cerca.isEmpty()) {
            binding.tvGps.text = "No estás dentro del margen de ninguna tienda guardada."
            return
        }
        val (t, d) = cerca[0]
        val s = db.saldoTienda(t.id)
        binding.tvGps.text = "Estás en ${t.nombre} (a ${d.toInt()} m).\n" +
            if (s > 0) "Cobro pendiente: ${fmt(s)}" else "Sin cobro pendiente."
        if (s > 0) {
            AlertDialog.Builder(this)
                .setTitle(t.nombre)
                .setMessage("Cobro pendiente: ${fmt(s)}. ¿Cobrar ahora?")
                .setPositiveButton("Cobrar") { _, _ -> cobrar(t.id) }
                .setNegativeButton("Después", null)
                .show()
        }
    }

    // ---------- Render general ----------
    private fun render() {
        renderHoy()
        renderBuscar()
        renderHist()
    }

    override fun onDestroy() {
        printer.close()
        super.onDestroy()
    }
}
