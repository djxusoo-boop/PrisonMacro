package es.macrostudio

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import es.macrostudio.core.Studio
import es.macrostudio.ui.PrisonApp

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Studio.init(this)
        applyOrientationMode(getSharedPreferences("studio",0).getInt("orientationMode",0))
        setContent { PrisonApp(this) }
        Studio.sync.handleSignInIntent(intent)
        // Android 13+: permiso para el aviso «■ Parar» mientras se ejecuta una macro.
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }
    override fun onStart() {
        super.onStart()
        // La app se ve: vuelve a salir en Recientes.
        es.macrostudio.engine.KeepAlive.appVisible=true
        es.macrostudio.engine.KeepAlive.hideFromRecents(this,false)
        es.macrostudio.engine.KeepAlive.appScreen(true)
    }
    override fun onStop() {
        super.onStop()
        // Con la barra flotante puesta, «Cerrar todo» no debe cerrar PrisonMacro ni parar la macro.
        es.macrostudio.engine.KeepAlive.appVisible=false
        es.macrostudio.engine.KeepAlive.appScreen(false)
        if(es.macrostudio.gestures.MacroAccessibilityService.instance?.isBarShown()==true) es.macrostudio.engine.KeepAlive.hideFromRecents(this,true)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Studio.sync.handleSignInIntent(intent)
    }
    fun applyOrientationMode(mode: Int) {
        requestedOrientation=when(mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}
