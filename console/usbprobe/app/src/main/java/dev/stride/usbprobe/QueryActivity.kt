package dev.stride.usbprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import java.io.File

class QueryActivity : Activity() {
    companion object {
        const val TAG = "StrideBoardRead"
        private const val PERMISSION = "dev.stride.usbprobe.QUERY_PERMISSION"
    }
    private lateinit var output: TextView
    private lateinit var start: Button
    private lateinit var allowHidDetach: CheckBox
    private lateinit var manager: UsbManager
    @Volatile private var cancelled = false
    @Volatile private var connection: UsbDeviceConnection? = null
    private var permissionPending = false
    private var worker: Thread? = null
    private var detachRequested = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PERMISSION || !permissionPending || cancelled) return
            permissionPending = false
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && matches(device) && manager.hasPermission(device) &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) inspect(device)
            else report("USB permission denied; no queries sent.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16) }
        val explanation = TextView(this).apply {
            text = "Board identity/status — query only\nWorkout stopped, stand off belt, safety key removed.\nNo interface is claimed until you press the button."
            textSize = 17f; setTextColor(Color.BLACK)
        }
        root.addView(explanation)
        allowHidDetach = CheckBox(this).apply {
            text = "Allow temporary kernel HID detach for this read test"
            isChecked = false
        }
        root.addView(allowHidDetach)
        start = Button(this).apply {
            text = "Read once — stopped and safety key out"
            setOnClickListener { requestPermission() }
        }
        root.addView(start)
        output = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(Color.BLACK) }
        root.addView(output)
        setContentView(ScrollView(this).apply { addView(root) })
        val filter = IntentFilter(PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter) }
        report("READY: no queries sent. A busy interface or any failed reply stops the probe.")
    }

    private fun matches(device: UsbDevice) = device.vendorId == 0x213c && device.productId == 2

    private fun requestPermission() {
        start.isEnabled = false
        detachRequested = allowHidDetach.isChecked
        allowHidDetach.isEnabled = false
        val devices = manager.deviceList.values.filter(::matches)
        if (devices.size != 1) { report("Expected exactly one ICON 213c:0002 device; found ${devices.size}."); return }
        val device = devices.single()
        if (manager.hasPermission(device)) inspect(device)
        else {
            permissionPending = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(this, 1, Intent(PERMISSION).setPackage(packageName), flags))
        }
    }

    private fun inspect(device: UsbDevice) {
        if (cancelled || worker != null) return
        worker = thread(name = "query-only-board") {
            var conn: UsbDeviceConnection? = null
            var claimed: android.hardware.usb.UsbInterface? = null
            try {
                check(!cancelled)
                val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
                    .singleOrNull { candidate ->
                        val endpoints = (0 until candidate.endpointCount).map { candidate.getEndpoint(it) }
                        endpoints.any { it.address == 0x81 && it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.maxPacketSize == 64 } &&
                            endpoints.any { it.address == 0x02 && it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.maxPacketSize == 64 }
                    } ?: error("Expected 64-byte interrupt endpoints 81/02; stopped.")
                conn = manager.openDevice(device) ?: error("openDevice failed; stopped.")
                connection = conn
                check(!cancelled)
                val active = conn
                val didClaim = UsbClaimPolicy.claim(detachRequested, ::kernelDriverName) { force ->
                    check(!cancelled)
                    if (force) report("User allowed temporary usbhid detach; claiming this interface only.")
                    active.claimInterface(intf, force)
                }
                check(didClaim) { "Interface busy or kernel driver owns it; stopped." }
                claimed = intf
                val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                val input = endpoints.single { it.address == 0x81 }
                val out = endpoints.single { it.address == 0x02 }
                report("Claimed interface. Beginning bounded query plan.")
                val wire = QueryWire(write = { request ->
                    check(!cancelled)
                    active.bulkTransfer(out, request, request.size, 300)
                }, read = { timeout ->
                    check(!cancelled)
                    val bytes = ByteArray(64)
                    val count = active.bulkTransfer(input, bytes, bytes.size, timeout)
                    if (count > 0) bytes.copyOf(count) else null
                }, wait = { Thread.sleep(it) }, report = ::report)
                wire.discardPendingInput()
                BoardInspection(exchange = wire::exchange, report = ::report).run()
            } catch (e: Exception) {
                report("STOPPED: ${if (cancelled) "cancelled" else e.message ?: e.javaClass.simpleName}")
            } finally {
                try { claimed?.let { conn?.releaseInterface(it) } } catch (_: Exception) { }
                try { conn?.close() } catch (_: Exception) { }
                connection = null
                report("Connection closed. No control requests were sent.")
            }
        }
    }

    private fun kernelDriverName(): String? = try {
        val devices = File("/sys/bus/usb/devices").listFiles().orEmpty().filter { dir ->
            try { File(dir, "idVendor").readText().trim() == "213c" && File(dir, "idProduct").readText().trim() == "0002" }
            catch (_: Exception) { false }
        }
        val device = devices.singleOrNull()
        val drivers = if (device == null) emptyList() else File("/sys/bus/usb/devices").listFiles().orEmpty()
            .filter { it.name.startsWith(device.name + ":") }
            .map { File(it, "driver").canonicalFile.name }
        drivers.singleOrNull()
    } catch (_: Exception) { null }

    private fun report(message: String) {
        Log.i(TAG, message)
        runOnUiThread { if (!isFinishing && !isDestroyed) output.append(message + "\n") }
    }

    override fun onStop() {
        cancelled = true
        permissionPending = false
        connection?.close()
        worker?.interrupt()
        super.onStop()
    }

    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
}
