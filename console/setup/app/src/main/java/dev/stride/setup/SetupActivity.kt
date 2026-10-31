package dev.stride.setup

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

class SetupActivity:Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var setup:DeviceSetup
    private lateinit var details:TextView
    private lateinit var status:TextView
    private lateinit var wifi:Switch
    private lateinit var privileged:Switch
    private lateinit var usbButton:Button
    private val mutationControls=mutableListOf<android.view.View>()
    private var state:JSONObject?=null
    private var busy=false
    private var rendering=false
    private var alive=true
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);setup=DeviceSetup(this)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(12,8,12,8);setBackgroundColor(Color.rgb(9,22,29))}
        root.addView(label("STRIDE Console Setup",23f))
        root.addView(label("Android 5.1 access setup · no motor control",15f))
        status=label("Inspecting this tablet…",16f);root.addView(status)
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply {addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        details=label("",14f);content.addView(details)
        usbButton=button(row(content),"Enable USB debugging") {
            confirm("Enable USB debugging using vendor hook",
                "This firmware's Root Grant hook requests USB debugging, starts the vendor console service and sets sys.adb.debug=1. It may make adbd run as root for this boot. It does not install permanent root, disable authentication, or approve a computer. Apply once, then verify the result; use Developer options if it is refused.") {setup.usbDebugging()}
        }
        privileged=toggle(content,"Keep privileged mode after reboot") {checked ->
            confirm(if(checked) "Keep privileged mode" else "Remove setup marker",
                if(checked) "Create the version-specific marker and request privileged mode now. No packages are disabled. Confirm its effect visually."
                else "Remove only empty marker directories created by this app. Existing markers are preserved. Current privileged mode stays active until you exit it manually.") {
                if(checked)setup.privileged() else setup.removeMarker()
            }
        }
        wifi=toggle(content,"Wi-Fi ADB debugging · TCP 5555") {checked ->
            confirm(if(checked) "Enable Wi-Fi ADB" else "Disable Wi-Fi ADB",
                if(checked) "Enable authenticated TCP ADB on your LAN and restart adbd. Approve the computer's RSA prompt on the tablet."
                else "Clear both TCP port properties and restart adbd. This may disconnect the computer. Local Android settings and Termux remain available.") {
                if(checked)setup.wifi() else setup.disableWifi()
            }
        }
        content.addView(label("Switches report saved port/marker state. No permanent root is installed. Enable USB debugging first, then Wi-Fi ADB.",14f))
        val first=row(content)
        button(first,"Inspect / refresh") {refresh()}
        button(first,"Developer options") {startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))}
        button(first,"Verify after reboot") {execute {setup.verify()}}
        val second=row(content)
        button(second,"View first backup") {execute(false) {showText("First setup baseline",setup.baseline());"Backup shown locally; it has not been uploaded."}}
        button(second,"Setup guide") {execute(false) {val text=assets.open("README.md").bufferedReader().use {it.readText()};showText("Setup guide",text);"Guide shown."}}
        button(second,"Termux notices") {execute(false) {
            val text=try {assets.open("TERMUX-NOTICES.txt").bufferedReader().use {it.readText()}} catch(_:Exception) {"Core build: no Termux APK bundled. Official project: https://github.com/termux/termux-app"}
            showText("Independent Termux package",text);"Notices shown."
        }}
        val third=row(content)
        payloadButton(third,"Install Termux (optional)","termux.apk")
        payloadButton(third,"Install STRIDE Preview","preview.apk")
        button(third,"Open Termux") {val intent=packageManager.getLaunchIntentForPackage("com.termux");check(intent!=null) {"Termux is not installed"};startActivity(intent)}
        content.addView(label("The bundle uses Android's installer: each package needs your approval. The core build omits APK payloads. Termux is a fallback; direct setup from this app UID still needs a physical test.",14f))
        val fourth=row(content)
        button(fourth,"Copy Termux fallback") {execute(false) {
            val script=assets.open("enable-wifi-adb.sh").bufferedReader().use {it.readText()}
            runOnUiThread {if(alive) {
                val clipboard=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("STRIDE Wi-Fi ADB setup","/system/bin/sh -s -- --apply <<'STRIDE_ADB_SETUP'\n$script\nSTRIDE_ADB_SETUP\n"))
                showText("Termux fallback copied","Open Termux, paste, review, and press Enter. This runs the documented firmware-guarded Wi-Fi ADB script from Termux's UID. No network download is needed. It does not install root or create the privileged-mode marker.")
            }}
            "Fallback script copied for manual review and execution in Termux."
        }}
        button(fourth,"Share Termux source archive") {sharePayload("termux-source.tar.gz",false)}.isEnabled=Payloads.available(this,"termux-source.tar.gz")
        setContentView(root);refresh()
    }
    private fun label(value:String,size:Float)=TextView(this).apply {text=value;textSize=size;setTextColor(Color.rgb(220,237,241));setPadding(3,3,3,3)}
    private fun row(parent:LinearLayout)=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;parent.addView(this)}
    private fun button(parent:LinearLayout,title:String,action:()->Unit)=Button(this).apply {
        text=title;textSize=13f;parent.addView(this,LinearLayout.LayoutParams(0,52,1f))
        setOnClickListener {try {action()} catch(e:Exception) {status.text=e.message ?: "Action unavailable"}}
    }
    private fun toggle(parent:LinearLayout,title:String,change:(Boolean)->Unit)=Switch(this).apply {
        text=title;textSize=18f;setTextColor(Color.WHITE);setPadding(4,8,4,8)
        val tint=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(33,192,172),Color.rgb(145,164,174)))
        thumbDrawable?.mutate()?.setTintList(tint);trackDrawable?.mutate()?.setTintList(tint)
        parent.addView(this,LinearLayout.LayoutParams(-1,60));mutationControls.add(this)
        setOnCheckedChangeListener {_,value->if(!rendering && !busy) change(value)}
    }
    private fun payloadButton(parent:LinearLayout,title:String,name:String) {
        val item=button(parent,title) {sharePayload(name,true)}
        item.isEnabled=Build.VERSION.SDK_INT==22 && Payloads.available(this,name)
    }
    private fun sharePayload(name:String,install:Boolean) {
        if(install) check(Build.VERSION.SDK_INT==22) {"Installer payload targets Android 5; unsupported device"}
        execute(false) {
            Payloads.prepare(this,name)
            runOnUiThread {if(alive) {
                val uri=Uri.parse("content://dev.stride.setup.payload/$name")
                val intent=if(install) Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    else Intent(Intent.ACTION_SEND).setType("application/gzip").putExtra(Intent.EXTRA_STREAM,uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData=ClipData.newRawUri("STRIDE setup payload",uri)
                try {startActivity(intent)} catch(e:Exception) {status.text=e.message ?: "No installer/share application available"}
            }}
            "Payload checksum verified; Android will handle installation/sharing. No package is installed silently."
        }
    }
    private fun confirm(title:String,message:String,action:()->String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Apply") {_,_->execute {action()}}
            .setNegativeButton("Cancel") {_,_->render()}.setOnCancelListener {render()}.show()
    }
    private fun showText(title:String,text:String) {runOnUiThread {if(alive) {
        val view=label(text,14f).apply {setTextColor(Color.BLACK);setTextIsSelectable(true)}
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply {addView(view)}).setPositiveButton("Close",null).show()
    }}}
    private fun execute(refreshAfter:Boolean=true,action:()->String) {
        if(busy) return;busy=true;render();status.text="Working locally…"
        worker.execute {
            val result=try {action()} catch(e:Exception) {"Stopped: ${e.message}. No automatic retry."}
            val updated=if(refreshAfter) try {setup.inspect()} catch(_:Exception) {null} else null
            runOnUiThread {if(alive) {if(updated!=null) state=updated;busy=false;status.text=result;render()}}
        }
    }
    private fun refresh() {execute {"Inspection finished; no settings changed."}}
    private fun render() {
        if(!::details.isInitialized) return
        val s=state
        details.text=if(s==null) "Waiting for inspection" else "${s.optString("model")} · API ${s.optInt("api")} · ${s.optString("build")}\n"+
            "Admin ${s.optString("adminVersion")} (${s.optInt("adminCode")}) · USB debugging ${s.optBoolean("debugging")}\n"+
            "LAN ${s.optString("ipv4")} · app ${s.optString("uid").substringBefore(' ')}\n${s.optString("support")}"
        val supported=s?.optString("support")?.startsWith("Tested profile matches")==true
        usbButton.isEnabled=!busy && s?.optBoolean("hardwareSupported")==true && !s.optBoolean("debugging")
        usbButton.text=if(s?.optBoolean("debugging")==true) "USB debugging enabled" else "Enable USB debugging"
        mutationControls.forEach {it.isEnabled=!busy && supported}
        rendering=true
        wifi.isChecked=s?.optJSONObject("properties")?.optString("persist.adb.tcp.port")=="5555"
        privileged.isChecked=s?.optJSONObject("markers")?.optString(SetupPolicy.marker)=="directory"
        rendering=false
    }
    override fun onDestroy() {alive=false;worker.shutdown();super.onDestroy()}
}
