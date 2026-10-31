package dev.stride.setup

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object Payloads {
    private val allowed=setOf("termux.apk","preview.apk","termux-source.tar.gz")
    fun metadata(context:Context):JSONObject = try {JSONObject(context.assets.open("payloads.json").bufferedReader().use {it.readText()})} catch(_:Exception) {JSONObject()}
    fun available(context:Context,name:String)=name in allowed && metadata(context).has(name)
    fun file(context:Context,name:String):File {
        require(name in allowed);return File(context.cacheDir,"payload-$name")
    }
    fun prepare(context:Context,name:String):File {
        require(available(context,name)) {"This payload is not included in the core APK"}
        val info=metadata(context).getJSONObject(name);val target=file(context,name)
        val asset=info.optString("asset","payloads/$name")
        require(asset=="payloads/$name" || name=="termux-source.tar.gz" && asset=="payloads/termux-source.bin")
        val tmp=File(context.cacheDir,"payload-copy.tmp")
        val digest=MessageDigest.getInstance("SHA-256")
        try {
            context.assets.open(asset).use {input -> FileOutputStream(tmp).use {output ->
                val buffer=ByteArray(65536)
                while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n);output.write(buffer,0,n)}
                output.fd.sync()
            }}
            val hash=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
            check(hash==info.getString("sha256")) {"Payload checksum mismatch; refusing to share"}
            if(target.exists()) check(target.delete())
            check(tmp.renameTo(target)) {"Cannot stage installer payload"}
            return target
        } finally {tmp.delete()}
    }
}
