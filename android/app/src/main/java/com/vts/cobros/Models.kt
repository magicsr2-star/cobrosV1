package com.vts.cobros

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

// Modelos con la misma estructura que la app web (cobros_v1),
// para que el respaldo JSON de la web se pueda importar tal cual.
data class Pago(var f: String, var m: Double) {
    fun toJson() = JSONObject().put("f", f).put("m", m)
    companion object { fun fromJson(o: JSONObject) = Pago(o.optString("f"), o.optDouble("m")) }
}

data class Tienda(
    var id: String, var nombre: String,
    var lat: Double?, var lng: Double?, var radio: Int
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("nombre", nombre)
        if (lat != null) put("lat", lat!!) else put("lat", JSONObject.NULL)
        if (lng != null) put("lng", lng!!) else put("lng", JSONObject.NULL)
        put("radio", radio)
    }
    companion object {
        fun fromJson(o: JSONObject) = Tienda(
            o.optString("id"), o.optString("nombre"),
            if (o.isNull("lat")) null else o.optDouble("lat"),
            if (o.isNull("lng")) null else o.optDouble("lng"),
            o.optInt("radio", 100)
        )
    }
}

data class Credito(
    var id: String, var tiendaId: String,
    var monto: Double, var saldo: Double, var plazo: Int,
    var fechaOtorgado: String, var fechaCobro: String,
    var estado: String, var pagos: MutableList<Pago>
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("tiendaId", tiendaId); put("monto", monto)
        put("saldo", saldo); put("plazo", plazo)
        put("fechaOtorgado", fechaOtorgado); put("fechaCobro", fechaCobro)
        put("estado", estado)
        put("pagos", JSONArray().apply { pagos.forEach { put(it.toJson()) } })
    }
    companion object {
        fun fromJson(o: JSONObject) = Credito(
            o.optString("id"), o.optString("tiendaId"),
            o.optDouble("monto"), o.optDouble("saldo"), o.optInt("plazo"),
            o.optString("fechaOtorgado"), o.optString("fechaCobro"),
            o.optString("estado", "pendiente"), mutableListOf()
        ).also { c ->
            val a = o.optJSONArray("pagos")
            if (a != null) for (i in 0 until a.length()) c.pagos.add(Pago.fromJson(a.getJSONObject(i)))
        }
    }
}

data class Aplicado(var creditoId: String, var monto: Double) {
    fun toJson() = JSONObject().put("creditoId", creditoId).put("monto", monto)
    companion object { fun fromJson(o: JSONObject) = Aplicado(o.optString("creditoId"), o.optDouble("monto")) }
}

data class Mov(
    var id: String, var fecha: String, var tiendaId: String, var concepto: String,
    var pagados: Double, var abono: Double, var nuevos: Double,
    var aplicado: MutableList<Aplicado> = mutableListOf(),
    var creditoNuevoId: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("fecha", fecha); put("tiendaId", tiendaId)
        put("concepto", concepto); put("pagados", pagados)
        put("abono", abono); put("nuevos", nuevos)
        put("aplicado", JSONArray().apply { aplicado.forEach { put(it.toJson()) } })
        if (creditoNuevoId != null) put("creditoNuevoId", creditoNuevoId!!)
    }
    companion object {
        fun fromJson(o: JSONObject) = Mov(
            o.optString("id"), o.optString("fecha"), o.optString("tiendaId"),
            o.optString("concepto"), o.optDouble("pagados"),
            o.optDouble("abono"), o.optDouble("nuevos"), mutableListOf(),
            if (o.isNull("creditoNuevoId")) null else o.optString("creditoNuevoId")
        ).also { m ->
            val a = o.optJSONArray("aplicado")
            if (a != null) for (i in 0 until a.length()) m.aplicado.add(Aplicado.fromJson(a.getJSONObject(i)))
        }
    }
}

fun uid(): String = UUID.randomUUID().toString().substring(0, 8)

private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
fun hoy(): String = isoFmt.format(Date())
fun sumaDias(fecha: String, n: Int): String {
    val c = Calendar.getInstance()
    c.time = isoFmt.parse(fecha) ?: Date()
    c.add(Calendar.DAY_OF_YEAR, n)
    return isoFmt.format(c.time)
}
fun difDias(fecha: String): Long {
    val a = isoFmt.parse(fecha)?.time ?: return 0
    val b = isoFmt.parse(hoy())?.time ?: return 0
    return ((a - b) / 86400000.0).roundToLong()
}
fun plazoTxt(p: Int): String = when (p) {
    2 -> "2 días"; 7 -> "1 semana"; else -> "$p días"
}
fun venceTxt(c: Credito): String {
    val d = difDias(c.fechaCobro)
    return when {
        d < 0 -> "Atrasado ${-d} d"
        d == 0L -> "Vence hoy"
        else -> "Vence en $d d"
    }
}
fun fmt(n: Double): String = "$" + String.format(Locale.US, "%,.2f", n)
fun Double.r2(): Double = (this * 100).roundToLong() / 100.0
