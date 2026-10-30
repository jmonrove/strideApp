package dev.stride.usbprobe

import org.junit.Assert.*
import org.junit.Test

class UsbClaimPolicyTest {
    @Test fun normalSuccessfulClaimNeverReadsDriverOrAttemptsForce() {
        val attempts = mutableListOf<Boolean>()
        assertTrue(UsbClaimPolicy.claim(true, { error("No driver lookup needed") }) { attempts.add(it); true })
        assertEquals(listOf(false), attempts)
    }

    @Test fun refusalStaysARefusalWithoutExplicitOptIn() {
        val attempts = mutableListOf<Boolean>()
        assertFalse(UsbClaimPolicy.claim(false, { error("No detach approved") }) { attempts.add(it); false })
        assertEquals(listOf(false), attempts)
    }

    @Test fun onlyKnownHidDriverCanUseTheApprovedFallback() {
        for (driver in listOf(null, "usbfs", "unknown", "hub", "usbhid")) {
            val attempts = mutableListOf<Boolean>()
            try {
                val claimed = UsbClaimPolicy.claim(true, { driver }) { attempts.add(it); it }
                assertEquals("usbhid", driver); assertTrue(claimed)
            } catch (_: IllegalStateException) { assertNotEquals("usbhid", driver) }
            assertEquals(if (driver == "usbhid") listOf(false, true) else listOf(false), attempts)
        }
    }
}
