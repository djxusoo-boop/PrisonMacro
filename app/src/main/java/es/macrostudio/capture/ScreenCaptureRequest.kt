package es.macrostudio.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

object ScreenCaptureRequest {
    const val REQUEST_CODE = 4101
    fun createIntent(context: Context): Intent {
        val manager=context.getSystemService(MediaProjectionManager::class.java)
        return if(android.os.Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
    }
    fun isGranted(resultCode: Int, data: Intent?) = resultCode == Activity.RESULT_OK && data != null
}
