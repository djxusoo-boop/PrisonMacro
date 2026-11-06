package es.macrostudio.capture

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import es.macrostudio.MainActivity
import es.macrostudio.core.Studio
import es.macrostudio.engine.Engine
import java.io.File

class CaptureService: Service() {
    companion object { @Volatile var instance: CaptureService?=null; private set }
    private var projection: MediaProjection?=null
    private var display: VirtualDisplay?=null
    private var reader: ImageReader?=null
    private val thread=HandlerThread("screen-frames")
    private val lock=Any(); private var latest: Bitmap?=null; private var frameAt=0L
    private var w=0; private var h=0; private var dpi=0
    /** Lector de fotogramas a ~5 Hz. Conserva siempre el último fotograma. */
    private fun createReader(width: Int, height: Int): ImageReader {
        val r=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2); reader=r
        val captureHandler=Handler(thread.looper); var scheduled=false
        r.setOnImageAvailableListener({ source ->
            if(scheduled) return@setOnImageAvailableListener
            scheduled=true
            captureHandler.postDelayed({
                scheduled=false
                if(reader!==source) return@postDelayed
                val image=runCatching { source.acquireLatestImage() }.getOrNull() ?: return@postDelayed
                try {
                    val iw=image.width; val ih=image.height
                    val plane=image.planes[0]; val rowWidth=plane.rowStride/plane.pixelStride
                    val padded=Bitmap.createBitmap(rowWidth,ih,Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val frame=Bitmap.createBitmap(padded,0,0,iw,ih)
                    if(frame!==padded) padded.recycle()
                    synchronized(lock) { latest?.recycle(); latest=frame; frameAt=SystemClock.elapsedRealtime() }
                } catch(e: Exception) { Studio.log("Error de captura: ${e.message}") } finally { image.close() }
            },(180-(SystemClock.elapsedRealtime()-frameAt)).coerceAtLeast(0))
        },captureHandler)
        return r
    }
    private val main=Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); Studio.init(this); thread.start() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="STOP") { Engine.stop("Captura detenida"); stopSelf(); return START_NOT_STICKY }
        if(projection!=null) return START_NOT_STICKY
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture","Captura y automatización",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,2,Intent(this,CaptureService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(17,Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("PrisonMacro · captura activa").setContentText("Procesamiento local. Pulsa Detener para finalizar.").setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"Detener",stop).setOngoing(true).build())
        try {
            @Suppress("DEPRECATION") val data=intent?.getParcelableExtra<Intent>("data") ?: error("Falta autorización de captura")
            val p=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data)
            projection=p
            p.registerCallback(object: MediaProjection.Callback() { override fun onStop() { Engine.stop("Android ha cerrado la captura"); stopSelf() } },main)
            val metrics=android.util.DisplayMetrics()
            @Suppress("DEPRECATION") getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            w=metrics.widthPixels; h=metrics.heightPixels
            require(w.toLong()*h<=8_500_000) { "Resolución demasiado alta: máximo 8,5 megapíxeles" }
            val r=createReader(w,h)
            dpi=metrics.densityDpi
            display=p.createVirtualDisplay("PrisonMacro",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,r.surface,null,main)
            instance=this; Studio.captureReady=true; Studio.status="Captura autorizada"; Studio.log("Captura iniciada: ${w}×${h}")
        } catch(e: Exception) { Studio.fail("No se pudo iniciar la captura: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    // Static screens may not deliver a new image. Keep the latest frame until projection ends.
    fun frame(): Bitmap? = synchronized(lock) { latest?.copy(Bitmap.Config.ARGB_8888,false) }
    fun saveScreenshot(done: (Boolean)->Unit) {
        if(!thread.isAlive || instance!==this) { done(false); return }
        val posted=Handler(thread.looper).post {
            val frame=frame()
            val success=frame!=null && runCatching { AtomicScreenshot.save(File(filesDir,"capture.png"),frame); true }.getOrDefault(false)
            frame?.recycle(); main.post { if(success) Studio.screenshotVersion++; else Studio.fail("No hay imagen reciente. Reautoriza la captura de pantalla completa."); done(success) }
        }
        if(!posted) done(false)
    }
    /** Al girar la pantalla se redimensiona la captura en lugar de cerrarla,
     * para que abrir un juego horizontal no obligue a autorizar otra vez. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ resizeToDisplay() },300)
    }
    private fun resizeToDisplay() {
        val vd=display ?: return
        val metrics=android.util.DisplayMetrics()
        @Suppress("DEPRECATION") getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        if(metrics.widthPixels==w && metrics.heightPixels==h) return
        runCatching {
            val old=reader
            w=metrics.widthPixels; h=metrics.heightPixels; dpi=metrics.densityDpi
            val r=createReader(w,h)
            vd.resize(w,h,dpi); vd.surface=r.surface
            old?.setOnImageAvailableListener(null,null); old?.close()
            synchronized(lock) { latest?.recycle(); latest=null }
            Studio.log("Captura adaptada a ${w}×${h}")
        }.onFailure { Studio.fail("No se pudo adaptar la captura al giro: ${it.message}"); stopSelf() }
    }
    override fun onDestroy() {
        instance=null; Studio.captureReady=false; Engine.stop("Captura cerrada")
        display?.release(); display=null; reader?.setOnImageAvailableListener(null,null); reader?.close(); reader=null
        projection?.stop(); projection=null; thread.quitSafely()
        synchronized(lock) { latest?.recycle(); latest=null }
        super.onDestroy()
    }
}
