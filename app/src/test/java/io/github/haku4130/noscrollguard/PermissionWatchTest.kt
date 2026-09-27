package io.github.haku4130.noscrollguard

import io.github.haku4130.noscrollguard.state.PermissionWatch
import io.github.haku4130.noscrollguard.state.PermissionWatch.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionWatchTest {

    @Test
    fun `first sighting of a granted permission is not news`() {
        assertNull(PermissionWatch().update(true))
    }

    @Test
    fun `first sighting of a missing permission is reported`() {
        // A fresh process knows nothing — a permission lost while the guard was dead
        // must still be reported.
        assertEquals(Change.REVOKED, PermissionWatch().update(false))
    }

    @Test
    fun `a missing permission is reported once, not on every check`() {
        val watch = PermissionWatch()
        watch.update(false)
        assertNull(watch.update(false))
    }

    @Test
    fun `return of the permission is reported`() {
        val watch = PermissionWatch()
        watch.update(false)
        assertEquals(Change.RESTORED, watch.update(true))
        assertNull(watch.update(true))
    }

    @Test
    fun `unknown state changes nothing`() {
        val watch = PermissionWatch()
        watch.update(false)
        assertNull(watch.update(null))
        assertNull(watch.update(false))
    }
}
