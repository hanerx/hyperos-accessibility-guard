package io.github.haku4130.noscrollguard.state

/**
 * Turns repeated permission checks into news: reports a permission going missing or
 * coming back, once, instead of on every check.
 *
 * Lives in memory, so a fresh process starts out assuming the permission is there. A
 * permission lost while the guard was dead is therefore reported on the first check —
 * noisy after every restart, but never silent.
 */
class PermissionWatch {

    enum class Change { REVOKED, RESTORED }

    private var revoked = false

    /** @param allowed the current state, or null when it could not be determined. */
    fun update(allowed: Boolean?): Change? = when {
        allowed == null -> null
        !allowed && !revoked -> { revoked = true; Change.REVOKED }
        allowed && revoked -> { revoked = false; Change.RESTORED }
        else -> null
    }
}
