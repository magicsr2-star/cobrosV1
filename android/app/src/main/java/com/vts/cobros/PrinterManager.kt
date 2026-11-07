package com.vts.cobros

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.OutputStream
import java.util.UUID

// Impresion termica directa por Bluetooth clasico (SPP),
// sin apps puente: la app abre el socket RFCOMM y manda ESC/POS.
class PrinterManager(private val ctx: Context) {
    companion object {
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PREFS = "cobros_printer"
        private const val KEY_MAC = "printer_mac"
        private const val KEY_NAME = "printer_name"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    val printerName: String? get() = prefs.getString(KEY_NAME, null)
    val hasPrinter: Boolean get() = prefs.getString(KEY_MAC, null) != null

    fun setPrinter(device: BluetoothDevice) {
        prefs.edit()
            .putString(KEY_MAC, device.address)
            .putString(KEY_NAME, device.name ?: device.address)
            .apply()
        close()
    }

    fun clearPrinter() {
        prefs.edit().remove(KEY_MAC).remove(KEY_NAME).apply()
        close()
    }

    fun pairedDevices(): List<BluetoothDevice> {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            adapter.bondedDevices?.toList().orEmpty().sortedBy { it.name ?: it.address }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Imprime bytes en un hilo de fondo. Llama onResult(true/false, mensaje). */
    fun print(bytes: ByteArray, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                val mac = prefs.getString(KEY_MAC, null)
                    ?: run { onResult(false, "Elige una impresora primero"); return@Thread }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: run { onResult(false, "Este telefono no tiene Bluetooth"); return@Thread }
                if (socket?.isConnected != true) {
                    val device = adapter.getRemoteDevice(mac)
                    try { adapter.cancelDiscovery() } catch (_: Exception) { }
                    val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    socket = s
                    out = s.outputStream
                }
                val o = out ?: throw IllegalStateException("Sin conexion")
                o.write(bytes)
                o.flush()
                // Dar tiempo a la impresora antes de cerrar el flujo
                Thread.sleep(300)
                onResult(true, "Impreso")
            } catch (e: SecurityException) {
                close()
                onResult(false, "Falta el permiso de Bluetooth")
            } catch (e: Exception) {
                close()
                onResult(false, "No se pudo imprimir: ${e.message ?: "error de conexion"}")
            }
        }.start()
    }

    fun close() {
        try { out?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        out = null
        socket = null
    }
}
