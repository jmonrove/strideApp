package dev.stride.motorbench

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** Prepared bounded transport. Not instantiated by BenchActivity; all control writes are rejected. */
class LockedUsbLink(context: Context) : BoardLink {
    override val kind=BoardLink.Kind.PHYSICAL
    private val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection?=null
    private var iface: UsbInterface?=null
    private var input: UsbEndpoint?=null
    private var output: UsbEndpoint?=null
    @Synchronized fun open(device: UsbDevice) {
        check(connection==null)
        require(device.vendorId==0x213c && device.productId==2 && device.interfaceCount==1)
        check(manager.hasPermission(device)) { "Explicit USB permission required; this class never requests it" }
        val candidate=device.getInterface(0)
        require(candidate.interfaceClass==UsbConstants.USB_CLASS_HID && candidate.endpointCount==2)
        val endpoints=(0 until candidate.endpointCount).map { candidate.getEndpoint(it) }
        val inn=endpoints.single { it.address==0x81 }
        val out=endpoints.single { it.address==0x02 }
        require(endpoints.all { it.type==UsbConstants.USB_ENDPOINT_XFER_INT && it.maxPacketSize==64 })
        val conn=manager.openDevice(device) ?: error("USB device unavailable")
        if(!conn.claimInterface(candidate,false)) { conn.close();error("USB busy; no forced claim or HID detach performed") }
        connection=conn;iface=candidate;input=inn;output=out
    }
    @Synchronized override fun exchange(request: ByteArray): ByteArray? {
        val frame=request.copyOf()
        HardwareGate.check(frame) // Before touching a USB connection, including on a Stop request.
        val conn=connection ?: return null
        val out=output ?: return null
        val inn=input ?: return null
        try {
            if(conn.bulkTransfer(out,frame,frame.size,200)!=frame.size) { close();return null }
            Thread.sleep(if(MotorWire.u(frame[2])==2) 80 else 400)
            val reply=ByteArray(64)
            val count=conn.bulkTransfer(inn,reply,reply.size,1000)
            if(count<=0) { close();return null }
            return reply.copyOf(count)
        } catch(_:Exception) { close();return null }
    }
    @Synchronized override fun close() {
        val conn=connection
        try { iface?.let { conn?.releaseInterface(it) } } finally {
            conn?.close();connection=null;iface=null;input=null;output=null
        }
    }
}
