package dev.stride.usbprobe

/** Force is an explicit fallback for a known kernel HID driver, never a generic busy-device retry. */
object UsbClaimPolicy {
    fun claim(allowHidDetach: Boolean, kernelDriver: () -> String?, tryClaim: (Boolean) -> Boolean): Boolean {
        if (tryClaim(false)) return true
        if (!allowHidDetach) return false
        val driver = kernelDriver()
        check(driver == "usbhid") { "HID detach allowed, but driver is ${driver ?: "unknown"}; stopped." }
        return tryClaim(true)
    }
}
