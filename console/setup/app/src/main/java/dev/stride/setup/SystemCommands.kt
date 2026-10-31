package dev.stride.setup

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/** Bounded fixed system commands in the app's own UID. No su, no downloaded code. */
class SystemCommands {
    fun run(command:List<String>):String {
        require(command.isNotEmpty() && command[0].startsWith("/system/bin/"))
        val builder=ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["PATH"]="/system/bin:/system/xbin"
        builder.environment().remove("LD_PRELOAD")
        val process=builder.start()
        val bytes=ByteArrayOutputStream()
        val overflow=java.util.concurrent.atomic.AtomicBoolean(false)
        val reader=Thread {
            try {
                process.inputStream.use { input ->
                    val buffer=ByteArray(1024)
                    while(true) {
                        val count=input.read(buffer);if(count<0) break
                        synchronized(bytes) {
                            if(bytes.size()+count>16384) {overflow.set(true);process.destroy();return@Thread}
                            bytes.write(buffer,0,count)
                        }
                    }
                }
            } catch(_:Exception) { overflow.set(true) }
        }.apply {isDaemon=true;start()}
        val deadline=SystemClock.elapsedRealtime()+10000
        try {
            var code:Int?=null
            while(code==null && SystemClock.elapsedRealtime()<deadline) {
                code=try {process.exitValue()} catch(_:IllegalThreadStateException) {Thread.sleep(25);null}
            }
            check(code!=null) {"System command timed out; no automatic retry"}
            reader.join(500)
            check(!reader.isAlive && !overflow.get()) {"System command output unavailable/too large"}
            val text=synchronized(bytes) {bytes.toString("UTF-8").trim()}
            check(code==0) {"System command denied/failed: $text"}
            check(!text.contains("SecurityException",true) && !text.contains("Error:",true)) {text}
            return text
        } finally {process.destroy()}

    }
}
