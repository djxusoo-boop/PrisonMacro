package es.macrostudio

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.macrostudio.core.*
import es.macrostudio.editor.LabActivity
import es.macrostudio.gestures.MacroAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GestureDeviceTest {
    @Test fun recordsTwoButtonsWithWaitAndReloadsBeforeReplay() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(MacroAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        val service=MacroAccessibilityService.instance ?: error("Servicio no conectado")
        val app=ins.targetContext
        val lab=ins.startActivitySync(Intent(app,LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ins.waitForIdleSync()
        // Activity startup can report idle before its entrance animation has settled.
        Thread.sleep(500)
        ins.waitForIdleSync()
        val prefs=app.getSharedPreferences("lab",0)
        val beforeA=prefs.getInt("a",0); val beforeB=prefs.getInt("b",0)
        var original: Project?=null
        val positions=mutableListOf<Pair<Float,Float>>()
        ins.runOnMainSync {
            Studio.init(app); original=Studio.project
            Studio.switch(Project(name="Grabación múltiple",delayMs=0))
            fun find(v: View,text: String): Button? {
                if(v is Button && v.text.toString()==text) return v
                if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),text)?.let {return it}
                return null
            }
            for(label in listOf("Pulsar A","Pulsar B")) {
                val b=find(lab.window.decorView,label) ?: error(label)
                val pos=IntArray(2); b.getLocationOnScreen(pos)
                positions.add(Pair(pos[0]+b.width/2f,pos[1]+b.height/2f))
            }
            service.startRecording()
        }
        try {
            for((index,pos) in positions.withIndex()) {
                Thread.sleep(450)
                val down=SystemClock.uptimeMillis()
                fun inject(action: Int) {
                    val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,pos.first,pos.second,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                    try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
                }
                inject(android.view.MotionEvent.ACTION_DOWN); Thread.sleep(90); inject(android.view.MotionEvent.ACTION_UP)
                var saved=false; val end=SystemClock.elapsedRealtime()+6000
                while(!saved && SystemClock.elapsedRealtime()<end) {
                    Thread.sleep(100)
                    ins.runOnMainSync {saved=Studio.project.actions.count {it.kind==Kind.GESTURE}==index+1}
                }
                assertTrue("Debe guardar el gesto ${index+1}",saved)
            }
            assertEquals("A tras grabar; puntos=$positions",beforeA+1,prefs.getInt("a",0)); assertEquals("B tras grabar; puntos=$positions",beforeB+1,prefs.getInt("b",0))
            ins.runOnMainSync {
                service.stopRecording()
                val recorded=Studio.project
                val actions=recorded.actions
                assertEquals(listOf(Kind.WAIT,Kind.GESTURE,Kind.WAIT,Kind.GESTURE),actions.map {it.kind})
                assertTrue("Debe conservar la espera entre botones",actions[2].durationMs>=400)
                val reloaded=es.macrostudio.projects.ProjectStore(app).list().single {it.id==recorded.id}
                assertEquals(recorded,reloaded)
                Studio.switch(reloaded)
                es.macrostudio.engine.Engine.start(false)
            }
            var running=true; val end=SystemClock.elapsedRealtime()+10000
            while(running && SystemClock.elapsedRealtime()<end) {Thread.sleep(100); ins.runOnMainSync {running=Studio.running}}
            assertFalse("La reproducción debe finalizar",running)
            assertEquals(beforeA+2,prefs.getInt("a",0)); assertEquals(beforeB+2,prefs.getInt("b",0))
            ins.runOnMainSync {assertTrue(Studio.status.startsWith("Completado"))}
        } finally {
            ins.runOnMainSync {service.stopRecording(); service.hideBar(); es.macrostudio.engine.Engine.stop(); Studio.switch(original!!); lab.finish()}
        }
    }
    private var oldServices: String?=null
    private var oldEnabled: String?=null
    private fun shell(command: String): String {
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    }
    @Before fun enableOnlyInDisposableEmulator() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.startsWith("sdk_gphone"))
        oldServices=shell("settings get secure enabled_accessibility_services"); oldEnabled=shell("settings get secure accessibility_enabled")
        shell("settings put secure enabled_accessibility_services null")
        val disconnectedBy=SystemClock.elapsedRealtime()+8000
        while(MacroAccessibilityService.instance!=null && SystemClock.elapsedRealtime()<disconnectedBy) Thread.sleep(100)
        assertNull("El servicio anterior debe desconectarse antes de habilitar el siguiente",MacroAccessibilityService.instance)
        Thread.sleep(300)
        shell("settings put secure enabled_accessibility_services es.macrostudio/es.macrostudio.gestures.MacroAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
    }
    @After fun restorePermissions() {
        oldServices?.let { shell("settings put secure enabled_accessibility_services $it") }
        oldEnabled?.let { shell("settings put secure accessibility_enabled $it") }
    }
    @Test fun accessibilityGestureActuallyClicksLabButton() {
        val ins=InstrumentationRegistry.getInstrumentation()
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(MacroAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        val service=MacroAccessibilityService.instance
        assertNotNull("El servicio debe conectarse en el emulador",service)
        val app=ins.targetContext
        val lab=ins.startActivitySync(Intent(app,LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ins.waitForIdleSync()
        val prefs=app.getSharedPreferences("lab",0); val before=prefs.getInt("a",0)
        val done=CountDownLatch(1); var accepted=false; var original: Project?=null
        ins.runOnMainSync {
            Studio.init(app); original=Studio.project; Studio.switch(Project(name="Prueba gesto físico"))
            fun find(v: View): Button? {
                if(v is Button && v.text.toString()=="Pulsar A") return v
                if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            val button=find(lab.window.decorView) ?: error("Botón de laboratorio ausente")
            val location=IntArray(2); button.getLocationOnScreen(location); val (w,h)=service!!.screenSize()
            val action=Action(points=listOf(Point((location[0]+button.width/2f)/(w-1),(location[1]+button.height/2f)/(h-1))))
            service.perform(action) { accepted=it; done.countDown() }
        }
        try { assertTrue("Falta callback",done.await(5,TimeUnit.SECONDS)); ins.waitForIdleSync(); assertTrue(accepted); assertEquals(before+1,prefs.getInt("a",0)) }
        finally { ins.runOnMainSync { Studio.switch(original!!); lab.finish() } }
    }
    @Test fun closeActiveFromRecentsDismissesLabAndReturnsHome() {
        val ins=InstrumentationRegistry.getInstrumentation()
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(MacroAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        val service=MacroAccessibilityService.instance
        assertNotNull("El servicio debe conectarse en el emulador",service)
        val lab=ins.startActivitySync(Intent(ins.targetContext,LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ins.waitForIdleSync(); Thread.sleep(400)
        val done=CountDownLatch(1); var success=false
        ins.runOnMainSync { service!!.closeActiveFromRecents(1000) { success=it; done.countDown() } }
        try {
            assertTrue("La acción de Recientes no terminó",done.await(6,TimeUnit.SECONDS))
            assertTrue("Android rechazó el descarte de la tarjeta",success)
            Thread.sleep(300)
            val activities=shell("dumpsys activity activities")
            val resumed=activities.lineSequence().firstOrNull { it.contains("topResumedActivity=") }
                ?: activities.lineSequence().firstOrNull { it.contains("mResumedActivity:") }
                ?: "No se encontró la actividad reanudada"
            assertFalse("El laboratorio no debe seguir en primer plano: $resumed",resumed.contains("LabActivity"))
        } finally { ins.runOnMainSync { if(!lab.isFinishing) lab.finish() } }
    }
    @Test fun opensLabPackageThenClosesItFromRecents() {
        val ins=InstrumentationRegistry.getInstrumentation()
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(MacroAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        val service=MacroAccessibilityService.instance
        assertNotNull("El servicio debe conectarse en el emulador",service)
        assertTrue("La acción Abrir aplicación debe lanzar el laboratorio de la propia app",service!!.performSystemAction(Action(kind=Kind.OPEN_APP,packageName="es.macrostudio")))
        Thread.sleep(900)
        val activities=shell("dumpsys activity activities")
        assertTrue("La aplicación de laboratorio debe quedar visible",activities.contains("es.macrostudio"))
        val done=CountDownLatch(1); var success=false
        service.closeActiveFromRecents(700) { success=it; done.countDown() }
        assertTrue("La acción de cierre debe terminar",done.await(6,TimeUnit.SECONDS))
        assertTrue("Android debe aceptar el cierre de la tarjeta del laboratorio",success)
        Thread.sleep(250)
        val after=shell("dumpsys activity activities")
        val resumed=after.lineSequence().firstOrNull { it.contains("topResumedActivity=") }
            ?: after.lineSequence().firstOrNull { it.contains("mResumedActivity:") }
            ?: "No se encontró la actividad reanudada"
        assertFalse("El laboratorio no debe seguir en primer plano: $resumed",resumed.contains("LabActivity"))
    }
    @Test fun assistedRecorderSavesAndReplaysRealTap() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(MacroAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        val service=MacroAccessibilityService.instance; assertNotNull(service)
        val app=ins.targetContext; val lab=ins.startActivitySync(Intent(app,LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ins.waitForIdleSync(); val prefs=app.getSharedPreferences("lab",0); val before=prefs.getInt("a",0)
        var original: Project?=null; var x=0f; var y=0f
        ins.runOnMainSync {
            Studio.init(app); original=Studio.project; Studio.switch(Project(name="Prueba grabador",delayMs=0))
            fun find(v: View): Button? { if(v is Button && v.text.toString()=="Pulsar A") return v; if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let{return it}; return null }
            val b=find(lab.window.decorView)!!; val pos=IntArray(2); b.getLocationOnScreen(pos); x=pos[0]+b.width/2f; y=pos[1]+b.height/2f
            service!!.startRecording()
        }
        try {
            Thread.sleep(300)
            val down=SystemClock.uptimeMillis()
            fun inject(action: Int,time: Long) { val event=android.view.MotionEvent.obtain(down,time,action,x,y,0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }; try { assertTrue(automation.injectInputEvent(event,true)) } finally {event.recycle()} }
            inject(android.view.MotionEvent.ACTION_DOWN,down); Thread.sleep(90); inject(android.view.MotionEvent.ACTION_UP,SystemClock.uptimeMillis())
            var saved=false; val end=SystemClock.elapsedRealtime()+6000
            while(!saved && SystemClock.elapsedRealtime()<end) { Thread.sleep(100); ins.runOnMainSync {saved=Studio.project.actions.any{it.kind==Kind.GESTURE}} }
            assertTrue("El grabador no guardó el gesto",saved)
            // El callback de dispatchGesture y el guardado pueden adelantarse al
            // onClick de la aplicación objetivo en un emulador cargado.
            val firstClickEnd=SystemClock.elapsedRealtime()+2000
            while(prefs.getInt("a",0)<before+1 && SystemClock.elapsedRealtime()<firstClickEnd) Thread.sleep(50)
            assertEquals(before+1,prefs.getInt("a",0))
            ins.runOnMainSync { service!!.stopRecording(); es.macrostudio.engine.Engine.start(false) }
            var running=true; val replayEnd=SystemClock.elapsedRealtime()+6000
            while(running && SystemClock.elapsedRealtime()<replayEnd) { Thread.sleep(100); ins.runOnMainSync {running=Studio.running} }
            assertFalse(running); assertEquals("La secuencia debe repetir la pulsación",before+2,prefs.getInt("a",0))
        } finally { ins.runOnMainSync { service!!.stopRecording(); service.hideBar(); es.macrostudio.engine.Engine.stop(); Studio.switch(original!!); lab.finish() } }
    }
}
