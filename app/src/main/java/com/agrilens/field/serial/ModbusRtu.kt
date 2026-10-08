package com.agrilens.field.serial

/**
 * Minimal Modbus RTU (function 0x03 — Read Holding Registers) implementation.
 *
 * Frame layout and CRC16 (polynomial 0xA001, standard Modbus) verified against the
 * OEM Android app shipped with the Yago 8-in-1 RS485 soil probe: the device is a
 * generic Modbus RTU slave over a CH34x USB-serial/OTG link, 9600 8N1 by default.
 */
object ModbusRtu {

    const val FUNCTION_READ_HOLDING_REGISTERS = 0x03

    /** Standard Modbus RTU CRC16 (poly 0xA001, init 0xFFFF), little-endian result. */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Builds an 8-byte Modbus RTU "read holding registers" request frame:
     * [slaveAddr][functionCode][startReg hi][startReg lo][count hi][count lo][crc lo][crc hi]
     */
    fun buildReadHoldingRegistersRequest(slaveAddr: Int, startRegister: Int, count: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = slaveAddr.toByte()
        frame[1] = FUNCTION_READ_HOLDING_REGISTERS.toByte()
        frame[2] = (startRegister shr 8).toByte()
        frame[3] = (startRegister and 0xFF).toByte()
        frame[4] = (count shr 8).toByte()
        frame[5] = (count and 0xFF).toByte()

        val crc = crc16(frame, 6)
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = (crc shr 8).toByte()
        return frame
    }

    data class ParsedResponse(
        val slaveAddress: Int,
        val functionCode: Int,
        val registers: IntArray,
        val crcValid: Boolean,
    )

    /**
     * Parses a Modbus RTU response to a read-holding-registers request. Register values
     * are returned as signed 16-bit ints (two's complement), since some channels
     * (e.g. temperature) can be negative.
     */
    fun parseReadHoldingRegistersResponse(data: ByteArray): ParsedResponse? {
        if (data.size < 5) return null

        val slaveAddress = data[0].toInt() and 0xFF
        val functionCode = data[1].toInt() and 0xFF
        val byteCount = data[2].toInt() and 0xFF

        if (data.size < 3 + byteCount + 2) return null

        val registerCount = byteCount / 2
        val registers = IntArray(registerCount)
        for (i in 0 until registerCount) {
            val hi = data[3 + 2 * i].toInt() and 0xFF
            val lo = data[3 + 2 * i + 1].toInt() and 0xFF
            val unsigned = (hi shl 8) or lo
            registers[i] = if (unsigned > 32767) unsigned - 65536 else unsigned
        }

        val expectedCrc = crc16(data, 3 + byteCount)
        val actualCrcLo = data[3 + byteCount].toInt() and 0xFF
        val actualCrcHi = data[3 + byteCount + 1].toInt() and 0xFF
        val actualCrc = (actualCrcHi shl 8) or actualCrcLo
        val crcValid = expectedCrc == actualCrc

        return ParsedResponse(slaveAddress, functionCode, registers, crcValid)
    }
}
