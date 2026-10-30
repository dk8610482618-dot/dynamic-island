package com.dynamicisland.engine.core.model

import java.util.Locale

enum class ActionSemantics { UNKNOWN, CANCEL_TIMER }

/** No generic stop/delete/first-action guesses. Localized unsupported labels stay UNKNOWN. */
object TimerActions {
    fun classify(title: String): ActionSemantics = if (title.trim().lowercase(Locale.ROOT) in
        setOf("cancel timer", "stop timer", "cancel countdown")) ActionSemantics.CANCEL_TIMER else ActionSemantics.UNKNOWN

    fun cancellation(activity: IslandActivity?): IslandAction? = activity?.takeIf {
        it.kind == ActivityKind.LIVE_TIMER
    }?.actions?.firstOrNull { it.semantics == ActionSemantics.CANCEL_TIMER && it.pendingIntent != null }
}
