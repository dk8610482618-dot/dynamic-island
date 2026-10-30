package com.dynamicisland.engine.util
import android.app.PendingIntent
import android.content.Context
import android.util.Log
object PendingIntentCompat {
    fun send(context: Context? = null, pendingIntent: PendingIntent?): Boolean {
        if (pendingIntent == null) return false
        return try { pendingIntent.send(context, 0, null); true }
        catch (e: PendingIntent.CanceledException) { false }
        catch (e: RuntimeException) { Log.w("IslandAction", "Platform refused action", e); false }
    }
}
