package io.github.haku4130.noscrollguard.restart

enum class ReopenAction { NOTHING, LAUNCH, ASK_USER }

/**
 * Whether to open the guarded app on unlock.
 *
 * A background start without SYSTEM_ALERT_WINDOW is not refused with an exception — it
 * is silently dropped, and startActivity returns as if it had worked. So the permission
 * has to be checked up front; otherwise the journal reports a revival that never
 * happened. When the launch cannot go out, the user has to do it: a tap on a
 * notification is a foreground start and is always allowed.
 */
fun decideReopen(needed: Boolean, mayStartFromBackground: Boolean): ReopenAction = when {
    !needed -> ReopenAction.NOTHING
    mayStartFromBackground -> ReopenAction.LAUNCH
    else -> ReopenAction.ASK_USER
}
