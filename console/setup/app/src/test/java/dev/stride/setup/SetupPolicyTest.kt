package dev.stride.setup
import org.junit.Assert.*
import org.junit.Test

class SetupPolicyTest {
    private val supported=SetupPolicy.Profile("MalataSamsungRoyalWolfSinglePartition","MGRSP_20210806",22,"1","2.2.20",1602,true)
    private fun reject(block:()->Unit) {try {block();fail("Expected rejection")} catch(_:IllegalArgumentException) {}}
    @Test fun exactProfileMatches() {SetupPolicy.check(supported)}
    @Test fun otherBuildsAreNotTreatedAsCompatible() {
        for(p in listOf(supported.copy(model="other"),supported.copy(build="new"),supported.copy(api=23),supported.copy(api=36))) reject {SetupPolicy.check(p)}
    }
    @Test fun authenticationAndManualDebuggingCannotBeBypassed() {
        reject {SetupPolicy.check(supported.copy(secure="0"))};reject {SetupPolicy.check(supported.copy(debugging=false))}
    }
    @Test fun adminVersionAndCodeMustBothMatch() {
        reject {SetupPolicy.check(supported.copy(adminVersion="2.2.20.1602"))};reject {SetupPolicy.check(supported.copy(adminCode=1603))}
    }
    @Test fun preexistingMarkersArePreserved() {
        val state=mapOf(SetupPolicy.marker to "directory",SetupPolicy.marker+"/keepme" to "directory")
        assertTrue(SetupPolicy.removal(state,state).isEmpty())
    }
    @Test fun onlyCreatedEmptyDirectoriesMayBeRemovedChildFirst() {
        val before=mapOf(SetupPolicy.marker to "absent",SetupPolicy.marker+"/keepme" to "absent")
        val after=before.mapValues {"directory"}
        val result=SetupPolicy.removal(before,after)
        assertEquals(listOf("/system/bin/rmdir",SetupPolicy.marker+"/keepme"),result[0])
        assertEquals(listOf("/system/bin/rmdir",SetupPolicy.marker),result[1])
    }
    @Test fun markerSymlinksOrUnknownStateAreRejected() {
        val before=mapOf(SetupPolicy.marker to "absent",SetupPolicy.marker+"/keepme" to "absent")
        reject {SetupPolicy.removal(before,before+mapOf(SetupPolicy.marker to "symlink"))}
        reject {SetupPolicy.removal(emptyMap(),before)}
    }
    @Test fun noRebootClaimWithoutChangedBootAndAuthenticatedPort() {
        assertTrue(SetupPolicy.rebootChecks("old","new","1","5555","1","directory"))
        assertFalse(SetupPolicy.rebootChecks("same","same","1","5555","1","directory"))
        assertFalse(SetupPolicy.rebootChecks("","new","1","5555","1","directory"))
        assertFalse(SetupPolicy.rebootChecks("old","new","0","5555","1","directory"))
        assertFalse(SetupPolicy.rebootChecks("old","new","1","","1","directory"))
        assertFalse(SetupPolicy.rebootChecks("old","new","1","5555","0","directory"))
        assertFalse(SetupPolicy.rebootChecks("old","new","1","5555","1","absent"))
    }
    @Test fun planDoesNotInvokeRootOrChangeAuthenticationPackagesOrMotors() {
        val plan=SetupPolicy.enableWifi+SetupPolicy.privileged+listOf(SetupPolicy.restart)
        assertFalse(plan.flatten().any {it in setOf("su","ro.adb.secure","disable-user","reboot","dev.stride.hud")})
        assertTrue(plan.all {it[0] in setOf("/system/bin/setprop","/system/bin/mkdir","/system/bin/am")})
    }
    @Test fun propertyDenialNeverRestartsAdbd() {
        val calls=mutableListOf<List<String>>()
        try {SetupPolicy.applyWifi({calls.add(it);""},{""});fail("Expected failure")} catch(_:IllegalStateException) {}
        assertEquals(1,calls.size);assertFalse(calls.contains(SetupPolicy.restart))
    }
    @Test fun wifiEnableVerifiesBothPropertiesBeforeRestart() {
        val props=mutableMapOf<String,String>();val calls=mutableListOf<List<String>>()
        SetupPolicy.applyWifi({calls.add(it);if(it[1]!="ctl.restart") props[it[1]]=it[2];""},{props[it] ?: ""})
        assertEquals("5555",props["persist.adb.tcp.port"]);assertEquals("5555",props["service.adb.tcp.port"])
        assertEquals(SetupPolicy.restart,calls.last());assertEquals(3,calls.size)
    }
    @Test fun wifiDisableVerifiesClearsBeforeRestart() {
        val calls=mutableListOf<List<String>>()
        SetupPolicy.clearWifi({calls.add(it);""},{""})
        assertEquals(SetupPolicy.restart,calls.last());assertTrue(calls.take(2).all {it[2].isEmpty()})
        calls.clear()
        try {SetupPolicy.clearWifi({calls.add(it);""},{"5555"});fail("Expected failure")} catch(_:IllegalStateException) {}
        assertFalse(calls.contains(SetupPolicy.restart))
    }
    @Test fun usbEnableCanBootstrapWhenDebuggingIsOff() {
        val off=supported.copy(debugging=false);SetupPolicy.checkHardware(off)
        var sends=0;var polls=0;var pauses=0
        assertTrue(SetupPolicy.enableUsb(off,{sends++},{polls++>=3},{"1"},{pauses++}))
        assertEquals(1,sends);assertTrue(pauses>0)
    }
    @Test fun usbAlreadyEnabledSendsNoVendorRequest() {
        var sends=0
        assertFalse(SetupPolicy.enableUsb(supported,{sends++},{true},{"1"},{fail("Should not wait")}))
        assertEquals(0,sends)
    }
    @Test fun usbTimeoutNeverRepeatsTheVendorRequest() {
        var sends=0;var pauses=0
        try {SetupPolicy.enableUsb(supported.copy(debugging=false),{sends++},{false},{"1"},{pauses++});fail("Expected timeout")} catch(_:IllegalStateException) {}
        assertEquals(1,sends);assertEquals(40,pauses)
    }
    @Test fun usbActionRejectsUnknownHardwareBeforeSending() {
        var sends=0
        reject {SetupPolicy.enableUsb(supported.copy(model="other",debugging=false),{sends++},{false},{"1"},{})}
        assertEquals(0,sends)
    }
    @Test fun usbActionNeverBypassesAuthentication() {
        var sends=0
        try {SetupPolicy.enableUsb(supported,{sends++},{false},{"0"},{});fail("Expected authentication guard")} catch(_:IllegalStateException) {}
        assertEquals(0,sends)
        var secureReads=0;var enabledReads=0
        try {SetupPolicy.enableUsb(supported.copy(debugging=false),{sends++},{enabledReads++>0},{if(secureReads++==0) "1" else "0"},{});fail("Expected post-check failure")} catch(_:IllegalStateException) {}
        assertEquals(1,sends)
    }
}
