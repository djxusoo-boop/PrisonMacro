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
