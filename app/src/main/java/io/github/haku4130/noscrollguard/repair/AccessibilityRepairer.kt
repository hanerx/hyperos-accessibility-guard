package io.github.haku4130.noscrollguard.repair

import io.github.haku4130.noscrollguard.Constants
import io.github.haku4130.noscrollguard.settings.SecureKeys
import io.github.haku4130.noscrollguard.settings.SecureSettings

/** Pause between clearing the service list and writing it back. */
const val REBIND_PAUSE_MS = 2000L

sealed class RepairResult {
    object Success : RepairResult()
    object NoPermission : RepairResult()
    data class Failed(val reason: String) : RepairResult()
}

/**
 * Restores Projectivy's accessibility service without permanently disabling
 * any other accessibility services that were enabled by the user.
 */
class AccessibilityRepairer(
    private val settings: SecureSettings,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun repair(): RepairResult {
        // Preserve every currently configured service. HyperOS may remove only Projectivy,
        // while the original guard used to clear the list and restore only its target.
        val originalServices = settings.getString(SecureKeys.ENABLED_SERVICES)
            .orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        originalServices.add(Constants.NOSCROLL_SERVICE)
        val restoredServices = originalServices.joinToString(":")

        if (!settings.putInt(SecureKeys.ACCESSIBILITY_ENABLED, 0)) {
            return RepairResult.NoPermission
        }
        if (!settings.putString(SecureKeys.ENABLED_SERVICES, "")) {
            return RepairResult.Failed("could not clear the service list")
        }

        sleeper(REBIND_PAUSE_MS)

        if (!settings.putString(SecureKeys.ENABLED_SERVICES, restoredServices)) {
            return RepairResult.Failed("could not restore the accessibility service list")
        }
        if (!settings.putInt(SecureKeys.ACCESSIBILITY_ENABLED, 1)) {
            return RepairResult.Failed("could not turn the master switch back on")
        }
        return RepairResult.Success
    }
}
