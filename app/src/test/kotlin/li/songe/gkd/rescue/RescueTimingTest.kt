package li.songe.gkd.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTimingTest {
    @Test fun sustainedFreshAdvertisementNeedsHelpAfterEightSeconds() {
        val tracker = AdFailureTracker()
        tracker.observe("video#7", true, 1_000L)
        tracker.observe("video#7", true, 8_999L)
        assertFalse(tracker.needsHelp(8_999L))
        tracker.observe("video#7", true, 9_000L)
        assertTrue(tracker.needsHelp(9_000L))
        tracker.observe("video#7", false, 9_001L)
        assertFalse(tracker.needsHelp(20_000L))
    }

    @Test fun newWindowAndClockResetCannotInheritOldHelp() {
        val tracker = AdFailureTracker()
        tracker.observe("video#7", true, 1_000L)
        assertTrue(tracker.needsHelp(9_000L))
        tracker.observe("video#8", true, 9_001L)
        assertFalse(tracker.needsHelp(9_002L))
        tracker.observe("video#8", true, 0L)
        assertFalse(tracker.needsHelp(8_000L))
    }

    @Test fun quickAppReopeningIsBoundedWithoutDisablingProtection() {
        val guard = QuickAppGuard()
        val host = "com.huawei.fastapp"
        assertEquals(QuickAppGuard.Decision.RETURN_HOME, guard.onForeground(host, 0L))
        assertEquals(QuickAppGuard.Decision.IGNORE, guard.onForeground(host, 500L))
        assertEquals(QuickAppGuard.Decision.RETURN_HOME, guard.onForeground(host, 1_500L))
        assertEquals(QuickAppGuard.Decision.HELP, guard.onForeground(host, 3_000L))
        assertEquals(QuickAppGuard.Decision.RETURN_HOME, guard.onForeground(host, 20_000L))
    }

    @Test fun normalAndLookalikePackagesAreNeverExited() {
        val guard = QuickAppGuard()
        assertEquals(QuickAppGuard.Decision.IGNORE, guard.onForeground("com.ss.android.ugc.aweme", 0L))
        assertEquals(QuickAppGuard.Decision.IGNORE, guard.onForeground("com.huawei.fastapp.example", 2_000L))
        assertEquals(QuickAppGuard.Decision.RETURN_HOME, guard.onForeground("com.hihonor.fastapp", 4_000L))
    }
}
