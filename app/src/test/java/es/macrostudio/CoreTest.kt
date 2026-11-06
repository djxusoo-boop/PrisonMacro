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
    @Test fun firstCaptureCanInitializeQuickStartWhileAssigningCondition() {
        val target=Action(kind=Kind.IF_IMAGE)
        val saved=SavedTemplate(id="primera",name="Primera",template=Template("primera.png",0,0,20,20,100,100))
        val updated=addCapturedTemplate(Project(actions=listOf(target)),saved,target.id)
        assertEquals(saved.template,updated.template)
        assertEquals(saved.id,updated.actions.single().templateId)
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
}
