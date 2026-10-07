package io.github.haku4130.noscrollguard.repair

import io.github.haku4130.noscrollguard.Constants
import io.github.haku4130.noscrollguard.settings.SecureKeys
import io.github.haku4130.noscrollguard.settings.SecureSettings

sealed class RepairResult {
    object Success : RepairResult()
    object NoPermission : RepairResult()
    data class Failed(val reason: String) : RepairResult()
}

/**
 * Restores Projectivy immediately. HyperOS TV removes Projectivy from the enabled
 * accessibility-service list on screen-off; there is no reason to wait two seconds
 * before putting it back.
 *
 * Existing accessibility services are preserved.
 */
class AccessibilityRepairer(
    private val settings: SecureSettings,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun repair(): RepairResult {
        val originalServices = settings.getString(SecureKeys.ENABLED_SERVICES)
            .orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

        originalServices.add(Constants.NOSCROLL_SERVICE)
        val restoredServices = originalServices.joinToString(":")

        // Instant path: write the complete service list and master switch back immediately.
        // This is the normal HyperOS TV failure mode and avoids exposing the Xiaomi launcher.
        if (!settings.putString(SecureKeys.ENABLED_SERVICES, restoredServices)) {
            return RepairResult.NoPermission
        }
        if (!settings.putInt(SecureKeys.ACCESSIBILITY_ENABLED, 1)) {
            return RepairResult.Failed("could not turn the master switch back on")
        }
        return RepairResult.Success
    }
}
