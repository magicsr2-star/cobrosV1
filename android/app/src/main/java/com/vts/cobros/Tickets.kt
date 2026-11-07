package com.vts.cobros

// Tickets ESC/POS para papel de 58 mm (32 columnas), igual que la app web.
object Tickets {
    const val W = 32

    fun sinAcentos(s: String): String {
        val norm = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return norm.replace(Regex("[\\u0300-\\u036f]"), "").replace(Regex("[^\\x20-\\x7E]"), "")
    }

    fun lr(a: String, b: String): String {
        val x = sinAcentos(a)
        val y = sinAcentos(b)
        val left = x.take(maxOf(0, W - y.length - 1))
        return left.padEnd(W - y.length) + y + "\n"
    }

    class Builder {
        private val out = mutableListOf<Byte>()
        fun cmd(vararg b: Int) = apply { b.forEach { out.add(it.toByte()) } }
        fun txt(s: String) = apply { out.addAll(sinAcentos(s).toByteArray(Charsets.US_ASCII).toList()) }
        fun line(s: String) = txt(s + "\n")
        fun build(): ByteArray = out.toByteArray()
    }

    private fun cab(b: Builder, titulo: String, fecha: String) {
        b.cmd(27, 64, 27, 97, 1, 27, 69, 1)
        b.line(titulo)
        b.cmd(27, 69, 0)
        b.line(fecha)
        b.cmd(27, 97, 0)
        b.line("-".repeat(W))
    }

    private fun pie(b: Builder) {
        b.txt("\n\n\n")
        b.cmd(29, 86, 66, 0)
    }

    fun ticketMov(db: CobrosDb, m: Mov): ByteArray {
        val b = Builder()
        cab(b, m.concepto, m.fecha)
        b.txt(lr("Creditos pagados", fmt(m.pagados)))
        b.txt(lr("Abono", fmt(m.abono)))
        b.txt(lr("Creditos nuevos", fmt(m.nuevos)))
        b.line("-".repeat(W))
        b.txt(lr("Saldo pendiente", fmt(db.saldoTienda(m.tiendaId))))
        b.line("\nGracias por su pago")
        pie(b)
        return b.build()
    }

    fun ticketDia(db: CobrosDb): ByteArray {
        val h = hoy()
        val ms = db.movsDia(h)
        val (p, a, n) = db.totalesDia(h)
        val v = db.ventas[h] ?: 0.0
        val b = Builder()
        cab(b, "RESUMEN DEL DIA", h)
        ms.forEach { m -> b.txt(lr(m.concepto, fmt(m.pagados + m.abono - m.nuevos))) }
        b.line("-".repeat(W))
        b.txt(lr("Creditos pagados", fmt(p)))
        b.txt(lr("Abono", fmt(a)))
        b.txt(lr("Creditos nuevos", fmt(n)))
        b.txt(lr("Venta del dia", fmt(v)))
        b.cmd(27, 69, 1)
        b.txt(lr("RESULTADO", fmt(v + p + a - n)))
        b.cmd(27, 69, 0)
        pie(b)
        return b.build()
    }

    fun ticketPrueba(): ByteArray {
        val b = Builder()
        cab(b, "COBROS DE RUTA", hoy())
        b.line("Prueba de impresion")
        b.line("Si lees esto, la impresora")
        b.line("esta lista.")
        pie(b)
        return b.build()
    }
}
