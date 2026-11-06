package es.macrostudio.editor

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import es.macrostudio.core.Studio
import es.macrostudio.core.*
import es.macrostudio.engine.Engine
import es.macrostudio.gestures.MacroAccessibilityService

/** Controlled target for real end-to-end tests without touching games, purchases or accounts. */
class LabActivity: Activity() {
    private var originalProject: Project?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Studio.init(this)
        val prefs=getSharedPreferences("lab",0)
        // Keep both recorder targets inside the visible display area. The
        // laboratory is also used to inject real coordinates; placing B under
        // the navigation bar would test the launcher, not the recorder.
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,80,32,48); setBackgroundColor(Color.rgb(18,24,37)) }
        fun label(value: String)=TextView(this).apply { text=value; textSize=20f; setTextColor(Color.WHITE); setPadding(8,20,8,20) }
        root.addView(label("LABORATORIO\nPrueba aquí, sin afectar otras apps."))
        val counter=label("")
        fun update() { counter.text="Pulsaciones A: ${prefs.getInt("a",0)} · B: ${prefs.getInt("b",0)}" }
        val target=label("◆  PRISONMACRO  ◆\nIMAGEN DE PRUEBA  7 • 2 • 9").apply { setBackgroundColor(Color.rgb(10,108,124)); gravity=android.view.Gravity.CENTER; contentDescription="Imagen de prueba PrisonMacro" }
        root.addView(target,LinearLayout.LayoutParams(-1,180))
        root.addView(counter); update()
        val targets=mutableListOf<Button>()
        for(key in listOf("a","b")) root.addView(Button(this).apply { targets.add(this); text="Pulsar ${key.uppercase()}"; setOnClickListener { prefs.edit().putInt(key,prefs.getInt(key,0)+1).apply(); update() } })
        root.addView(Button(this).apply { text="Mover imagen"; setOnClickListener { target.translationX=if(target.translationX==0f) 45f else 0f; target.translationY=if(target.translationY==0f) 30f else 0f } })
        root.addView(Button(this).apply { text="Ocultar / mostrar imagen"; setOnClickListener { target.alpha=if(target.alpha==1f) 0f else 1f } })
        root.addView(Button(this).apply { text="Panel flotante"; setOnClickListener { MacroAccessibilityService.instance?.showBar() ?: Studio.fail("Activa accesibilidad") } })
        root.addView(Button(this).apply { text="Probar imagen → A → esperar → B"; setOnClickListener {
            if(Studio.running || Studio.recording || Studio.detecting) return@setOnClickListener
            val service=MacroAccessibilityService.instance
            if(service==null || !Studio.captureReady || Studio.project.template==null) {
                Toast.makeText(this@LabActivity,"Autoriza captura y accesibilidad, y guarda antes la imagen del laboratorio",Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val (w,h)=service.screenSize()
            val gestures=targets.map { button ->
                val pos=IntArray(2); button.getLocationOnScreen(pos)
                Action(name="Laboratorio ${button.text}",points=listOf(Point((pos[0]+button.width/2f)/(w-1),(pos[1]+button.height/2f)/(h-1))))
            }
            val original=originalProject ?: Studio.project
            val test=Studio.project.copy(id=java.util.UUID.randomUUID().toString(),name="Prueba visual de laboratorio",delayMs=500,intervalMs=0,timeoutMs=10_000,maxRuns=1,actions=listOf(gestures[0],Action(kind=Kind.WAIT,name="Espera de laboratorio",durationMs=500),gestures[1]))
            if(Studio.update(test,false)) {
                originalProject=original
                Engine.start()
                Toast.makeText(this@LabActivity,"Esperando plantilla: A, 500 ms, B. Al salir se restaura tu proyecto.",Toast.LENGTH_LONG).show()
            }
        } })
        root.addView(Button(this).apply { text="Volver al editor"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply {
            isFillViewport=true
            addView(root,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        })
    }
    override fun onPause() {
        if(originalProject!=null) Engine.stop("Prueba de laboratorio detenida al salir")
        super.onPause()
    }
    override fun onDestroy() {
        originalProject?.let { Studio.switch(it) }
        super.onDestroy()
    }
}
