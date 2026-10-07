package io.github.haku4130.noscrollguard.service

import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.haku4130.noscrollguard.Constants
import io.github.haku4130.noscrollguard.GuardApp
import io.github.haku4130.noscrollguard.evidence.EvidenceCollector
import io.github.haku4130.noscrollguard.repair.AccessibilityRepairer
import io.github.haku4130.noscrollguard.repair.RepairResult
import io.github.haku4130.noscrollguard.restart.ReopenAction
import io.github.haku4130.noscrollguard.restart.decideReopen
import io.github.haku4130.noscrollguard.settings.AndroidSecureSettings
import io.github.haku4130.noscrollguard.settings.SecureKeys
import io.github.haku4130.noscrollguard.state.AccessibilityStateReader
import io.github.haku4130.noscrollguard.state.OverlayPermissionProbe
import io.github.haku4130.noscrollguard.state.PermissionWatch
import io.github.haku4130.noscrollguard.work.HealthWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class GuardService : Service() {

    private lateinit var observer: ContentObserver
    private lateinit var wakeReceiver: BroadcastReceiver

    override fun onCreate() {
        super.onCreate()
        GuardNotifications.ensureChannels(this)
        startForeground(GuardNotifications.ID_ONGOING, GuardNotifications.ongoing(this))
        HealthWorker.schedule(this)

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                thread { checkAndRepair(this@GuardService, "observer") }
            }
        }
        listOf(SecureKeys.ACCESSIBILITY_ENABLED, SecureKeys.ENABLED_SERVICES).forEach { key ->
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, observer
            )
        }

        // A crashed service leaves the settings untouched, so the observer never fires for
        // it. Blocking only matters while the screen is on, so re-check whenever the user
        // picks the phone up — that turns a 15-minute worst case into a few seconds.
        wakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val unlocked = intent.action == Intent.ACTION_USER_PRESENT
                thread {
                    checkAndRepair(context, if (unlocked) "unlock" else "screen on")
                    // Android TV may never emit USER_PRESENT after waking. SCREEN_ON is the
                    // important event here: repair first, then immediately return to Projectivy.
                    // The restart flag is deliberately kept while the panel is asleep.
                    reopenGuardedAppIfNeeded(context)
                }
            }
        }
        registerReceiver(
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )

        // The observer only sees changes. If the setting was broken while the guard was
        // dead (reboot, force-stop, memory cleanup) there is no event left to catch,
        // so check the state immediately on start.
        thread { checkAndRepair(this, "startup") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        runCatching { unregisterReceiver(wakeReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        /** A repair is running right now — ignore our own writes. */
        private val repairing = AtomicBoolean(false)

        /** When the last repair finished: events right after it are echoes of our writes. */
        private val settledAt = AtomicLong(0L)

        /** Quiet window after a repair, long enough for the observer to drain our writes. */
        private const val QUIET_AFTER_REPAIR_MS = 3000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuardService::class.java))
        }

        /**
         * Shared entry point for the observer, the startup check and HealthWorker.
         * Evidence first, repair second — otherwise our own writes overwrite the evidence.
         */
        fun checkAndRepair(context: Context, source: String) {
            // A repair writes the very settings the observer watches. Without these two
            // guards every write would trigger another repair.
            if (repairing.get()) return
            if (System.currentTimeMillis() - settledAt.get() < QUIET_AFTER_REPAIR_MS) return

            checkOverlayPermission(context, source)
            checkOwnPermissions(context, source)

            val settings = AndroidSecureSettings(context.contentResolver)
            val reader = AccessibilityStateReader(settings)
            if (reader.isHealthy()) return

            // Claim the work before collecting evidence, not after. Evidence collection
            // runs dumpsys and takes ~100ms; without claiming first, every observer thread
            // woken by the same reset walks through that window and logs a duplicate.
            if (!repairing.compareAndSet(false, true)) return
            try {

            val settingsBroken = !reader.isSettingsHealthy()
            val kind = if (settingsBroken) "permission was switched off" else "service was stuck crashed"
            Log.i("GuardTrace", "[$source] unhealthy ($kind), collecting evidence")
            val log = GuardApp.eventLog(context)
            val evidence = EvidenceCollector(context, settings).collect()
            log.append(evidence.timestampMs, "[$source] $kind — ${evidence.describe()}")

            if (GuardApp.pauseState(context).isPaused()) {
                log.append(System.currentTimeMillis(), "[$source] paused — standing down")
                return
            }


            Log.i("GuardTrace", "[$source] starting repair")
            val result = AccessibilityRepairer(settings).repair()

            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(evidence.timestampMs))
            val message = when (result) {
                is RepairResult.Success ->
                    "$kind at $time, restored. " +
                        "Foreground app: ${evidence.foregroundApp ?: "unknown"}. " +
                        "Setting written by: ${evidence.lastWriterPackage ?: "could not determine"}"
                is RepairResult.NoPermission ->
                    "Reset at $time, repair failed: WRITE_SECURE_SETTINGS not granted"
                is RepairResult.Failed ->
                    "Reset at $time, repair failed: ${result.reason}"
            }
            Log.i("GuardTrace", "[$source] repair finished: $result")
            log.append(System.currentTimeMillis(), "[$source] $message")
            GuardNotifications.notifyRepair(context, message)

            // Do not launch while the TV panel is still asleep. startActivity() can report
            // success in that state even though nothing becomes visible, which would consume
            // the restart flag. Keep it pending; SCREEN_ON will reopen Projectivy immediately.
            if (result is RepairResult.Success) GuardApp.restartFlag(context).markNeeded()
            } finally {
                settledAt.set(System.currentTimeMillis())
                repairing.set(false)
            }
        }

        /**
         * Watched separately from accessibility, because it fails separately and silently:
         * the guarded app keeps reporting itself active while being unable to put anything
         * on screen. We can only detect and tell the user — restoring it needs a
         * signature-level permission.
         */
        private val overlayWatch = PermissionWatch()

        private fun checkOverlayPermission(context: Context, source: String) {
            when (overlayWatch.update(OverlayPermissionProbe.isAllowed(context))) {
                PermissionWatch.Change.REVOKED -> {
                    GuardApp.eventLog(context).append(
                        System.currentTimeMillis(),
                        "[$source] overlay permission revoked — the app cannot show its blocking screen"
                    )
                    GuardNotifications.notifyOverlayRevoked(context)
                }
                PermissionWatch.Change.RESTORED -> {
                    GuardApp.eventLog(context).append(
                        System.currentTimeMillis(),
                        "[$source] overlay permission is back — the app must be reopened to pick it up"
                    )
                    // Apps read this permission when they start. Restoring it under a running
                    // process changes nothing until that process restarts, which cost an
                    // afternoon to discover.
                    GuardNotifications.notifyOverlayRestored(context)
                }
                null -> Unit
            }
        }

        /**
         * The guard's own permissions go missing too, and each one fails silently: without
         * SYSTEM_ALERT_WINDOW every reopen is dropped, without POST_NOTIFICATIONS every
         * warning. Both were found revoked on a device whose journal looked healthy.
         */
        private val ownOverlayWatch = PermissionWatch()
        private val notificationsWatch = PermissionWatch()

        private fun checkOwnPermissions(context: Context, source: String) {
            val log = GuardApp.eventLog(context)
            when (ownOverlayWatch.update(Settings.canDrawOverlays(context))) {
                PermissionWatch.Change.REVOKED -> {
                    log.append(
                        System.currentTimeMillis(),
                        "[$source] guard's own overlay permission revoked — it cannot reopen the app after a repair"
                    )
                    GuardNotifications.notifyGuardOverlayRevoked(context)
                }
                PermissionWatch.Change.RESTORED -> log.append(
                    System.currentTimeMillis(),
                    "[$source] guard's own overlay permission is back"
                )
                null -> Unit
            }
            // Nothing to notify with, so the journal is the only place this can show up.
            when (notificationsWatch.update(GuardNotifications.canNotify(context))) {
                PermissionWatch.Change.REVOKED -> log.append(
                    System.currentTimeMillis(),
                    "[$source] notifications are off — warnings will not reach the user"
                )
                PermissionWatch.Change.RESTORED -> log.append(
                    System.currentTimeMillis(),
                    "[$source] notifications are back on"
                )
                null -> Unit
            }
        }

        /**
         * Opens the guarded app once, if a repair has left it needing a restart.
         *
         * Needs SYSTEM_ALERT_WINDOW on this app, which is what exempts it from the
         * background-activity-start restriction. Without it the launch is silently
         * dropped — startActivity does not throw — so the permission is checked first
         * and the job handed to the user instead.
         */
        private fun reopenGuardedAppIfNeeded(context: Context) {
            val flag = GuardApp.restartFlag(context)
            val action = decideReopen(flag.isNeeded(), Settings.canDrawOverlays(context))
            if (action == ReopenAction.NOTHING) return

            val waited = flag.pendingForMs() / 1000
            if (action == ReopenAction.ASK_USER) {
                // One notification per repair: it stays until tapped, so keeping the flag
                // would only repeat it on every unlock.
                flag.clear()
                GuardApp.eventLog(context).append(
                    System.currentTimeMillis(),
                    "[unlock] could not reopen ${Constants.NOSCROLL_PACKAGE}: guard's own overlay " +
                        "permission is revoked — asked the user to open it (waited ${waited}s)"
                )
                GuardNotifications.notifyReopenNeeded(context)
                return
            }

            val launch = context.packageManager
                .getLaunchIntentForPackage(Constants.NOSCROLL_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return

            try {
                context.startActivity(launch)
                flag.clear()
                GuardApp.eventLog(context).append(
                    System.currentTimeMillis(),
                    "[unlock] reopened ${Constants.NOSCROLL_PACKAGE} to revive it (waited ${waited}s)"
                )
            } catch (e: Exception) {
                GuardApp.eventLog(context).append(
                    System.currentTimeMillis(),
                    "[unlock] could not reopen the app: ${e.javaClass.simpleName} — open it by hand"
                )
                GuardNotifications.notifyReopenNeeded(context)
            }
        }
    }
}
