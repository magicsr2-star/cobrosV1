package com.vts.cobros

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Persistencia JSON en almacenamiento interno (cobros.json).
// Misma estructura que la app web: se puede importar su respaldo.
class CobrosDb(private val ctx: Context) {
    var tiendas = mutableListOf<Tienda>()
    var creditos = mutableListOf<Credito>()
    var movs = mutableListOf<Mov>()
    var ventas = mutableMapOf<String, Double>()

    private val file: File get() = File(ctx.filesDir, "cobros.json")

    fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            tiendas = jsonList(o.optJSONArray("tiendas")) { Tienda.fromJson(it) }
            creditos = jsonList(o.optJSONArray("creditos")) { Credito.fromJson(it) }
            movs = jsonList(o.optJSONArray("movs")) { Mov.fromJson(it) }
            ventas = mutableMapOf()
            val v = o.optJSONObject("ventas")
            if (v != null) {
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    ventas[k] = v.optDouble(k)
                }
            }
        } catch (_: Exception) { /* DB corrupta: se empieza vacia */ }
    }

    fun save() {
        try {
            val o = JSONObject()
            o.put("tiendas", JSONArray().apply { tiendas.forEach { put(it.toJson()) } })
            o.put("creditos", JSONArray().apply { creditos.forEach { put(it.toJson()) } })
            o.put("movs", JSONArray().apply { movs.forEach { put(it.toJson()) } })
            o.put("ventas", JSONObject().apply { ventas.forEach { (k, v) -> put(k, v) } })
            file.writeText(o.toString())
        } catch (_: Exception) { }
    }

    fun toJsonString(): String {
        val o = JSONObject()
        o.put("tiendas", JSONArray().apply { tiendas.forEach { put(it.toJson()) } })
        o.put("creditos", JSONArray().apply { creditos.forEach { put(it.toJson()) } })
        o.put("movs", JSONArray().apply { movs.forEach { put(it.toJson()) } })
        o.put("ventas", JSONObject().apply { ventas.forEach { (k, v) -> put(k, v) } })
        return o.toString()
    }

    /** Devuelve true si el JSON es un respaldo valido y lo carga. */
    fun importJson(text: String): Boolean {
        return try {
            val o = JSONObject(text)
            if (o.optJSONArray("tiendas") == null || o.optJSONArray("creditos") == null ||
                o.optJSONArray("movs") == null
            ) return false
            tiendas = jsonList(o.optJSONArray("tiendas")) { Tienda.fromJson(it) }
            creditos = jsonList(o.optJSONArray("creditos")) { Credito.fromJson(it) }
            movs = jsonList(o.optJSONArray("movs")) { Mov.fromJson(it) }
            ventas = mutableMapOf()
            val v = o.optJSONObject("ventas")
            if (v != null) {
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    ventas[k] = v.optDouble(k)
                }
            }
            save()
            true
        } catch (_: Exception) { false }
    }

    private fun <T> jsonList(a: JSONArray?, f: (JSONObject) -> T): MutableList<T> {
        val out = mutableListOf<T>()
        if (a != null) for (i in 0 until a.length()) {
            try { out.add(f(a.getJSONObject(i))) } catch (_: Exception) { }
        }
        return out
    }

    fun tienda(id: String) = tiendas.find { it.id == id }
    fun getTienda(nombre: String) =
        tiendas.find { it.nombre.trim().equals(nombre.trim(), ignoreCase = true) }

    fun pendientes(tiendaId: String) =
        creditos.filter { it.tiendaId == tiendaId && it.estado == "pendiente" }
            .sortedBy { it.fechaCobro }

    fun saldoTienda(tiendaId: String) = pendientes(tiendaId).sumOf { it.saldo }

    /** Aplica un pago: el credito indicado primero, luego los mas viejos.
     *  Devuelve la lista de aplicaciones para poder anular el movimiento. */
    fun aplicarPago(tiendaId: String, monto: Double, fecha: String, primeroId: String? = null): List<Aplicado> {
        var r = monto
        val lista = pendientes(tiendaId).sortedWith(compareBy { it.id != primeroId })
        val out = mutableListOf<Aplicado>()
        for (c in lista) {
            if (r <= 0) break
            val p = minOf(r, c.saldo).r2()
            c.saldo = (c.saldo - p).r2()
            r = (r - p).r2()
            c.pagos.add(Pago(fecha, p))
            out.add(Aplicado(c.id, p))
            if (c.saldo <= 0.009) { c.saldo = 0.0; c.estado = "pagado" }
        }
        return out
    }

    /** Anula un movimiento revirtiendo sus pagos y su credito nuevo.
     *  Devuelve false si no se puede anular con seguridad. */
    fun anularMov(m: Mov): Boolean {
        // Validar primero: cada aplicacion debe cuadrar con un pago real.
        for (a in m.aplicado) {
            val c = creditos.find { it.id == a.creditoId } ?: return false
            if (c.pagos.none { it.m == a.monto }) return false
        }
        for (a in m.aplicado) {
            val c = creditos.find { it.id == a.creditoId }!!
            val p = c.pagos.lastOrNull { it.m == a.monto } ?: continue
            c.pagos.remove(p)
            c.saldo = (c.saldo + a.monto).r2()
            if (c.estado == "pagado") c.estado = "pendiente"
        }
        val cn = m.creditoNuevoId?.let { id -> creditos.find { it.id == id } }
        if (cn != null && cn.pagos.isEmpty()) creditos.remove(cn)
        movs.remove(m)
        save()
        return true
    }

    fun movsDia(fecha: String) = movs.filter { it.fecha == fecha }
    fun totalesDia(fecha: String): Triple<Double, Double, Double> {
        val ms = movsDia(fecha)
        return Triple(ms.sumOf { it.pagados }, ms.sumOf { it.abono }, ms.sumOf { it.nuevos })
    }

    /** Creditos que vencen hoy o antes (pendientes) + cobrados hoy. */
    fun porCobrarHoy(): List<Credito> {
        val h = hoy()
        return creditos.filter {
            (it.fechaCobro <= h && it.estado == "pendiente") ||
                it.pagos.any { p -> p.f == h }
        }.sortedBy { it.fechaCobro }
    }

    fun cobradoHoy(c: Credito): Double {
        val h = hoy()
        return c.pagos.filter { it.f == h }.sumOf { it.m }
    }

    fun totalCartera(): Double = creditos.filter { it.estado == "pendiente" }.sumOf { it.saldo }
    fun tiendasConDeuda(): Int = tiendas.count { saldoTienda(it.id) > 0 }
}
