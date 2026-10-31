package dev.stride.motorbench

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Every visible control addresses only this in-memory simulated board. Intent extras are ignored. */
class BenchActivity : Activity() {
    private val board=SimulatedBoard()
    private val controller=BenchController(board)
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var trace: TextView
    private lateinit var keyButton: Button
    private lateinit var armButton: Button
    private lateinit var startButton: Button
    private var visible=false
    private var lastTime=0L
    private var actionError=""
    private val clock=object:Runnable {
        override fun run() {
            if(!visible) return
            val now=SystemClock.elapsedRealtime()
            board.advance((now-lastTime).coerceIn(0,750));lastTime=now
            controller.tick(now);render()
            handler.postDelayed(this,200)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(12,8,12,8);setBackgroundColor(Color.rgb(8,21,28)) }
        root.addView(label("STRIDE Motor Bench · SIMULATION",22f))
        root.addView(label("No physical motor connection. Hardware writes are locked in this build.",15f))
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this).apply { addView(content) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        status=label("",17f);content.addView(status)
        metrics=label("",16f);content.addView(metrics)
        val first=row(content)
        button(first,"Connect simulator") { controller.connect(SystemClock.elapsedRealtime()) }
        keyButton=button(first,"Simulator key OUT") { board.key(!board.keyPresent) }
        armButton=button(first,"ARM") { controller.arm(SystemClock.elapsedRealtime()) }
        val second=row(content)
        startButton=button(second,"Start 0.5 MPH") { controller.start(.5,SystemClock.elapsedRealtime()) }
        button(second,"Speed −0.1 MPH") { controller.speed(controller.targetMph()-.1,SystemClock.elapsedRealtime()) }
        button(second,"Speed +0.1 MPH") { controller.speed(controller.targetMph()+.1,SystemClock.elapsedRealtime()) }
        val third=row(content)
        button(third,"Grade −0.5%") { controller.grade(controller.targetGrade()-.5,SystemClock.elapsedRealtime()) }
        button(third,"Grade +0.5%") { controller.grade(controller.targetGrade()+.5,SystemClock.elapsedRealtime()) }
        val faults=row(content)
        checkbox(faults,"Drop replies") { board.noReply=it }
        checkbox(faults,"Freeze actuals") { board.freezeActual=it }
        checkbox(faults,"Corrupt replies") { board.corruptReply=it }
        trace=label("",13f);content.addView(trace)
        val stop=Button(this).apply {
            text="STOP SIMULATED MOTION";textSize=19f;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(160,47,40))
            setOnClickListener { perform { controller.stop(SystemClock.elapsedRealtime()) } }
        }
        root.addView(stop,LinearLayout.LayoutParams(-1,60))
        setContentView(root);render()
    }
    private fun label(value: String,size: Float)=TextView(this).apply { text=value;textSize=size;setTextColor(Color.rgb(218,236,241));setPadding(3,3,3,3) }
    private fun row(parent:LinearLayout)=LinearLayout(this).also { it.orientation=LinearLayout.HORIZONTAL;parent.addView(it) }
    private fun button(parent:LinearLayout,title:String,action:()->Unit)=Button(this).also {
        it.text=title;it.textSize=13f;parent.addView(it,LinearLayout.LayoutParams(0,50,1f));it.setOnClickListener { perform(action) }
    }
    private fun checkbox(parent:LinearLayout,title:String,action:(Boolean)->Unit) {
        val item=CheckBox(this).apply {
            text=title;textSize=13f;setTextColor(Color.WHITE)
            buttonTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
                intArrayOf(Color.rgb(41,201,180),Color.rgb(151,177,188)))
            setOnCheckedChangeListener { _,value->action(value) }
        }
        parent.addView(item,LinearLayout.LayoutParams(0,45,1f))
    }
    private fun perform(action:()->Unit) {
        actionError=""
        try { action() } catch(e:IllegalArgumentException) { actionError=e.message ?: "Outside bench limits" }
        catch(e:IllegalStateException) { actionError=e.message ?: "Action unavailable in current phase" }
        render()
    }
    private fun number(value:Double)=String.format(Locale.US,"%.2f",value)
    private fun render() {
        if(!::status.isInitialized) return
        status.text="${controller.phase}: ${controller.reason}" + if(actionError.isNotEmpty()) "\n$actionError" else ""
        val f=controller.feedback
        metrics.text=if(f==null) "Simulated board 14116 / part 388129 · bench cap 1 MPH / 2%" else
            "SIM target: ${number(f.targetSpeed/1.609344)} MPH / ${number(f.targetGrade)}%\n"+
            "SIM measured: ${number(f.actualSpeed/1.609344)} MPH / ${number(f.actualGrade)}% · mode ${f.mode}\n"+
            "Last response: ${SystemClock.elapsedRealtime()-f.at} ms ago · stop request ACK: ${controller.stopAcknowledged}"
        keyButton.text=if(board.keyPresent) "Simulator key IN" else "Simulator key OUT"
        armButton.isEnabled=controller.phase==BenchController.Phase.READY
        startButton.isEnabled=controller.phase==BenchController.Phase.ARMED
        trace.text="Recent simulated packets (not USB):\n"+board.trace.takeLast(6).joinToString("\n") {
            if(it.writes.isEmpty()) "READ command ${it.command}" else "WRITE "+it.writes.entries.joinToString { e->"${e.key.name}=${number(e.value)}" }
        }
        if(board.trace.size>100) board.trace.subList(0,board.trace.size-100).clear()
    }
    override fun onResume() { super.onResume();visible=true;lastTime=SystemClock.elapsedRealtime();handler.post(clock) }
    override fun onPause() { visible=false;handler.removeCallbacks(clock);controller.leave(SystemClock.elapsedRealtime());super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(clock);board.close();super.onDestroy() }
}
