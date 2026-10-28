package li.songe.gkd.rescue

import android.content.Context
import li.songe.gkd.app

/** Optional local diagnostics; ordinary ad skipping does not need these records. */
object RuntimeOptions {
    private val preferences = app.getSharedPreferences("runtime_options", Context.MODE_PRIVATE)

    @Volatile
    private var diagnosticState: Boolean = preferences.getBoolean("diagnostic_enabled", false)

    val diagnosticEnabled: Boolean
        get() = diagnosticState

    @Volatile
    private var automaticRuleUpdatesState = preferences.getBoolean("automatic_rule_updates", false)

    val automaticRuleUpdatesEnabled: Boolean
        get() = automaticRuleUpdatesState

    fun setAutomaticRuleUpdatesEnabled(enabled: Boolean) {
        automaticRuleUpdatesState = enabled
        preferences.edit().putBoolean("automatic_rule_updates", enabled).apply()
    }

    fun setDiagnosticEnabled(enabled: Boolean) {
        diagnosticState = enabled
        preferences.edit().putBoolean("diagnostic_enabled", enabled).apply()
    }
}
