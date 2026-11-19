package es.macrostudio

import es.macrostudio.core.*
import es.macrostudio.editor.addCapturedTemplate
import es.macrostudio.vision.*
import es.macrostudio.sync.DeviceSession
import es.macrostudio.sync.activeSessionCount
import es.macrostudio.projects.recoverPrimaryTemplate
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun image(w: Int,h: Int,seed: Int)=Pixels(w,h,IntArray(w*h) { i -> 0xff000000.toInt() or (((i*7919+seed)*104729) and 0xffffff) })
    @Test fun findsTemplateAtEveryEdge() {
        val t=image(7,9,4)
        for((x,y) in listOf(0 to 0,23 to 0,0 to 31,23 to 31,13 to 17)) {
            val screen=image(30,40,25)
            for(ty in 0 until t.height) for(tx in 0 until t.width) screen.colors[(y+ty)*screen.width+x+tx]=t.colors[ty*t.width+tx]
            val match=Matcher.find(screen,t)!!
            assertEquals(x,match.x); assertEquals(y,match.y); assertEquals(1.0,match.confidence,0.00001)
        }
    }
    @Test fun exactModeDoesNotSearchOtherLocations() {
        val screen=image(20,30,11); val template=Pixels(4,5,IntArray(20) { i -> screen.colors[(10+i/4)*20+5+i%4] })
        assertEquals(1.0,Matcher.find(screen,template,5 to 10)!!.confidence,0.0)
        assertTrue(Matcher.find(screen,template,0 to 0)!!.confidence<.95)
    }
    @Test fun unrelatedImageFailsThreshold() { assertTrue(Matcher.find(image(80,60,77),image(10,10,3))!!.confidence<.93) }
    @Test fun oversizeTemplateReturnsNull() { assertNull(Matcher.find(image(2,2,1),image(3,3,1))) }
    @Test fun cancellationStopsSearch() { assertNull(Matcher.find(image(80,80,1),image(8,8,1),cancelled={true})) }
    @Test fun colorFinderSupportsAnywhereExactToleranceAndNoiseRejection() {
        val colors=IntArray(30*20) { 0xff101010.toInt() }
        for(y in 8..10) for(x in 12..14) colors[y*30+x]=0xffff4010.toInt()
        val screen=Pixels(30,20,colors)
        assertNotNull(ColorMatcher.find(screen,0xffff4000.toInt(),20))
        assertNotNull(ColorMatcher.find(screen,0xffff4000.toInt(),20,13 to 9))
        assertNull(ColorMatcher.find(screen,0xff00ff00.toInt(),20))
        colors[0]=0xff00ff00.toInt()
        assertNull("Un píxel aislado no debe activar la condición",ColorMatcher.find(screen,0xff00ff00.toInt(),0))
        assertNull(ColorMatcher.find(screen,0xffff4010.toInt(),0,cancelled={true}))
    }
    @Test fun colorHexParsingIsStrict() {
        assertEquals(0xffff5500.toInt(),parseRgbHex("#FF5500"))
        assertEquals("#FF5500",rgbHex(0xffff5500.toInt()))
        assertNull(parseRgbHex("#FFF")); assertNull(parseRgbHex("GG5500"))
    }
    @Test fun outOfBoundsExactDoesNotMatch() { assertEquals(0.0,Matcher.score(image(8,8,1),image(4,4,1),7,7),0.0) }
    @Test fun validatesDefaultProject() { assertNull(Project().validate()) }
    @Test fun executionPreflightAllowsDraftsButExplainsWhatIsMissing() {
        val draft=Project(actions=listOf(Action(kind=Kind.IF_IMAGE,name="Ver botón",templateId="")))
        assertNull("El borrador se puede guardar",draft.validate())
        assertTrue(draft.executionIssues().single().contains("necesita una captura"))
        val saved=SavedTemplate(id="boton",name="Botón",template=Template("boton.png",0,0,20,20,100,100))
        assertTrue(draft.copy(templates=listOf(saved),actions=listOf(draft.actions.single().copy(templateId=saved.id))).executionIssues().isEmpty())
        assertTrue(Project(actions=listOf(Action(kind=Kind.REPEAT,children=emptyList()))).executionIssues().single().contains("no contiene pasos"))
        assertTrue(Project(actions=listOf(Action(kind=Kind.WAIT_IMAGE,durationMs=1_000))).executionIssues().single().contains("necesita una captura"))
    }
    @Test fun normalizedScreenPointUsesScreenEdgesAndClampsOverlayNoise() {
        assertEquals(Point(0f,0f),normalizedScreenPoint(0f,0f,101,201))
        assertEquals(Point(1f,1f),normalizedScreenPoint(100f,200f,101,201))
        assertEquals(Point(0f,1f),normalizedScreenPoint(-20f,250f,101,201))
        assertEquals(Point(.5f,.5f),normalizedScreenPoint(50f,100f,101,201))
    }
    @Test fun rejectsInvalidNumbers() {
        assertNotNull(Project(threshold=Double.NaN).validate())
        assertNotNull(Project(delayMs=-1).validate())
        assertNotNull(Project(maxRuns=0).validate())
        assertNotNull(Project(actions=listOf(Action(points=listOf(Point(Float.NaN,.5f))))).validate())
        assertNotNull(Project(actions=listOf(Action(kind=Kind.CLOSE_ACTIVE,durationMs=499))).validate())
    }
    @Test fun rejectsInvalidTemplateBounds() { assertNotNull(Project(template=Template("a.png",5,5,99,99,100,100)).validate()) }
    @Test fun validatesPerActionImageAndRejectsMissingOrInvalidCapture() {
        val saved=SavedTemplate(id="imagen-1",name="Botón azul",template=Template("imagen-1.png",10,12,30,40,200,100))
        assertNull(Project(templates=listOf(saved),actions=listOf(Action(kind=Kind.IF_IMAGE,templateId=saved.id))).validate())
        assertNull("Debe poder guardarse como borrador hasta que el usuario elija",Project(template=saved.template,templates=listOf(saved),actions=listOf(Action(kind=Kind.IF_IMAGE,templateId=""))).validate())
        assertNotNull(Project(actions=listOf(Action(kind=Kind.IF_IMAGE,templateId=saved.id))).validate())
        assertNotNull(Project(templates=listOf(saved.copy(template=saved.template.copy(x=190)))).validate())
    }
    @Test fun validatesColorConditionSettings() {
        assertNull(Project(actions=listOf(Action(kind=Kind.IF_COLOR,targetColor=0xff123456.toInt(),colorTolerance=40,colorMode=SearchMode.EXACT,colorX=.25f,colorY=.75f))).validate())
        assertNotNull(Project(actions=listOf(Action(kind=Kind.IF_COLOR,colorTolerance=256))).validate())
        assertNotNull(Project(actions=listOf(Action(kind=Kind.IF_COLOR,colorX=1.1f))).validate())
    }
    @Test fun rejectsTemplatePathTraversal() { assertNotNull(Project(template=Template("../a.png",0,0,10,10,100,100)).validate()) }
    @Test fun rejectsDuplicateActionIds() { val a=Action(); assertNotNull(Project(actions=listOf(a,a)).validate()) }
    @Test fun rejectsUniformTemplates() { assertFalse(Matcher.isDistinctive(Pixels(20,20,IntArray(400){0xff112233.toInt()}))); assertTrue(Matcher.isDistinctive(image(20,20,9))) }
    @Test fun rejectsDeepRecursion() {
        var action=Action(); repeat(10) { action=Action(kind=Kind.REPEAT,children=listOf(action)) }
        assertNotNull(Project(actions=listOf(action)).validate())
    }
    @Test fun editNestedActionPreservesOthers() {
        val child=Action(name="child"); val other=Action(name="other"); val parent=Action(kind=Kind.IF_IMAGE,children=listOf(child),otherwise=listOf(other))
        val edited=updateAction(listOf(parent),child.id){it.copy(name="edited")}.single()
        assertEquals("edited",edited.children.single().name); assertEquals(other,edited.otherwise.single())
    }
    @Test fun removeNestedAction() { val a=Action(); assertTrue(updateAction(listOf(Action(children=listOf(a))),a.id){null}.single().children.isEmpty()) }
    @Test fun moveStaysInItsGroup() { val a=Action(); val b=Action(); val parent=Action(children=listOf(a,b)); assertEquals(listOf(b,a),moveAction(listOf(parent),a.id,1).single().children) }
    @Test fun insertsConditionImmediatelyAfterNestedAction() { val child=Action(); val condition=Action(kind=Kind.IF_IMAGE); val parent=Action(children=listOf(child)); assertEquals(listOf(child,condition),insertAfter(listOf(parent),child.id,condition).single().children) }
    @Test fun duplicatesActionImmediatelyAfterItInItsOwnBranch() {
        val child=Action(name="hijo")
        val sibling=Action(name="hermana")
        val parent=Action(children=listOf(child,sibling))
        val result=duplicateAfter(listOf(parent),child.id).single().children
        assertEquals(listOf("hijo","hijo","hermana"),result.map { it.name })
        assertNotEquals(child.id,result[1].id)
    }
    @Test fun assignsImportedOrCapturedTemplateToNestedConditionOnly() {
        val target=Action(kind=Kind.IF_IMAGE,templateId="old")
        val sibling=Action(kind=Kind.IF_IMAGE,templateId="keep")
        val root=Action(kind=Kind.REPEAT,children=listOf(target),otherwise=listOf(sibling))
        val result=assignTemplateToAction(listOf(root),target.id,"new").single()
        assertEquals("new",result.children.single().templateId)
        assertEquals("keep",result.otherwise.single().templateId)
    }
    @Test fun canReplaceAnExistingActionCaptureWithAnotherSavedCapture() {
        val first=SavedTemplate(id="primera",name="Primera",template=Template("primera.png",0,0,20,20,100,100))
        val second=SavedTemplate(id="segunda",name="Segunda",template=Template("segunda.png",10,10,20,20,100,100))
        val target=Action(kind=Kind.IF_IMAGE,templateId=first.id)
        val sibling=Action(kind=Kind.IF_IMAGE,templateId=first.id)
        val result=assignTemplateToAction(listOf(target,sibling),target.id,second.id)
        assertEquals(second.id,result[0].templateId)
        assertEquals(first.id,result[1].templateId)
        assertNull(Project(templates=listOf(first,second),actions=result).validate())
    }
    @Test fun waitForImageRoundTripsWithItsSelectedCapture() {
        val capture=SavedTemplate(id="lobby",name="Lobby",template=Template("lobby.png",0,0,24,24,100,100))
        val action=Action(kind=Kind.WAIT_IMAGE,name="Esperar lobby",templateId=capture.id,durationMs=30_000)
        val project=Project(template=capture.template,templates=listOf(capture),actions=listOf(action))
        assertNull(project.validate())
        assertEquals(project,project)
    }
    @Test fun captureForConditionDoesNotReplaceQuickStartImage() {
        val quick=SavedTemplate(id="inicio",name="Inicio rápido",template=Template("inicio.png",0,0,20,20,100,100))
        val target=Action(kind=Kind.IF_IMAGE)
        val project=Project(template=quick.template,templates=listOf(quick),actions=listOf(target))
        val conditionImage=SavedTemplate(id="condicion",name="Solo condición",template=Template("condicion.png",30,30,20,20,100,100))
        val updated=addCapturedTemplate(project,conditionImage,target.id)
        assertEquals(quick.template,updated.template)
        assertEquals(conditionImage.id,updated.actions.single().templateId)
        assertEquals(listOf(quick,conditionImage),updated.templates)
    }
    @Test fun captureForAStepNeverBecomesTheStartImage() {
        val target=Action(kind=Kind.IF_IMAGE)
        val saved=SavedTemplate(id="primera",name="Primera",template=Template("primera.png",0,0,20,20,100,100))
        val updated=addCapturedTemplate(Project(actions=listOf(target)),saved,target.id)
        assertNull(updated.template)
        assertEquals(saved.id,updated.actions.single().templateId)
    }
    @Test fun captureForStartImageEnablesStartOnImage() {
        val saved=SavedTemplate(id="inicio",name="Inicio",template=Template("inicio.png",0,0,20,20,100,100))
        val updated=addCapturedTemplate(Project(),saved,Studio.START_IMAGE_TARGET)
        assertEquals(saved.template,updated.template)
        assertTrue(updated.startOnImage)
        assertEquals(listOf(saved),updated.templates)
    }
    @Test fun legacyLoopingProjectKeepsItsRepetitions() {
        val legacy=org.json.JSONObject(es.macrostudio.projects.ProjectStore.encode(Project(intervalMs=5000,maxRuns=7))).apply { remove("repeatCount"); remove("startOnImage") }
        assertEquals(7,es.macrostudio.projects.ProjectStore.decode(legacy.toString()).repeatCount)
        val single=org.json.JSONObject(es.macrostudio.projects.ProjectStore.encode(Project(intervalMs=0,maxRuns=7))).apply { remove("repeatCount") }
        assertEquals(1,es.macrostudio.projects.ProjectStore.decode(single.toString()).repeatCount)
    }
    @Test fun repeatCountIsValidated() {
        assertNull(Project(repeatCount=5).validate())
        assertNull(Project(repeatCount=0).validate()) // 0 = sin fin
        assertNotNull(Project(repeatCount=-1).validate())
        assertNotNull(Project(repeatCount=1001).validate())
        assertNull(Project(maxMinutes=0).validate())
        assertNotNull(Project(maxMinutes=2000).validate())
    }
    @Test fun newStepOptionsRoundTrip() {
        val click=Action(kind=Kind.CLICK_IMAGE,skipIfMissing=true,waitMs=5000)
        val swipe=Action(points=listOf(Point(.5f,.8f),Point(.5f,.2f)),durationMs=300)
        val p=Project(repeatCount=0,maxMinutes=0,actions=listOf(click,swipe))
        val back=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(p))
        assertEquals(p,back)
        assertNotNull(Project(actions=listOf(Action(waitMs=-1))).validate())
    }
    @Test fun projectsWithoutNewFieldsKeepSafeDefaults() {
        val json=org.json.JSONObject(es.macrostudio.projects.ProjectStore.encode(Project(actions=listOf(Action(kind=Kind.CLICK_IMAGE)))))
        json.remove("maxMinutes"); val action=json.getJSONArray("actions").getJSONObject(0); action.remove("skipIfMissing"); action.remove("waitMs")
        val p=es.macrostudio.projects.ProjectStore.decode(json.toString())
        assertEquals(30,p.maxMinutes); assertFalse(p.actions[0].skipIfMissing); assertEquals(0L,p.actions[0].waitMs)
    }
    /** ios-export.json lo generó el codificador real de la app de iPhone (MacroModels.swift). */
    @Test fun macroSavedOnIphoneOpensOnAndroidWithAllOptions() {
        val json=javaClass.classLoader!!.getResource("ios-export.json")!!.readText()
        val p=es.macrostudio.projects.ProjectStore.decode(json)
        assertNull(p.validate())
        assertEquals("Recolectar oro",p.name)
        assertEquals(0,p.repeatCount); assertEquals(45,p.maxMinutes); assertEquals(2000L,p.intervalMs)
        val click=p.actions[0]
        assertEquals(Kind.CLICK_IMAGE,click.kind); assertTrue(click.skipIfMissing); assertEquals(5000L,click.waitMs)
        assertEquals(Kind.WAIT,p.actions[1].kind); assertEquals(1500L,p.actions[1].durationMs)
        assertEquals(2,p.actions[2].points.size); assertEquals(0.8f,p.actions[2].points[1].x,0.0001f)
        assertFalse(p.actions[3].skipIfMissing)
    }
    @Test fun stopStepAndRepeatUntilImageRoundTrip() {
        val img=SavedTemplate("t1","Victoria",Template("a.png",0,0,10,10,100,100))
        val p=Project(templates=listOf(img),actions=listOf(Action(kind=Kind.REPEAT,templateId="t1",count=50,children=listOf(Action())),Action(kind=Kind.STOP,name="Parar")))
        assertNull(p.validate())
        val back=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(p))
        assertEquals(Kind.STOP,back.actions[1].kind); assertEquals("t1",back.actions[0].templateId)
        assertTrue(back.needsScreenCapture())
        assertFalse(Project(actions=listOf(Action(kind=Kind.REPEAT,children=listOf(Action())))).needsScreenCapture())
    }
    @Test fun updaterOnlyAcceptsLocalAddresses() {
        val u=es.macrostudio.update.Updater
        assertEquals("10.0.2.2:8765",u.normalizeAddress("10.0.2.2"))
        assertEquals("192.168.1.20:9000",u.normalizeAddress(" http://192.168.1.20:9000/ "))
        assertEquals("mac-de-xuso.local:8765",u.normalizeAddress("mac-de-xuso.local"))
        assertNull(u.normalizeAddress("8.8.8.8")); assertNull(u.normalizeAddress("ejemplo.com"))
        assertNull(u.normalizeAddress("172.32.0.1")); assertNull(u.normalizeAddress("192.168.1.300"))
        assertNull(u.normalizeAddress("192.168.1.2:99999")); assertNull(u.normalizeAddress("192.168.1.2/../x"))
    }
    @Test fun updaterRejectsUnsafeVersionInfo() {
        val u=es.macrostudio.update.Updater
        val ok="""{"versionCode":30,"versionName":"0.7.0","apk":"PrisonMacro-0.7.0.apk","sha256":"${"a".repeat(64)}","sizeBytes":1000}"""
        assertEquals(30,u.parseInfo(ok).versionCode)
        assertThrows(IllegalArgumentException::class.java) { u.parseInfo(ok.replace("PrisonMacro-0.7.0.apk","../secreto.apk")) }
        assertThrows(IllegalArgumentException::class.java) { u.parseInfo(ok.replace("a".repeat(64),"zz")) }
    }
    /** ios-roundtrip.json: macro grabada en el emulador Android, importada en el simulador de
     * iPhone (copia completa) y guardada por la app de iPhone. Debe volver entera a Android. */
    @Test fun androidMacroSurvivesTripThroughIphone() {
        val json=javaClass.classLoader!!.getResource("ios-roundtrip.json")!!.readText()
        val p=es.macrostudio.projects.ProjectStore.decode(json)
        assertNull(p.validate()); assertTrue(p.executionIssues().isEmpty())
        assertEquals(0,p.repeatCount); assertEquals(45,p.maxMinutes); assertEquals(1,p.templates.size)
        assertEquals(12,countActions(p.actions))
        val repeat=p.actions.first { it.kind==Kind.REPEAT }
        assertEquals(p.templates[0].id,repeat.templateId); assertEquals(5,repeat.count)
        assertEquals(Kind.STOP,p.actions.first { it.kind==Kind.IF_IMAGE }.children.single().kind)
        assertEquals(0.5626f,p.actions[2].points.single().y,0.001f)
    }
    @Test fun colorTargetMapsBackToItsStep() {
        assertEquals("abc",stepOfCaptureTarget(Studio.COLOR_PREFIX+"abc"))
        assertEquals("abc",stepOfCaptureTarget("abc"))
        assertNull(stepOfCaptureTarget(Studio.START_IMAGE_TARGET))
        assertNull(stepOfCaptureTarget(null))
    }
    @Test fun duplicateRegeneratesAllIds() { val a=Action(children=listOf(Action()),otherwise=listOf(Action())); val b=a.duplicate(); assertNotEquals(a.id,b.id); assertNotEquals(a.children[0].id,b.children[0].id); assertNotEquals(a.otherwise[0].id,b.otherwise[0].id) }
    @Test fun legacyMainImageIsRecoveredIntoTheActionLibrary() {
        val image=Template("legacy.png",0,0,20,20,100,100)
        val recovered=recoverPrimaryTemplate(image,emptyList())
        assertEquals(1,recovered.size)
        assertEquals(image.file,recovered.single().template.file)
        assertEquals("Imagen principal",recovered.single().name)
        assertEquals(recovered,recoverPrimaryTemplate(image,recovered))
    }
    @Test fun closeRecentGestureIsAQuickUpwardSwipe() {
        val action=closeRecentSwipeAction()
        assertEquals(Kind.GESTURE,action.kind); assertEquals(350,action.durationMs)
        assertEquals(2,action.points.size); assertEquals(.5f,action.points.first().x,0f)
        assertTrue(action.points.first().y>action.points.last().y)
    }
    @Test fun activeSessionIndicatorIgnoresOldAndRevokedDevices() {
        val now=1_000_000L
        val current=DeviceSession("a","BlueStacks","Android",now-1_000)
        val old=DeviceSession("b","iPhone","iOS",now-181_000)
        val revoked=DeviceSession("c","Mac","macOS",now-1_000,revoked=true)
        assertEquals(1,activeSessionCount(listOf(current,old,revoked),now))
    }
    @Test fun mergesWaitStepsIntoNeighbourDelays() {
        val list=listOf(
            Action(id="o",kind=Kind.OPEN_APP,name="Abrir",packageName="com.a.b"),
            Action(id="w1",kind=Kind.WAIT,name="Esperar",durationMs=40_000),
            Action(id="b",kind=Kind.BACK,name="Atrás"),
            Action(id="w2",kind=Kind.WAIT,name="Esperar",durationMs=2_000),
            Action(id="r",kind=Kind.REPEAT,name="Repetir",count=2,children=listOf(Action(id="w3",kind=Kind.WAIT,name="E",durationMs=1_000),Action(id="h",kind=Kind.HOME,name="Inicio")))
        )
        val (merged,n)=es.macrostudio.ui.mergeWaits(list)
        assertEquals(3,n)
        assertEquals(listOf("o","b","r"),merged.map { it.id })
        assertEquals(40_000L,merged[0].delayAfterMs); assertEquals(2_000L,merged[1].delayAfterMs)
        assertEquals(listOf("h"),merged[2].children.map { it.id }); assertEquals(1_000L,merged[2].children[0].delayBeforeMs)
        val p=Project(actions=merged)
        val back=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(p))
        assertEquals(40_000L,back.actions[0].delayAfterMs); assertEquals(1_000L,back.actions[2].children[0].delayBeforeMs)
        assertNull(back.validate())
    }
    @Test fun movesStepsOutOfAndIntoBlocks() {
        val inner=Action(id="i",kind=Kind.HOME,name="Inicio")
        val block=Action(id="r",kind=Kind.REPEAT,name="Repetir",count=2,children=listOf(inner,Action(id="j",kind=Kind.BACK,name="Atrás")))
        val list=listOf(Action(id="a",kind=Kind.BACK,name="A"),block,Action(id="z",kind=Kind.BACK,name="Z"))
        assertEquals("r",parentOf(list,"i")?.id); assertNull(parentOf(list,"a"))
        val out=moveOutOfBlock(list,"i")
        assertEquals(listOf("a","r","i","z"),out.map { it.id }); assertEquals(listOf("j"),out[1].children.map { it.id })
        val back=addInside(updateAction(out,"z") { null },"r",false,Action(id="z",kind=Kind.BACK,name="Z"))
        assertEquals(listOf("j","z"),back.first { it.id=="r" }.children.map { it.id })
        assertEquals(true to false,siblingPosition(list,"i")); assertEquals(false to true,siblingPosition(list,"z"))
        assertEquals(setOf("r"),containerIds(list))
    }

    @Test fun rulesAreSavedAndFoundAnywhere() {
        val tap=Action(id="t",kind=Kind.CLICK_IMAGE,templateId="img")
        val rule=Action(id="w",kind=Kind.WHEN_IMAGE,templateId="img",waitMs=5000,afterRule=AfterRule.EXIT_LOOP,children=listOf(tap))
        val off=Action(id="o",kind=Kind.WHEN_IMAGE,templateId="img",enabled=false)
        val p=Project(templates=listOf(SavedTemplate(id="img",template=Template("a.png",0,0,10,10,100,100))),actions=listOf(Action(id="r",kind=Kind.REPEAT,children=listOf(rule)),off))
        val back=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(p))
        val again=findAction(back.actions,"w")!!
        assertEquals(AfterRule.EXIT_LOOP,again.afterRule); assertEquals(5000L,again.waitMs); assertEquals(listOf("t"),again.children.map { it.id })
        assertEquals(listOf("w"),ruleActions(back.actions).map { it.id })
        assertTrue(back.needsScreenCapture())
        assertEquals(setOf("r","w","o"),containerIds(back.actions))
        // Sin pasos dentro, la regla avisa.
        assertTrue(p.copy(actions=listOf(rule.copy(children=emptyList()))).executionIssues().any { it.contains("todavía no toca nada") })
    }

    @Test fun ignoresTheFloatingPanel() {
        // La imagen buscada está dos veces: una «dentro del panel» (zona tapada) y otra en el juego.
        val template=image(6,6,5)
        val colors=IntArray(40*40) { 0xff102030.toInt() or ((it*37) and 0x3f) }
        fun put(x0: Int, y0: Int) { for(y in 0 until 6) for(x in 0 until 6) colors[(y0+y)*40+x0+x]=template.colors[y*6+x] }
        put(2,2); put(30,25)
        val screen=Pixels(40,40,colors).masked(listOf(Area(0,0,20,12)))
        val found=Matcher.find(screen,template)!!
        assertEquals(30 to 25,found.x to found.y)
        assertEquals(0.0,Matcher.score(screen,template,2,2),0.0)
        // Medio tapada por el panel: se reconoce por la parte que se ve.
        val half=Pixels(40,40,colors).masked(listOf(Area(30,25,33,31)))
        assertEquals(1.0,Matcher.score(half,template,30,25),1e-9)
    }

    @Test fun deletesManyStepsAndImagesAtOnce() {
        val t=Template("a.png",0,0,10,10,100,100)
        val p=Project(template=t,startOnImage=true,templates=listOf(SavedTemplate(id="a",template=t),SavedTemplate(id="b",template=t.copy(file="b.png"))),
            actions=listOf(Action(id="1",kind=Kind.CLICK_IMAGE,templateId="a"),Action(id="r",kind=Kind.REPEAT,children=listOf(Action(id="2",kind=Kind.WAIT_IMAGE,templateId="b",durationMs=30_000),Action(id="3")))))
        assertEquals(setOf("1","r","2","3"),allActionIds(p.actions))
        val fewer=removeActions(p.actions,setOf("1","2"))
        assertEquals(listOf("r"),fewer.map { it.id }); assertEquals(listOf("3"),fewer[0].children.map { it.id })
        assertTrue(removeActions(p.actions,allActionIds(p.actions)).isEmpty())
        val noA=p.withoutTemplates(setOf("a"))
        assertEquals(listOf("b"),noA.templates.map { it.id })
        assertEquals("",noA.actions[0].templateId); assertEquals("b",noA.actions[1].children[0].templateId)
        assertFalse(noA.startOnImage); assertNull(noA.template)
        assertNull(noA.validate())
    }

    /** Imagen guardada como «ancho, alto y bytes RGB» comprimidos (aquí no hay lector de PNG). */
    private fun png(name: String): es.macrostudio.vision.Pixels {
        val input=java.io.DataInputStream(java.util.zip.GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("vision/${name.removeSuffix(".png")}.rgb.gz")))
        val w=input.readInt(); val h=input.readInt(); val bytes=ByteArray(w*h*3); input.readFully(bytes)
        val data=IntArray(w*h) { i -> (0xFF shl 24) or ((bytes[i*3].toInt() and 255) shl 16) or ((bytes[i*3+1].toInt() and 255) shl 8) or (bytes[i*3+2].toInt() and 255) }
        return es.macrostudio.vision.Pixels(w,h,data)
    }

    /** Captura real de Ajustes a 1920×1080: filas casi iguales (icono + dos líneas de texto).
     * Antes, al reducir la imagen como Android, elegía la fila de al lado con un 93 %. */
    @Test fun findsTheRightRowAmongSimilarOnes() {
        val screen=png("ajustes-1920x1080.png")
        for((file,at) in listOf("fila-dispositivos.png" to (40 to 441),"fila-red.png" to (44 to 332))) {
            val t=png(file)
            val region=Template(file,at.first,at.second,t.width,t.height,screen.width,screen.height)
            val m=BitmapDetector.findIn(screen,t,region,SearchMode.ANYWHERE)!!
            assertEquals(file,at.first,m.x); assertEquals(file,at.second,m.y)
            assertTrue(file,m.confidence>.99)
            // Con el panel flotante tapando la esquina de arriba, igual.
            val masked=BitmapDetector.findIn(screen.masked(listOf(es.macrostudio.vision.Area(0,40,830,170))),t,region,SearchMode.ANYWHERE)!!
            assertEquals(file,at.second,masked.y)
        }
    }

    @Test fun shrinkAveragesBlocks() {
        val p=es.macrostudio.vision.Pixels(4,2,intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xffffffff.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()))
        val s=es.macrostudio.vision.Matcher.shrink(p,2)
        assertEquals(2,s.width); assertEquals(1,s.height)
        assertEquals(127,s.colors[0] and 255); assertEquals(0,s.colors[1] and 255)
    }

    @Test fun imageFoldersAreSavedWithTheMacro() {
        val t=Template("template-a.png",0,0,10,10,100,100)
        val p=Project(templates=listOf(SavedTemplate(id="a",name="Botón",template=t,folder="Lobby"),SavedTemplate(id="b",name="Otro",template=t)))
        val back=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(p))
        assertEquals("Lobby",back.templates.first { it.id=="a" }.folder)
        assertEquals("",back.templates.first { it.id=="b" }.folder)
    }

    @Test fun keepGoingIsSavedAndOnByDefault() {
        val off=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(Project(repeatCount=0,keepGoing=false)))
        assertFalse(off.keepGoing)
        val old=org.json.JSONObject(es.macrostudio.projects.ProjectStore.encode(Project())).apply { remove("keepGoing") }
        assertTrue(es.macrostudio.projects.ProjectStore.decode(old.toString()).keepGoing)
    }

    @Test fun numbersStepsLikeTheEditor() {
        val inner=Action(kind=Kind.WAIT,name="b"); val no=Action(kind=Kind.WAIT,name="c")
        val block=Action(kind=Kind.IF_IMAGE,name="si",children=listOf(Action(name="a"),inner),otherwise=listOf(no))
        val items=listOf(Action(name="x"),block)
        assertEquals("2",stepNumber(items,block.id)); assertEquals("2.2",stepNumber(items,inner.id)); assertEquals("2.n1",stepNumber(items,no.id))
        assertNull(stepNumber(items,"nada"))
    }

    @Test fun addsUpHowLongTheMacroTakes() {
        val p=Project(delayMs=500,timeoutMs=60_000,repeatCount=3,intervalMs=1000,actions=listOf(
            Action(kind=Kind.WAIT,durationMs=2000,delayAfterMs=1000),
            Action(kind=Kind.GESTURE,durationMs=80),
            Action(kind=Kind.CLICK_IMAGE,durationMs=80,waitMs=10_000),
            Action(kind=Kind.REPEAT,count=4,children=listOf(Action(kind=Kind.BACK),Action(kind=Kind.WAIT,durationMs=2000))),
            Action(kind=Kind.WHEN_IMAGE,children=listOf(Action(kind=Kind.WAIT,durationMs=99_000)))
        ))
        assertEquals(Span.of(3000),stepTime(p.actions[0],p))
        assertEquals(Span(480,10_480),stepTime(p.actions[2],p))
        assertEquals(Span.of(10_000),stepTime(p.actions[3],p))
        assertEquals(Span.ZERO,stepTime(p.actions[4],p))
        assertEquals(Span(500+3000+80+480+10_000,500+3000+80+10_480+10_000),roundTime(p))
        assertEquals("5 min 32 s",durationText(332_000)); assertEquals("0,5 s",durationText(500)); assertEquals("1 h 05 min",durationText(3_900_000))
        assertTrue(totalText(p).contains("Total 3 vueltas"))
    }

    @Test fun imagesFitOtherScreenSizesOfTheSameShape() {
        assertTrue(BitmapDetector.sameShape(1920,1080,1280,720))
        assertTrue(BitmapDetector.sameShape(540,1200,1080,2400))
        assertFalse(BitmapDetector.sameShape(1920,1080,1080,1920))
        assertFalse(BitmapDetector.sameShape(1920,1080,1440,1080))
    }

    @Test fun quickMacroIsValidAndLooksLikeTheUsualLoop() {
        val p=QuickMacro.build(Project(name="x"),"Zooba",byIcon=true,playMs=15_000,everyMs=240_000,popups=true)
        assertNull(p.validate())
        assertEquals("Zooba en bucle",p.name)
        assertEquals(listOf(Kind.WHEN_IMAGE,Kind.CLICK_IMAGE,Kind.WAIT_IMAGE,Kind.CLICK_IMAGE,Kind.WAIT,Kind.CLOSE_ACTIVE,Kind.WAIT),p.actions.map { it.kind })
        assertEquals(0,p.repeatCount); assertTrue(p.keepGoing); assertEquals(0,p.maxMinutes)
        assertEquals(240_000L,p.actions.last().durationMs); assertEquals(0L,p.intervalMs)
        // Sin imágenes todavía: lo dice y se puede arreglar desde el editor.
        assertTrue(p.executionIssues().any { it.contains("necesita una captura") })
        // Más de 10 min: el resto pasa a pausa entre vueltas.
        val long=QuickMacro.build(Project(name="x"),"Mi juego",byIcon=false,playMs=60_000,everyMs=30*60_000L,popups=false)
        assertNull(long.validate())
        assertEquals(600_000L,long.actions.last().durationMs); assertEquals(20*60_000L,long.intervalMs)
        assertTrue(long.executionIssues().any { it.contains("elige el juego") })
        assertEquals(QuickMacro.ZOOBA,QuickMacro.build(Project(name="x"),"zooba",false,15_000,60_000,false).actions[0].packageName)
    }

    @Test fun timesCanBeWrittenInMinutes() {
        assertEquals(240_000L,es.macrostudio.ui.parseSeconds("4 min"))
        assertEquals(240_000L,es.macrostudio.ui.parseSeconds("4m"))
        assertEquals(1_500L,es.macrostudio.ui.parseSeconds("1,5"))
        assertEquals(30_000L,es.macrostudio.ui.parseSeconds("30 s"))
        assertNull(es.macrostudio.ui.parseSeconds("abc"))
        assertEquals("4 min",es.macrostudio.ui.secondsText(240_000))
        assertEquals("90",es.macrostudio.ui.secondsText(90_000))
        assertEquals("0,5",es.macrostudio.ui.secondsText(500))
    }

    @Test fun changingATimeAlsoChangesItInTheNameAndNote() {
        fun r(t: String, old: Long, new: Long) = TimeInText.retime(t, listOf(old to new))
        assertEquals("5. Jugando (20 s)", r("5. Jugando (15 s)", 15_000, 20_000))
        assertEquals("Deja 20 s para que empiece la partida.", r("Deja 15 s para que empiece la partida.", 15_000, 20_000))
        assertEquals("7. Esperar 5 minutos", r("7. Esperar 4 minutos", 240_000, 300_000))
        assertEquals("Esperar 1 minuto", r("Esperar 4 minutos", 240_000, 60_000))
        assertEquals("Esperar 90 segundos", r("Esperar 4 minutos", 240_000, 90_000))
        assertEquals("Esperar 1 segundo", r("Esperar 2,5 segundos", 2_500, 1_000))
        assertEquals("Esperar 3,5 segundos", r("Esperar 2 segundos", 2_000, 3_500))
        assertEquals("(máximo 1 min)", r("(máximo 90 s)", 90_000, 60_000))
        assertEquals("cada 6 min", r("cada 5 min", 300_000, 360_000))
        assertEquals("cerrar a los 15 segundos", r("cerrar a los 10 segundos", 10_000, 15_000))
        assertEquals("Esperar 30s", r("Esperar 15s", 15_000, 30_000))
        // Lo que no es ese tiempo no se toca: el número del paso, otros tiempos, «5 sobres».
        assertEquals("3. Cobrar 20 s y 5 sobres", r("3. Cobrar 15 s y 5 sobres", 15_000, 20_000))
        assertEquals("Esperar 10 s", r("Esperar 10 s", 15_000, 20_000))
        // Dos cambios a la vez no se pisan (15→20 y 20→25).
        assertEquals("20 s y 25 s", TimeInText.retime("15 s y 20 s", listOf(15_000L to 20_000L, 20_000L to 25_000L)))
        val old = Action(kind = Kind.WAIT, name = "Jugando (15 s)", durationMs = 15_000)
        assertEquals(listOf(15_000L to 20_000L), timeChanges(old, old.copy(durationMs = 20_000), 10_000))
        assertEquals(listOf(15_000L, 3_000L), TimeInText.find("15 s y 3 segundos"))
    }

    @Test fun changingWhatAStepDoesKeepsTheImageAndWhereItTaps() {
        // «Tocar» desplazado de una imagen, con nombre puesto a mano y una nota.
        val tap = Action(kind = Kind.CLICK_IMAGE, name = "4. Pulsar BATALLA", description = "Manda a jugar", templateId = "img",
            clickTarget = ClickTarget.OFFSET, clickOffsetX = 30f, clickOffsetY = -20f, waitMs = 20_000, delayAfterMs = 2_000)
        fun change(a: Action, o: es.macrostudio.ui.ImageOrder) = es.macrostudio.ui.changeOrder(a, o, "Botón BATALLA", 60_000)

        val always = change(tap, es.macrostudio.ui.ImageOrder.ALWAYS)
        assertEquals(Kind.WHEN_IMAGE, always.kind)
        assertEquals("4. Pulsar BATALLA", always.name); assertEquals("Manda a jugar", always.description)
        assertEquals(1, always.children.size)
        val inner = always.children[0]
        assertEquals("img", inner.templateId); assertEquals(ClickTarget.OFFSET, inner.clickTarget)
        assertEquals(30f, inner.clickOffsetX); assertEquals(-20f, inner.clickOffsetY)
        assertTrue(es.macrostudio.ui.isSimpleRule(always))

        // Y de vuelta a «Tocarla»: el mismo sitio.
        val back = change(always, es.macrostudio.ui.ImageOrder.TAP)
        assertEquals(Kind.CLICK_IMAGE, back.kind); assertEquals("img", back.templateId)
        assertEquals(ClickTarget.OFFSET, back.clickTarget); assertEquals(30f, back.clickOffsetX); assertTrue(back.children.isEmpty())

        // Esperar y volver: no pierde el sitio ni el tiempo de búsqueda.
        val wait = change(tap, es.macrostudio.ui.ImageOrder.WAIT)
        assertEquals(Kind.WAIT_IMAGE, wait.kind); assertEquals(20_000L, wait.durationMs)
        val tapAgain = change(wait, es.macrostudio.ui.ImageOrder.TAP)
        assertEquals(ClickTarget.OFFSET, tapAgain.clickTarget); assertEquals(20_000L, tapAgain.waitMs); assertEquals(80L, tapAgain.durationMs)

        // Mantener pulsada y doble toque.
        assertEquals(ClickStyle.LONG, change(tap, es.macrostudio.ui.ImageOrder.HOLD).clickStyle); assertTrue(change(tap, es.macrostudio.ui.ImageOrder.HOLD).durationMs >= 500)
        assertEquals(ClickStyle.DOUBLE, change(tap, es.macrostudio.ui.ImageOrder.DOUBLE).clickStyle); assertEquals(80L, change(change(tap, es.macrostudio.ui.ImageOrder.HOLD), es.macrostudio.ui.ImageOrder.TAP).durationMs)

        // «Si sale…»: dentro, tocarla en el mismo sitio.
        val ifSeen = change(tap, es.macrostudio.ui.ImageOrder.IF)
        assertEquals(Kind.IF_IMAGE, ifSeen.kind); assertEquals(ClickTarget.OFFSET, ifSeen.children.single().clickTarget)

        // Nombre automático: cambia con la orden.
        val auto = tap.copy(name = "Tocar «Botón BATALLA»")
        assertEquals("Siempre que salga «Botón BATALLA»", change(auto, es.macrostudio.ui.ImageOrder.ALWAYS).name)

        // Un bloque con más pasos dentro no se puede convertir en «Tocarla» (se perderían).
        val busy = always.copy(children = always.children + Action(kind = Kind.BACK, name = "Atrás"))
        assertTrue(es.macrostudio.ui.hasInnerSteps(busy))
        assertFalse(es.macrostudio.ui.ImageOrder.TAP in es.macrostudio.ui.possibleOrders(busy))
        assertEquals(busy, change(busy, es.macrostudio.ui.ImageOrder.TAP))
        assertEquals(busy.children, change(busy, es.macrostudio.ui.ImageOrder.IF).children)
    }

    @Test fun waitingUntilAnImageIsGoneIsSavedAndOldStepsStayTheSame() {
        val gone=Action(kind=Kind.WAIT_IMAGE,name="Abrir todas las cajas",templateId="caja",durationMs=240_000,waitMs=15_000,untilGone=true)
        val normal=Action(kind=Kind.WAIT_IMAGE,name="Esperar el lobby",templateId="lobby",durationMs=90_000)
        val back=es.macrostudio.projects.ProjectStore.decode(es.macrostudio.projects.ProjectStore.encode(Project(actions=listOf(gone,normal),templates=listOf("caja","lobby").map { SavedTemplate(id=it,name=it,template=Template("$it.png",0,0,10,10,1920,1080)) }))).actions
        assertTrue(back[0].untilGone); assertEquals(15_000L,back[0].waitMs)
        assertFalse(back[1].untilGone)
        // Cambiar lo que hace el paso lo devuelve a «esperar a que salga».
        assertFalse(es.macrostudio.ui.changeOrder(back[0],es.macrostudio.ui.ImageOrder.TAP,"Caja",60_000).untilGone)
    }

    // ---------- ⏱ Reloj: «Marcar la hora» y «Esperar hasta X desde la marca» ----------
    @Test fun clockWaitWithOldMarkDoesNotWait() {
        val clock=ClockMarks(); clock.mark("reloj",1_000)
        // Marcada hace 7 min y hay que llegar a 6 min: ya pasó, no espera nada.
        assertEquals(0L,clock.leftMs("reloj",360_000,1_000+420_000))
        // Justo en el límite tampoco.
        assertEquals(0L,clock.leftMs("reloj",360_000,1_000+360_000))
    }
    @Test fun clockWaitWithoutMarkDoesNotWait() {
        val clock=ClockMarks()
        assertNull(clock.markedAt("reloj"))
        assertEquals(0L,clock.leftMs("reloj",360_000,50_000))
        assertEquals(0L,ClockMarks.leftMs(null,360_000,50_000))
        // Otra marca no cuenta.
        clock.mark("caja",10_000)
        assertEquals(0L,clock.leftMs("reloj",360_000,20_000))
    }
    @Test fun clockWaitWithRecentMarkWaitsOnlyWhatIsLeft() {
        val clock=ClockMarks(); clock.mark("reloj",100_000)
        // Los pasos de en medio tardaron 2 min 13 s: faltan 3 min 47 s para los 6 min.
        assertEquals(227_000L,clock.leftMs("reloj",360_000,100_000+133_000))
        // Recién marcada: espera todo.
        assertEquals(360_000L,clock.leftMs("reloj",360_000,100_000))
        // Volver a marcar pone el reloj a cero otra vez.
        clock.mark("reloj",400_000)
        assertEquals(300_000L,clock.leftMs("reloj",360_000,460_000))
        // Al parar la macro se borra.
        clock.clear(); assertEquals(0L,clock.leftMs("reloj",360_000,460_000))
    }
    @Test fun clockMarkNameComesFromNoteAndDefaultsToReloj() {
        assertEquals("reloj",ClockMarks.nameOf(Action(kind=Kind.MARK_TIME)))
        assertEquals("reloj",ClockMarks.nameOf(Action(kind=Kind.MARK_TIME,description="   ")))
        assertEquals("Partida",ClockMarks.nameOf(Action(kind=Kind.MARK_TIME,description=" Partida ")))
        // Mayúsculas y espacios no importan para encontrar la marca.
        val clock=ClockMarks(); clock.mark("Partida",0)
        assertEquals(60_000L,clock.leftMs(" partida",60_000,0))
    }
    @Test fun waitSinceMarkAllowsUpToOneHour() {
        fun valid(kind: Kind,ms: Long)=Project(actions=listOf(Action(kind=kind,durationMs=ms))).validate()==null
        assertTrue(valid(Kind.WAIT_SINCE_MARK,1))
        assertTrue(valid(Kind.WAIT_SINCE_MARK,3_600_000))
        assertFalse(valid(Kind.WAIT_SINCE_MARK,3_600_001))
        assertFalse(valid(Kind.WAIT_SINCE_MARK,0))
        // Los demás pasos siguen con su máximo de 10 min.
        assertFalse(valid(Kind.WAIT,600_001))
        assertTrue(valid(Kind.MARK_TIME,80))
    }
    @Test fun clockStepsSurviveSaveAndOldProjectsStillOpen() {
        val store=es.macrostudio.projects.ProjectStore
        val withClock=Project(repeatCount=0,actions=listOf(
            Action(kind=Kind.MARK_TIME,name="Marcar la hora",description="partida"),
            Action(kind=Kind.WAIT,durationMs=5_000),
            Action(kind=Kind.WAIT_SINCE_MARK,name="Esperar hasta 6 min desde la marca",description="partida",durationMs=360_000)))
        val json=store.encode(withClock)
        assertEquals(5,org.json.JSONObject(json).getInt("schemaVersion"))
        val back=store.decode(json)
        assertEquals(withClock.actions,back.actions)
        // Una macro sin reloj se sigue guardando como antes (versión 4): la abren las apps antiguas.
        assertEquals(4,org.json.JSONObject(store.encode(Project(actions=listOf(Action(kind=Kind.WAIT,durationMs=1000))))).getInt("schemaVersion"))
        // Y las versiones 2, 3 y 4 se siguen abriendo.
        for(v in 2..4) {
            val old=org.json.JSONObject(store.encode(Project(actions=listOf(Action(kind=Kind.WAIT,durationMs=1000))))).put("schemaVersion",v)
            assertEquals(1,store.decode(old.toString()).actions.size)
        }
        // Una versión del futuro no.
        assertTrue(runCatching { store.decode(org.json.JSONObject(json).put("schemaVersion",6).toString()) }.isFailure)
    }
    @Test fun clockTimeEstimate() {
        val p=Project()
        assertEquals(Span.ZERO,stepTime(Action(kind=Kind.MARK_TIME),p))
        assertEquals(Span(0,360_000),stepTime(Action(kind=Kind.WAIT_SINCE_MARK,durationMs=360_000),p))
    }
}
