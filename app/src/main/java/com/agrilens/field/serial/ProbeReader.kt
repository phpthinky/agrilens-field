package com.agrilens.field.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.agrilens.field.model.ProbeReading
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reads pH / Nitrogen / Phosphorus / Potassium from a handheld 7/8-in-1 RS485 soil
 * probe (e.g. Yago 8-in-1) connected via a USB-OTG-to-RS485 cable (CH34x chipset).
 *
 * Protocol (verified against the probe's own OEM Android app): standard Modbus RTU,
 * function 0x03, 9600 8N1 by default. A 32-register read starting at register 0
 * returns 16 value registers followed by 16 "tag" registers that identify which
 * physical parameter occupies the matching value slot — see [ProbeChannel].
 */
class ProbeReader(private val usbManager: UsbManager) {

    fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()

    suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION"

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(action), flags)

            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }

            usbManager.requestPermission(device, pendingIntent)
        }
    }

    /** Opens the probe, performs the read sequence, and returns the decoded values. */
    suspend fun readProbe(
        context: Context,
        baudRate: Int = 9600,
        slaveAddress: Int = 1,
    ): ProbeReading = withContext(Dispatchers.IO) {
        val driver = findDriver() ?: throw ProbeException.NoDeviceFound

        if (!requestPermission(context, driver.device)) {
            throw ProbeException.PermissionDenied
        }

        val connection = usbManager.openDevice(driver.device) ?: throw ProbeException.CannotOpen
        val port = driver.ports.firstOrNull() ?: throw ProbeException.NoDeviceFound

        port.open(connection)
        try {
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            val registerCount = detectRegisterCount(port, slaveAddress)
            val registers = readHoldingRegisters(port, slaveAddress, startRegister = 0, count = registerCount)

            decodeReading(registers, registerCount)
        } finally {
            runCatching { port.close() }
        }
    }

    /**
     * Mirrors the OEM app's handshake: read register 4095; if its value is 0xAAAA,
     * the device reports 256 registers per cycle instead of the default 32.
     */
    private fun detectRegisterCount(port: UsbSerialPort, slaveAddress: Int): Int {
        return runCatching {
            val registers = readHoldingRegisters(port, slaveAddress, startRegister = 4095, count = 1, timeoutMillis = 800)
            val raw = registers.firstOrNull() ?: return 32
            val unsigned = if (raw < 0) raw + 65536 else raw
            if (unsigned == 0xAAAA) 256 else 32
        }.getOrDefault(32)
    }

    private fun readHoldingRegisters(
        port: UsbSerialPort,
        slaveAddress: Int,
        startRegister: Int,
        count: Int,
        timeoutMillis: Int = 2000,
    ): IntArray {
        val request = ModbusRtu.buildReadHoldingRegistersRequest(slaveAddress, startRegister, count)
        port.write(request, 1000)

        val expectedLength = 3 + count * 2 + 2
        val response = readFully(port, expectedLength, timeoutMillis)

        val parsed = ModbusRtu.parseReadHoldingRegistersResponse(response)
            ?: throw ProbeException.InvalidResponse
        if (!parsed.crcValid) throw ProbeException.CrcMismatch

        return parsed.registers
    }

    private fun readFully(port: UsbSerialPort, expectedLength: Int, timeoutMillis: Int): ByteArray {
        val out = ByteArray(expectedLength)
        var total = 0
        val chunk = ByteArray(expectedLength)
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (total < expectedLength) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw ProbeException.Timeout

            val n = port.read(chunk, remaining.coerceAtMost(1000))
            if (n > 0) {
                System.arraycopy(chunk, 0, out, total, n)
                total += n
            }
        }
        return out
    }

    private fun decodeReading(registers: IntArray, registerCount: Int): ProbeReading {
        val half = registerCount / 2
        var ph: Double? = null
        var nitrogen: Double? = null
        var phosphorus: Double? = null
        var potassium: Double? = null
        var moisture: Double? = null
        var temperature: Double? = null
        var conductivity: Int? = null

        for (idx in 0 until half) {
            val tagIndex = half + idx
            if (tagIndex >= registers.size) break

            val rawTag = registers[tagIndex]
            val tag = if (rawTag < 0) rawTag + 65536 else rawTag
            val value = registers[idx]

            when (tag) {
                ProbeChannel.PH -> ph = value / 100.0
                ProbeChannel.NITROGEN -> nitrogen = value.toDouble()
                ProbeChannel.PHOSPHORUS -> phosphorus = value.toDouble()
                ProbeChannel.POTASSIUM -> potassium = value.toDouble()
                ProbeChannel.MOISTURE -> moisture = value / 10.0
                ProbeChannel.TEMPERATURE -> temperature = value / 10.0
                ProbeChannel.EC -> conductivity = value
            }
        }

        return ProbeReading(
            ph = ph,
            nitrogen = nitrogen,
            phosphorus = phosphorus,
            potassium = potassium,
            moisture = moisture,
            temperature = temperature,
            conductivity = conductivity,
        )
    }
}
