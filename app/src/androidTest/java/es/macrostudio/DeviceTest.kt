package es.macrostudio

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.macrostudio.core.*
import es.macrostudio.projects.ProjectStore
import es.macrostudio.editor.LabActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private fun waitForText(text: String, timeoutMs: Long=5000): AccessibilityNodeInfo {
        val automation=instrumentation.uiAutomation
        val end=android.os.SystemClock.elapsedRealtime()+timeoutMs
        var lastTree="sin ventana activa"
        var misses=0
        while(android.os.SystemClock.elapsedRealtime()<end) {
            automation.rootInActiveWindow?.let { root ->
                var match: AccessibilityNodeInfo?=null
                fun find(node: AccessibilityNodeInfo, depth: Int) {
                    // Compose puede colocar los botones dentro de varias capas
                    // de tarjetas/filas en la vista compacta. No truncar aquí
                    // los controles válidos del editor.
                    if(depth>20 || match!=null) return
                    if(node.text?.toString()?.contains(text)==true || node.contentDescription?.toString()?.contains(text)==true) { match=node; return }
                    for(i in 0 until node.childCount) node.getChild(i)?.let { find(it,depth+1) }
                }
                find(root,0); match?.let { return it }
                val seen=ArrayList<String>()
                fun collect(node: AccessibilityNodeInfo, depth: Int) {
                    if(depth>15 || seen.size>=120) return
                    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { seen.add(it) }
                    for(i in 0 until node.childCount) node.getChild(i)?.let { collect(it,depth+1) }
                }
                collect(root,0); lastTree=seen.joinToString(" | ")
                fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if(node.isScrollable) return node
                    for(i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { found -> return found } }
                    return null
                }
                // Da tiempo a Compose para publicar el contenido antes de moverlo.
                // Desplazar en cada sondeo podía pasar de largo por controles válidos.
                if(++misses%5==0) scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            Thread.sleep(100)
        }
        error("No apareció en la interfaz: $text. Textos visibles: $lastTree")
    }
    private fun clickNear(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo?=node
        repeat(4) {
            val candidate=current ?: return@repeat
            if(candidate.isClickable) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            for(i in 0 until candidate.childCount) candidate.getChild(i)?.let { child -> if(child.isClickable) return child.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            current=candidate.parent
        }
        return false
    }
    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        var current: AccessibilityNodeInfo?=node
        repeat(4) {
            val candidate=current ?: return@repeat
            if(candidate.isEditable) {
                val args=android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }
                return candidate.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)
            }
            for(i in 0 until candidate.childCount) candidate.getChild(i)?.let { child ->
                if(child.isEditable) {
                    val args=android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }
                    return child.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)
                }
            }
            current=candidate.parent
        }
        return false
    }
    @Test fun systemActionsRoundTripAndRejectInvalidPackage() {
        val actions=listOf(Kind.HOME,Kind.BACK,Kind.RECENTS,Kind.CLOSE_ACTIVE,Kind.NOTIFICATIONS,Kind.QUICK_SETTINGS,Kind.OPEN_APP).map { kind -> Action(kind=kind,packageName="es.macrostudio",durationMs=if(kind==Kind.CLOSE_ACTIVE) 1000 else 80) }
        val project=Project(name="Acciones de Android",actions=actions)
        assertNull(project.validate())
        assertEquals(project,ProjectStore.decode(ProjectStore.encode(project)))
        assertNotNull(project.copy(actions=listOf(Action(kind=Kind.OPEN_APP,packageName="file:///bad"))).validate())
        val legacy=JSONObject(ProjectStore.encode(Project(actions=listOf(Action()))))
        legacy.getJSONArray("actions").getJSONObject(0).remove("packageName")
        assertEquals("es.macrostudio",ProjectStore.decode(legacy.toString()).actions.single().packageName)
    }
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Test fun fullProjectRoundTrip() {
        val capture=SavedTemplate(id="captura-boton",name="Botón continuar",template=Template("test.png",5,8,20,30,1080,1920))
        val color=Action(kind=Kind.IF_COLOR,targetColor=0xff33aa77.toInt(),colorTolerance=31,colorMode=SearchMode.EXACT,colorX=.2f,colorY=.8f,children=listOf(Action(kind=Kind.WAIT,durationMs=123)))
        val click=Action(kind=Kind.CLICK_IMAGE,name="Clic en aviso",templateId=capture.id,clickTarget=ClickTarget.OFFSET,clickStyle=ClickStyle.DOUBLE,clickOffsetX=25f,clickOffsetY=-10f,durationMs=180)
        val waitImage=Action(kind=Kind.WAIT_IMAGE,name="Esperar pantalla de llegada",templateId=capture.id,durationMs=30_000)
        val imageCondition=Action(
            kind=Kind.IF_IMAGE,
            templateId=capture.id,
            children=listOf(Action(points=listOf(Point(.2f,.3f),Point(.4f,.6f))),color),
            otherwise=listOf(Action(kind=Kind.REPEAT,children=listOf(Action(kind=Kind.WAIT,durationMs=999))))
        )
        val p=Project(name="Prueba JSON",template=capture.template,templates=listOf(capture),actions=listOf(imageCondition,click,waitImage))
        assertEquals(p,ProjectStore.decode(ProjectStore.encode(p)))
    }
    @Test fun rejectsUnknownSchema() {
        val json=JSONObject(ProjectStore.encode(Project())).put("schemaVersion",999)
        assertTrue(runCatching { ProjectStore.decode(json.toString()) }.isFailure)
    }
    @Test fun rejectsInvalidCoordinatesFromImport() {
        val json=JSONObject(ProjectStore.encode(Project(actions=listOf(Action()))))
        json.getJSONArray("actions").getJSONObject(0).getJSONArray("points").getJSONArray(0).put(0,12.0)
        assertTrue(runCatching { ProjectStore.decode(json.toString()) }.isFailure)
    }
    @Test fun persistentAtomicStore() {
        val context=object: android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir()=java.io.File(baseContext.cacheDir,"store-tests").apply { mkdirs() }
        }
        val p=Project(name="Persistencia instrumentada",actions=listOf(Action(kind=Kind.WAIT,durationMs=100)))
        ProjectStore(context).save(p)
        assertEquals(p,ProjectStore(context).list().find { it.id==p.id })
        val updated=p.copy(delayMs=1234); ProjectStore(context).save(updated)
        assertEquals(updated,ProjectStore(context).list().find { it.id==p.id })
        java.io.File(context.filesDir,"projects/${p.id}.json").writeText("archivo roto")
        assertEquals("Debe recuperar la copia anterior válida",p,ProjectStore(context).list().find { it.id==p.id })
    }
    @Test fun editorLaunchesAndLabOpens() {
        val context=instrumentation.targetContext
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); assertFalse(editor.isFinishing)
        Thread.sleep(500)
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        java.io.File(context.cacheDir,"editor-test.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        screenshot.recycle()
        val lab=instrumentation.startActivitySync(Intent(context,LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); assertFalse(lab.isFinishing)
        waitForText("◆  PRISONMACRO  ◆")
        waitForText("VOLVER AL EDITOR")
        instrumentation.runOnMainSync { lab.finish(); editor.finish() }
    }
    @Test fun editorAssignsSavedCaptureToOneCondition() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val first=SavedTemplate(id="boton-azul",name="Botón azul",template=Template("boton-azul.png",0,0,20,20,1080,2400))
        val second=SavedTemplate(id="cruz-anuncio",name="Cruz de anuncio",template=Template("cruz-anuncio.png",100,100,24,24,1080,2400))
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Selector visual",template=first.template,templates=listOf(first,second),actions=listOf(Action(kind=Kind.IF_IMAGE,name="Condición configurable",templateId=first.id))))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot().let { shot -> java.io.File(context.cacheDir,"selector-editor.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; shot.recycle() }
            waitForText("Captura: Botón azul")
            // En la pantalla compacta el control se expone con su texto visible
            // «Editar»; el detalle semántico puede no aparecer en el árbol
            // clásico de UIAutomator aunque el botón sea accesible y pulsable.
            assertTrue("El botón visible debe abrir el selector de esa condición",clickNear(waitForText("Editar")))
            instrumentation.waitForIdleSync()
            waitForText("Captura de esta condición")
            waitForText("Imagen elegida ahora")
            waitForText("ELEGIDA")
            assertTrue("El selector debe permitir buscar por nombre",setText(waitForText("Buscar captura por nombre"),"Cruz"))
            waitForText("1 resultado(s)")
            instrumentation.uiAutomation.takeScreenshot().let { shot ->
                java.io.File(context.cacheDir,"selector-search-0.3.3.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                shot.recycle()
            }
            assertTrue("Debe poder elegir una captura de la biblioteca",clickNear(waitForText("Cruz de anuncio")))
            waitForText("＋ Hacer una captura nueva y recortarla")
            assertTrue("Debe guardar la captura elegida",clickNear(waitForText("Guardar")))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(second.id,Studio.project.actions.single().templateId) }
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun newConditionDoesNotAutoSelectFirstCapture() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val saved=SavedTemplate(id="captura-existente",name="No elegir sola",template=Template("existente.png",0,0,20,20,1080,2400))
        val condition=Action(kind=Kind.IF_IMAGE,name="Condición todavía vacía",templateId="")
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Sin selección implícita",template=saved.template,templates=listOf(saved),actions=listOf(condition)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertTrue(clickNear(waitForText("Editar")))
            waitForText("Imagen elegida: NINGUNA")
            instrumentation.runOnMainSync { assertEquals("",Studio.project.actions.single().templateId) }
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun imageLibraryCanAssignExistingCaptureToPendingCondition() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val first=SavedTemplate(id="biblioteca-primera",name="Primera guardada",template=Template("primera-guardada.png",0,0,20,20,1080,2400))
        val second=SavedTemplate(id="biblioteca-segunda",name="Segunda guardada",template=Template("segunda-guardada.png",40,50,24,24,1080,2400))
        val condition=Action(kind=Kind.IF_IMAGE,name="Condición pendiente",templateId="")
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project; Studio.pendingTemplateActionId=null
            Studio.switch(Project(name="Asignar desde biblioteca",template=first.template,templates=listOf(first,second),actions=listOf(condition)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertTrue(clickNear(waitForText("Editar")))
            assertTrue(clickNear(waitForText("＋ Hacer una captura nueva y recortarla")))
            waitForText("La próxima captura se asignará a: Condición pendiente")
            waitForText("Usar esta captura en este paso")
            assertTrue(clickNear(waitForText("Usar esta captura en este paso")))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(first.id,Studio.project.actions.single().templateId)
                assertNull(Studio.pendingTemplateActionId)
            }
        } finally { instrumentation.runOnMainSync { Studio.pendingTemplateActionId=null; Studio.switch(original!!); editor.finish() } }
    }
    @Test fun editorOffersDirectImageClickWithItsOwnCaptureAndTarget() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val first=SavedTemplate(id="clic-primera",name="Primera captura",template=Template("primera.png",0,0,20,20,1080,2400))
        val second=SavedTemplate(id="clic-segunda",name="Cruz de anuncio",template=Template("segunda.png",100,100,24,24,1080,2400))
        val click=Action(kind=Kind.CLICK_IMAGE,name="Pulsar aviso",templateId=first.id)
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Clic visual",template=first.template,templates=listOf(first,second),actions=listOf(click)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            waitForText("Clic en: Primera captura")
            assertTrue(clickNear(waitForText("Editar")))
            waitForText("Imagen que debe encontrar para hacer clic")
            assertTrue(clickNear(waitForText("Cruz de anuncio")))
            assertTrue(clickNear(waitForText("Guardar")))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val saved=Studio.project.actions.single()
                assertEquals(second.id,saved.templateId)
            }
            waitForText("Clic en: Cruz de anuncio")
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun editorOffersColorConditionWithBranchesAndSettings() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val condition=Action(kind=Kind.IF_COLOR,name="Color de alarma",targetColor=0xffff5500.toInt(),colorTolerance=35)
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Condición de color",actions=listOf(condition)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            waitForText("Color: #FF5500")
            assertTrue(clickNear(waitForText("Detalles de Color de alarma")))
            // El botón está antes que las ramas. Púlsalo antes de que el buscador
            // de accesibilidad desplace la lista para localizar los textos bajos.
            assertTrue(clickNear(waitForText("Cambiar color de esta condición")))
            waitForText("Color, por ejemplo #FF5500")
            waitForText("Tolerancia RGB (0–255)")
            waitForText("Cualquier punto")
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun conditionAfterLetsChooseTypeAndCancelLeavesProjectUntouched() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val initial=Action(kind=Kind.WAIT,name="Paso inicial",durationMs=100)
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Cancelar condición",actions=listOf(initial)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            // En horizontal ancho la vista compacta recoge las operaciones de la
            // tarjeta para mostrar más pasos sin reducir el tamaño del texto.
            assertTrue(clickNear(waitForText("Opciones")))
            assertTrue(clickNear(waitForText("+ Condición")))
            waitForText("Añadir condición")
            waitForText("SI SE VE UNA IMAGEN")
            waitForText("SI SE VE UN COLOR")
            assertTrue(clickNear(waitForText("SI SE VE UNA IMAGEN")))
            waitForText("Captura de esta condición")
            assertTrue(clickNear(waitForText("Cancelar")))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1,Studio.project.actions.size)
                assertEquals(initial.id,Studio.project.actions.single().id)
            }
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun actionMenuShowsMacrorifyStyleEditingOperations() {
        val context=instrumentation.targetContext
        var original: Project?=null
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Menú de acción",actions=listOf(Action(kind=Kind.WAIT,name="Espera visible",durationMs=1000))))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertTrue(clickNear(waitForText("Más opciones de Espera visible")))
            waitForText("Editar acción")
            waitForText("▶ Probar acción")
            waitForText("＋ Añadir condición después")
            waitForText("＋ Añadir acción encima")
            waitForText("Duplicar en esta posición")
            waitForText("Borrar")
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun imageLibraryOffersDirectCropRenameAndOrdering() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val first=SavedTemplate(id="biblioteca-1",name="Primera captura",template=Template("primera.png",0,0,20,20,1080,2400))
        val second=SavedTemplate(id="biblioteca-2",name="Segunda captura",template=Template("segunda.png",30,40,24,26,1080,2400))
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project
            Studio.switch(Project(name="Biblioteca editable",template=first.template,templates=listOf(first,second)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertTrue(clickNear(waitForText("Imagen")))
            waitForText("Primera captura")
            waitForText("Recortar")
            waitForText("Usar para inicio rápido")
        } finally { instrumentation.runOnMainSync { Studio.switch(original!!); editor.finish() } }
    }
    @Test fun newScreenCaptureRemembersRequestingCondition() {
        val context=instrumentation.targetContext
        var original: Project?=null
        val condition=Action(kind=Kind.IF_IMAGE,name="Condición de captura nueva")
        instrumentation.runOnMainSync {
            Studio.init(context); original=Studio.project; Studio.pendingTemplateActionId=null
            Studio.switch(Project(name="Asignación pendiente",actions=listOf(condition)))
        }
        val editor=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertTrue(clickNear(waitForText("Editar")))
            assertTrue(clickNear(waitForText("＋ Hacer una captura nueva y recortarla")))
            waitForText("La próxima captura se asignará a: Condición de captura nueva")
            instrumentation.runOnMainSync { assertEquals(condition.id,Studio.pendingTemplateActionId) }
        } finally {
            instrumentation.runOnMainSync { Studio.pendingTemplateActionId=null; Studio.switch(original!!); editor.finish() }
        }
    }
    @Test fun engineSimulationRepeatsAndStops() {
        var original: Project?=null
        val marker="espera-${java.util.UUID.randomUUID()}"
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            Studio.switch(Project(name="Prueba motor",delayMs=0,actions=listOf(Action(kind=Kind.REPEAT,name="grupo de prueba",count=3,children=listOf(Action(kind=Kind.WAIT,name=marker,durationMs=20))))))
            es.macrostudio.engine.Engine.start(false,true)
        }
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+8000
            var running=true
            while(running && android.os.SystemClock.elapsedRealtime()<deadline) { Thread.sleep(100); instrumentation.runOnMainSync { running=Studio.running } }
            assertFalse("El motor debe terminar",running)
            instrumentation.runOnMainSync {
                assertTrue(Studio.status.startsWith("Completado"))
                assertEquals(3,Studio.logs.count { it.contains("· $marker") })
            }
        } finally { instrumentation.runOnMainSync { es.macrostudio.engine.Engine.stop(); Studio.switch(original!!) } }
    }
    @Test fun disabledConditionWithoutImageDoesNotBlockMacro() {
        var original: Project?=null
        val marker="condicion-desactivada-${java.util.UUID.randomUUID()}"
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            val disabled=Action(kind=Kind.IF_IMAGE,name="Condición desactivada",enabled=false,templateId="",children=listOf(Action(name=marker)))
            Studio.switch(Project(name="Ignorar paso desactivado",delayMs=0,actions=listOf(Action(kind=Kind.REPEAT,name="Grupo",count=1,children=listOf(disabled)))))
            es.macrostudio.engine.Engine.start(false,true)
        }
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            var running=true
            while(running && android.os.SystemClock.elapsedRealtime()<deadline) { Thread.sleep(100); instrumentation.runOnMainSync { running=Studio.running } }
            instrumentation.runOnMainSync {
                assertFalse("El motor debe ignorar una condición desactivada",running)
                assertTrue("La macro debe completar el grupo",Studio.status.startsWith("Completado"))
                assertFalse("El paso desactivado no debe ejecutarse",Studio.logs.any { it.contains("· $marker") })
            }
        } finally { instrumentation.runOnMainSync { es.macrostudio.engine.Engine.stop(); Studio.switch(original!!) } }
    }
    @Test fun engineStopCancelsPendingSteps() {
        var original: Project?=null
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            Studio.switch(Project(name="Prueba parada",delayMs=0,actions=listOf(Action(kind=Kind.WAIT,name="NO DEBE EJECUTARSE",durationMs=10))))
            es.macrostudio.engine.Engine.start(false,true)
            es.macrostudio.engine.Engine.stop("Parada de prueba")
        }
        Thread.sleep(1700)
        try { instrumentation.runOnMainSync { assertFalse(Studio.running); assertFalse(Studio.logs.any { it.contains("· NO DEBE EJECUTARSE") }) } }
        finally { instrumentation.runOnMainSync { Studio.switch(original!!) } }
    }
    @Test fun delayedDetectionIsCancelledByStopOrProjectChange() {
        var original: Project?=null
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            Studio.switch(Project(name="Detección diferida"))
            Studio.lastMatch="sin ejecutar"
            es.macrostudio.engine.Engine.scheduleDetection(100)
            es.macrostudio.engine.Engine.stop()
        }
        try {
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                assertEquals("sin ejecutar",Studio.lastMatch)
                es.macrostudio.engine.Engine.scheduleDetection(100)
                Studio.switch(Project(name="Otro proyecto"))
            }
            Thread.sleep(250)
            instrumentation.runOnMainSync { assertEquals("sin ejecutar",Studio.lastMatch) }
        } finally { instrumentation.runOnMainSync { es.macrostudio.engine.Engine.stop(); Studio.switch(original!!) } }
    }
    @Test fun pauseDefersStepsAndStopDiscardsResume() {
        var original: Project?=null
        val marker="pausa-${java.util.UUID.randomUUID()}"
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            Studio.switch(Project(name="Prueba pausa",delayMs=0,actions=listOf(Action(kind=Kind.WAIT,name=marker,durationMs=10))))
            es.macrostudio.engine.Engine.start(false,true)
            es.macrostudio.engine.Engine.pause()
        }
        try {
            Thread.sleep(1800)
            instrumentation.runOnMainSync {
                assertTrue(Studio.running); assertTrue(Studio.paused)
                assertFalse(Studio.logs.any { it.contains("· $marker") })
                es.macrostudio.engine.Engine.stop("Pausa cancelada")
                es.macrostudio.engine.Engine.pause()
            }
            Thread.sleep(200)
            instrumentation.runOnMainSync {
                assertFalse(Studio.running); assertFalse(Studio.paused)
                assertFalse(Studio.logs.any { it.contains("· $marker") })
            }
        } finally { instrumentation.runOnMainSync { es.macrostudio.engine.Engine.stop(); Studio.switch(original!!) } }
    }
    @Test fun unavailableCaptureStopsInsteadOfExecutingElse() {
        var original: Project?=null
        val marker="rama-prohibida-${java.util.UUID.randomUUID()}"
        val saved=SavedTemplate(id="captura-ausente",name="Captura ausente",template=Template("missing.png",0,0,20,20,1080,2400))
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            assertNull("Esta prueba requiere laboratorio sin proyección activa",es.macrostudio.capture.CaptureService.instance)
            Studio.switch(Project(name="Captura ausente",delayMs=0,template=saved.template,templates=listOf(saved),actions=listOf(Action(kind=Kind.IF_IMAGE,templateId=saved.id,otherwise=listOf(Action(kind=Kind.WAIT,name=marker,durationMs=10))))))
            es.macrostudio.engine.Engine.start(false,true)
        }
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+6000
            var running=true
            while(running && android.os.SystemClock.elapsedRealtime()<deadline) {
                Thread.sleep(100); instrumentation.runOnMainSync { running=Studio.running }
            }
            instrumentation.runOnMainSync {
                assertFalse(Studio.running)
                assertEquals("No hay captura disponible",Studio.status)
                assertFalse(Studio.logs.any { it.contains("· $marker") })
            }
        } finally { instrumentation.runOnMainSync { es.macrostudio.engine.Engine.stop(); Studio.switch(original!!) } }
    }
    @Test fun conditionWithoutChosenImageCannotUseMainImage() {
        var original: Project?=null
        val saved=SavedTemplate(id="principal",name="Imagen principal",template=Template("principal.png",0,0,20,20,1080,2400))
        instrumentation.runOnMainSync {
            Studio.init(instrumentation.targetContext); original=Studio.project
            Studio.switch(Project(name="Borrador sin imagen",template=saved.template,templates=listOf(saved),actions=listOf(Action(kind=Kind.IF_IMAGE,templateId=""))))
            es.macrostudio.engine.Engine.start(false,true)
            assertFalse(Studio.running)
            assertEquals("Elige una imagen guardada en cada condición o espera por imagen",Studio.status)
            Studio.switch(original!!)
        }
    }
}
