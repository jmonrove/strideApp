@file:Suppress("DEPRECATION")
package dev.stride.lab

import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.graphics.Bitmap
import java.io.File
import org.json.JSONTokener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real API-22 WebView/native bridge test. It cannot load any motor driver. */
class ConsoleIntegrationTest : ActivityInstrumentationTestCase2<WorkoutLabActivity>(WorkoutLabActivity::class.java) {
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun js(expression: String): Any? {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val browser = web(activity.window.decorView) ?: throw AssertionError("Console WebView missing")
            browser.evaluateJavascript(expression) { result.set(it); done.countDown() }
        }
        assertTrue("WebView response timed out", done.await(5, TimeUnit.SECONDS))
        return JSONTokener(result.get()).nextValue()
    }
    private fun until(expression: String, timeoutMs: Long = 12_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (js(expression) == true) return
            Thread.sleep(100)
        }
        fail("Condition not met: $expression")
    }
    private fun click(action: String, id: String? = null) {
        val selector = "[data-action='$action']" + (id?.let { "[data-id='$it']" } ?: "")
        js("document.querySelector(\"$selector\").click(); true")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // WebView compositing follows the DOM update asynchronously on this API-22 GPU.
        Thread.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Screenshot unavailable")
        File(activity.cacheDir, "console-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun has(text: String) = "document.querySelector('#main').innerText.indexOf(${JSONObject.quote(text)})>=0"

    fun testConsoleBridgeLifecycleAndPersistence() {
        activity
        val prefs = activity.getSharedPreferences("console", 0)
        val historyBefore = prefs.getString("history", "[]")
        val countBefore = org.json.JSONArray(historyBefore).length()
        until("!!window.StrideUI && document.querySelectorAll('.workout').length===4")
        assertEquals("https://stride.local/console.html", js("location.href"))
        assertTrue(js("!!window.StrideConsole") == true)
        capture("home")
        click("nav", "history")
        until(has("Your sessions"))
        click("nav", "settings")
        until(has("Set your boundaries"))
        js("document.querySelector('#cap-speed').value='11.99';true")
        click("saveConfig")
        until("document.querySelector('#cap-speed').value==='11.99'")
        js("document.querySelector('#pace').click();true")
        click("saveConfig")
        until("document.querySelector('#cap-speed').value==='11.99'")
        js("document.querySelector('#units').value='kph';document.querySelector('#units').dispatchEvent(new Event('change',{bubbles:true}));true")
        until("document.querySelector('#cap-speed').value!=='11.99'")
        click("saveConfig")
        js("document.querySelector('#units').value='mph';document.querySelector('#units').dispatchEvent(new Event('change',{bubbles:true}));true")
        until("Math.abs(Number(document.querySelector('#cap-speed').value)-11.99)<0.00001")
        js("document.querySelector('#pace').checked=false;true")
        js("document.querySelector('#cap-speed').value='2.5';document.querySelector('#cap-grade').value='3';true")
        click("saveConfig")
        until("document.querySelector('#cap-speed').value==='2.5'")
        val deadline = System.currentTimeMillis() + 3000
        while (prefs.getFloat("maxSpeedMph", 0f) != 2.5f && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(2.5f, prefs.getFloat("maxSpeedMph", 0f))
        click("nav", "home")
        click("choose", "hills")
        until(has("Walking hills"))
        capture("setup")
        click("preflight")
        until("!!document.querySelector('#confirm-preview')")
        assertTrue(js("document.querySelector('[data-action=start]').disabled") == true)
        until("document.querySelector('#overlay').innerText.indexOf('Fresh heart-rate history is ready')>=0")
        js("document.querySelector('#confirm-preview').click();true")
        click("start")
        until(has("GUIDED SESSION"))
        click("visual", "track")
        until("!!document.querySelector('.trackcenter')")
        assertTrue("Live workout must fit the tablet height", js("document.querySelector('#main').scrollHeight<=document.querySelector('#main').clientHeight+2") == true)
        assertTrue("Track count stays inside its oval", js("(function(){var a=document.querySelector('.trackcenter').getBoundingClientRect(),b=document.querySelector('.visual').getBoundingClientRect();return a.top>=b.top && a.bottom<=b.bottom;})()") == true)
        js("window.__stopControl=document.querySelector('[data-action=stop]');true")
        until("document.querySelector('.telemetry strong').textContent>='0:03'")
        assertTrue("Stop remains mounted during telemetry updates", js("window.__stopControl===document.querySelector('[data-action=stop]')") == true)
        capture("track")
        click("visual", "profile")
        until("document.querySelector('svg').getAttribute('aria-label').indexOf('Planned incline')===0")
        capture("profile")
        click("visual", "hr")
        until("document.querySelector('svg').getAttribute('aria-label').indexOf('Recorded')===0")
        capture("heart-rate")
        click("speedUp")
        until(has("MANUAL CONTROL"))
        click("pause")
        until(has("SESSION PAUSED"))
        click("resumePaused")
        until(has("MANUAL CONTROL"))
        click("resume")
        until(has("GUIDED SESSION"))
        until("document.querySelector('.telemetry strong').textContent!=='0:00'")
        click("stop")
        until(has("SIMULATED SESSION SUMMARY"))
        until("!document.querySelector('[data-action=saveHistory]').disabled")
        capture("summary")
        click("saveHistory")
        until("document.querySelector('[data-action=saveHistory]').textContent==='Saved to history'")
        assertEquals(minOf(20, countBefore + 1), org.json.JSONArray(prefs.getString("history", "[]")).length())
        click("nav", "home")
        click("manualSetup")
        click("preflight")
        until("!!document.querySelector('#confirm-preview')")
        js("document.querySelector('#confirm-preview').click();true")
        click("start")
        until(has("MANUAL CONTROL"))
        click("nav", "sensors")
        click("switchSensor")
        until(has("Phone input selected"))
        click("nav", "live")
        click("pause")
        until(has("SESSION PAUSED"))
        click("resumePaused")
        until(has("MANUAL CONTROL"))
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity); instrumentation.callActivityOnResume(activity) }
        until(has("SESSION PAUSED"))
        click("resumePaused")
        until(has("MANUAL CONTROL"))
        click("stop")
        until(has("PHONE INPUT · SIMULATED WORKOUT"))
        // Restore defaults without deleting the deliberately saved synthetic history.
        click("nav", "settings")
        js("document.querySelector('#cap-speed').value='3';document.querySelector('#cap-grade').value='4';true")
        click("saveConfig")
        click("nav", "sensors")
        click("switchSensor")
        click("nav", "live")
        click("visual", "track")
        click("nav", "home")
        prefs.edit().putString("history", historyBefore).apply()
        assertTrue(js("document.querySelector('header').innerText.indexOf('MOTOR OFF')>=0") == true)
    }
}
