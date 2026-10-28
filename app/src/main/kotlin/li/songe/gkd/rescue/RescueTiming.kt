package li.songe.gkd.rescue

/** Only fresh engine observations establish that an advertisement is still present. */
class AdFailureTracker {
    private var scope: String? = null
    private var firstSeen = -1L
    private var lastTime = -1L

    fun observe(value: String?, present: Boolean, now: Long) {
        if (!present || value.isNullOrEmpty()) { reset(); return }
        if (!acceptTime(now)) return
        if (scope != value) { scope = value; firstSeen = now }
    }

    fun needsHelp(now: Long): Boolean =
        acceptTime(now) && scope != null && now - firstSeen >= 8_000L

    fun reset() { scope = null; firstSeen = -1L; lastTime = -1L }

    private fun acceptTime(now: Long): Boolean {
        if (now < 0L || now < lastTime) { reset(); return false }
        lastTime = now
        return true
    }
}

class QuickAppGuard {
    enum class Decision { IGNORE, RETURN_HOME, HELP }
    private val returns = ArrayDeque<Long>()
    private var lastObserved = -1L
    private var lastDecision = -1L

    fun onForeground(packageName: String, now: Long): Decision {
        if (now < 0L || now < lastObserved) { reset(); return Decision.IGNORE }
        lastObserved = now
        if (!isHost(packageName) || lastDecision >= 0L && now - lastDecision < 1_500L) {
            return Decision.IGNORE
        }
        while (returns.isNotEmpty() && now - returns.first() >= 20_000L) returns.removeFirst()
        lastDecision = now
        if (returns.size >= 2) return Decision.HELP
        returns.addLast(now)
        return Decision.RETURN_HOME
    }

    fun reset() { returns.clear(); lastObserved = -1L; lastDecision = -1L }

    companion object {
        fun isHost(packageName: String?) = packageName == "com.huawei.fastapp"
                || packageName == "com.hihonor.fastapp"
    }
}
