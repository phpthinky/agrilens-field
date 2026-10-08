package com.agrilens.field.serial

/**
 * Tag codes reported by the probe's own "channel map" registers (the second half of
 * its 32-register read identifies what physical parameter occupies each value slot
 * in the first half) — extracted from the OEM Android app's Modbus logic.
 */
object ProbeChannel {
    const val TEMPERATURE = 0x1100
    const val MOISTURE = 0x1200
    const val SALINITY = 0x1300
    const val EC = 0x1400
    const val PH = 0x1500
    const val NITROGEN = 0x3700
    const val PHOSPHORUS = 0x3800
    const val POTASSIUM = 0x3900
}

sealed class ProbeException(message: String) : Exception(message) {
    object NoDeviceFound : ProbeException("No USB serial device found.")
    object PermissionDenied : ProbeException("USB permission denied.")
    object CannotOpen : ProbeException("Could not open the USB device.")
    object InvalidResponse : ProbeException("Unexpected response from the probe.")
    object CrcMismatch : ProbeException("CRC check failed — reading was corrupted.")
    object Timeout : ProbeException("Timed out waiting for the probe to respond.")
}
