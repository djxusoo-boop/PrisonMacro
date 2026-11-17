package es.macrostudio.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import es.macrostudio.MainActivity

/**
 * Aviso en la barra de Android mientras una macro se ejecuta, con «■ Parar».
 * Así siempre se puede parar, aunque el panel flotante esté escondido.
 */
object RunNotice {
    private const val ID = 18
    private const val CHANNEL = "running"

    fun show(context: Context, name: String) = runCatching {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Macro en marcha", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(app, 3, Intent(app, StopMacroReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(app, 4, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("▶ PrisonMacro: «$name» en marcha")
            .setContentText("Pulsa «■ Parar» para detenerla.")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "■ Parar", stop)
            .setOngoing(true).build())
    }

    fun hide(context: Context) = runCatching { context.applicationContext.getSystemService(NotificationManager::class.java).cancel(ID) }
}

class StopMacroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Engine.stop("Parada manual") }
}
