package io.github.haku4130.noscrollguard

import io.github.haku4130.noscrollguard.restart.ReopenAction
import io.github.haku4130.noscrollguard.restart.decideReopen
import org.junit.Assert.assertEquals
import org.junit.Test

class ReopenDecisionTest {

    @Test
    fun `nothing to do when no repair is pending`() {
        assertEquals(ReopenAction.NOTHING, decideReopen(needed = false, mayStartFromBackground = true))
        assertEquals(ReopenAction.NOTHING, decideReopen(needed = false, mayStartFromBackground = false))
    }

    @Test
    fun `launches when pending and allowed to start from the background`() {
        assertEquals(ReopenAction.LAUNCH, decideReopen(needed = true, mayStartFromBackground = true))
    }

    @Test
    fun `hands over to the user when the launch would be silently dropped`() {
        assertEquals(ReopenAction.ASK_USER, decideReopen(needed = true, mayStartFromBackground = false))
    }
}
