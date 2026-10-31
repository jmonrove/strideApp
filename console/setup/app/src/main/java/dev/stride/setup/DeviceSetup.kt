package dev.stride.setup

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface

class DeviceSetup(private val context:Context) {
    private val commands=SystemCommands()
    private val backup=File(context.filesDir,"access-before.json")
    private val keys=listOf("ro.adb.secure","sys.adb.debug","persist.adb.tcp.port","service.adb.tcp.port","init.svc.adbd","sys.boot_completed")
    fun prop(name:String)=commands.run(listOf("/system/bin/getprop",name))
    fun marker(path:String):String {
        require(path in listOf(SetupPolicy.marker,SetupPolicy.marker+"/keepme"))
        val result=commands.run(listOf("/system/bin/sh","-c","if [ -L '$path' ]; then echo symlink; elif [ -d '$path' ]; then echo directory; elif [ -e '$path' ]; then echo other; else echo absent; fi"))
        check(result in setOf("absent","directory","symlink","other")) {"Cannot inspect marker"};return result
    }
    private fun packageVersion():Pair<String,Int> = try {
        @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo("com.ifit.eru",0)
        @Suppress("DEPRECATION") Pair(info.versionName ?: "",info.versionCode)
    } catch(_:Exception) {Pair("not installed",0)}
    private fun profile():SetupPolicy.Profile {
        val admin=packageVersion()
        return SetupPolicy.Profile(Build.MODEL,Build.VERSION.INCREMENTAL,Build.VERSION.SDK_INT,prop("ro.adb.secure"),admin.first,admin.second,
            Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED,0)==1)
    }
    fun inspect():JSONObject {
        val p=profile();val result=JSONObject().put("schema",1).put("model",p.model).put("build",p.build).put("api",p.api)
            .put("adminVersion",p.adminVersion).put("adminCode",p.adminCode).put("debugging",p.debugging)
        val properties=JSONObject();keys.forEach {properties.put(it,prop(it))};result.put("properties",properties)
        result.put("uid",commands.run(listOf("/system/bin/id")))
        result.put("bootId",try {File("/proc/sys/kernel/random/boot_id").readText().trim()} catch(_:Exception) {""})
        val markers=JSONObject();listOf(SetupPolicy.marker,SetupPolicy.marker+"/keepme").forEach {markers.put(it,marker(it))};result.put("markers",markers)
        val addresses=mutableListOf<String>()
        try {val interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()) {
            val iface=interfaces.nextElement();val ips=iface.inetAddresses
            while(ips.hasMoreElements()) {val ip=ips.nextElement();if(!ip.isLoopbackAddress && ip is java.net.Inet4Address) addresses.add(ip.hostAddress ?: "")}
        }} catch(_:Exception) {}
        result.put("ipv4",addresses.joinToString(", "));result.put("hasBackup",backup.exists())
        result.put("hardwareSupported",try {SetupPolicy.checkHardware(p);true} catch(_:Exception) {false})
        result.put("support",try {SetupPolicy.check(p);"Tested profile matches; this new app UID is not yet hardware validated"} catch(e:Exception) {e.message})
        return result
    }
    private fun prepare(requireDebugging:Boolean=true):JSONObject {
        if(requireDebugging) SetupPolicy.check(profile()) else SetupPolicy.checkHardware(profile())
        val state=inspect()
        val m=state.getJSONObject("markers")
        require(listOf(SetupPolicy.marker,SetupPolicy.marker+"/keepme").all {m.getString(it) in setOf("absent","directory")}) {"Marker path unsafe; inspect manually"}
        if(!backup.exists()) {
            check(backup.createNewFile()) {"Backup already exists"}
            FileOutputStream(backup).use {it.write(state.toString(2).toByteArray(Charsets.UTF_8));it.fd.sync()}
        }
        val original=JSONObject(backup.readText())
        require(original.getString("model")==Build.MODEL && original.getString("build")==Build.VERSION.INCREMENTAL && original.getInt("api")==22) {"Baseline does not match this device"}
        return original
    }
    fun usbDebugging():String {
        val p=profile();SetupPolicy.checkHardware(p)
        if(p.debugging) return "USB debugging is already enabled; no vendor request sent."
        prepare(requireDebugging=false)
        val changed=SetupPolicy.enableUsb(p,
            {context.sendBroadcast(Intent(SetupPolicy.vendorDebugAction).setPackage("android"))},
            {Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED,0)==1},
            {prop("ro.adb.secure")},{Thread.sleep(100)})
        return if(changed) "USB debugging setting verified enabled; authentication remains on. The vendor hook also requests its console/debug mode. Computer approval and transport still need checking."
            else "USB debugging was already enabled; no vendor request sent."
    }
    fun wifi():String {
        prepare()
        SetupPolicy.applyWifi(commands::run,::prop)
        return "Requested authenticated TCP ADB on 5555. Connect the computer and accept its RSA prompt. Reboot persistence still needs a real power cycle."
    }
    fun privileged():String {
        prepare();SetupPolicy.privileged.forEach {commands.run(it)}
        check(marker(SetupPolicy.marker+"/keepme")=="directory") {"Marker creation failed; check app storage permission"}
        return "Marker verified and privileged-mode broadcast sent. Confirm Android access visually; this is not proof of root or reboot persistence."
    }
    fun removeMarker():String {
        check(backup.exists()) {"No setup baseline: no marker removal allowed"}
        val original=prepare().getJSONObject("markers")
        val paths=listOf(SetupPolicy.marker,SetupPolicy.marker+"/keepme")
        val before=paths.associateWith {original.getString(it)};val current=paths.associateWith {marker(it)}
        for(command in SetupPolicy.removal(before,current)) {
            commands.run(command);check(marker(command[1])=="absent") {"Directory was not empty or removal denied; stopped"}
        }
        return "Removed only empty marker directories absent in the first baseline. Current privileged-mode setting and TCP ports were left as they were."
    }
    fun disableWifi():String {
        prepare()
        SetupPolicy.clearWifi(commands::run,::prop)
        return "TCP port properties cleared and adbd restart requested. Computer connection may close. USB debugging and computer authorization remain enabled."
    }
    fun verify():String {
        check(backup.exists()) {"No baseline available"};val before=JSONObject(backup.readText());val now=inspect()
        SetupPolicy.check(profile())
        val passed=SetupPolicy.rebootChecks(before.getString("bootId"),now.getString("bootId"),prop("sys.boot_completed"),prop("persist.adb.tcp.port"),prop("ro.adb.secure"),marker(SetupPolicy.marker+"/keepme"))
        check(passed) {"Reboot checks incomplete: power cycle manually, reconnect Wi-Fi, and check both setup steps"}
        return "Changed boot ID, completed boot, persistent port, authentication and marker verified. Confirm privileged mode visually. Root is not a pass condition."
    }
    fun baseline():String {check(backup.exists()) {"No baseline saved yet"};return backup.readText()}
}
