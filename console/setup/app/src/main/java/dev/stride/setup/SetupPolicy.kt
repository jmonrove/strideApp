package dev.stride.setup

/** Fixed vendor setup actions only; no USB transport, motor commands or arbitrary shell input. */
object SetupPolicy {
    data class Profile(val model:String,val build:String,val api:Int,val secure:String,val adminVersion:String,val adminCode:Int,val debugging:Boolean)
    fun checkHardware(p:Profile) {
        require(p.model=="MalataSamsungRoyalWolfSinglePartition" && p.build=="MGRSP_20210806" && p.api==22) { "Unsupported tablet build; setup is locked" }
        require(p.adminVersion=="2.2.20" && p.adminCode==1602) { "Unsupported iFit Admin; setup is locked" }
        require(p.secure=="1") { "ADB authentication must remain enabled" }
    }
    fun check(p:Profile) {
        checkHardware(p)
        require(p.debugging) { "Enable USB debugging in Android Developer options first" }
    }
    const val vendorDebugAction="malata.adb.root"
    fun enableUsb(p:Profile,send:()->Unit,enabled:()->Boolean,secure:()->String,pause:()->Unit):Boolean {
        checkHardware(p)
        check(secure()=="1") {"ADB authentication state changed; stopped"}
        if(enabled()) return false
        send() // One request, never a retry loop. The inspected receiver sleeps for one second.
        repeat(40) {
            if(enabled()) {
                check(secure()=="1") {"Unexpected ADB authentication state; inspect manually"}
                return true
            }
            pause()
        }
        error("USB debugging was not confirmed. No automatic retry; use Android Developer options")
    }
    val enableWifi=listOf(listOf("/system/bin/setprop","persist.adb.tcp.port","5555"),
        listOf("/system/bin/setprop","service.adb.tcp.port","5555"))
    val restart=listOf("/system/bin/setprop","ctl.restart","adbd")
    fun applyWifi(run:(List<String>)->String,read:(String)->String) {
        for(command in enableWifi) {
            run(command)
            check(read(command[1])=="5555") {"Port write denied; stopped before daemon restart"}
        }
        run(restart)
    }
    fun clearWifi(run:(List<String>)->String,read:(String)->String) {
        for(name in listOf("persist.adb.tcp.port","service.adb.tcp.port")) {
            run(listOf("/system/bin/setprop",name,""))
            check(read(name).isEmpty()) {"Port clear failed; stopped before daemon restart"}
        }
        run(restart)
    }
    const val marker="/sdcard/.wolfDev"
    val privileged=listOf(listOf("/system/bin/mkdir","-p","$marker/keepme"),
        listOf("/system/bin/am","broadcast","-a","com.ifit.eru.PRIVILEGEDMODE","-n",
            "com.ifit.eru/com.ifit.eru.receivers.PrivilegedModeReceiver","--ez","PRIVILEGED_KEY","true"))
    fun removal(before:Map<String,String>,current:Map<String,String>):List<List<String>> {
        val paths=listOf("$marker/keepme",marker)
        require(paths.all {before[it] in setOf("absent","directory") && current[it] in setOf("absent","directory")}) { "Marker is not an ordinary directory" }
        return paths.filter {before[it]=="absent" && current[it]=="directory"}.map {listOf("/system/bin/rmdir",it)}
    }
    fun rebootChecks(before:String,after:String,bootComplete:String,port:String,secure:String,marker:String):Boolean =
        before.isNotBlank() && after.isNotBlank() && before!=after && bootComplete=="1" && port=="5555" && secure=="1" && marker=="directory"
}
