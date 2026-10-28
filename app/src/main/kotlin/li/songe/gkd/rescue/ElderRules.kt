package li.songe.gkd.rescue

import android.content.Context
import android.util.AtomicFile
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import li.songe.gkd.app
import li.songe.gkd.R
import li.songe.gkd.appScope
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import li.songe.gkd.data.SubsItem
import li.songe.gkd.db.DbSet
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.client
import li.songe.gkd.util.json
import li.songe.gkd.util.ruleSummaryFlow
import li.songe.gkd.util.subsFolder
import li.songe.gkd.util.subsMapFlow
import li.songe.gkd.util.updateSubsMutex
import li.songe.gkd.util.usedSubsEntriesFlow
import java.io.ByteArrayOutputStream
import java.io.File

/** Bundled complete GKD subscriptions, with ad-only defaults applied before matching is ready. */
object ElderRules {
    internal data class Source(val key: String, val id: Long, val file: String, val url: String?,
        val bundledVersion: Int)

    internal val sources = listOf(
        Source("easyrescue", 2026101801L, "easyrescue.json5",
            null, 4),
        Source("ganlinte", 233, "ganlin_gkd.json5",
            "https://raw.githubusercontent.com/ganlinte/GKD-subscription/main/dist/ganlin_gkd.json5", 72),
        Source("aoguai", 86, "aoguai_gkd.json5",
            "https://raw.githubusercontent.com/aoguai/subscription/custom/dist/aoguai_gkd.json5", 89),
        Source("zhtdbobo", 0, "zhtdbobo_gkd.json5",
            "https://raw.githubusercontent.com/zhtdbobo/gkd-subscription/master/dist/zhtdbobo_gkd.json5", 3)
    )
    private const val DAY = 24 * 60 * 60_000L
    private const val MAX_SOURCE_LENGTH = 4 * 1024 * 1024
    private val operationMutex = Mutex()
    private var initializing = false
    private var dailyJob: Job? = null
    private var loaded = emptyList<RawSubscription>()
    private val preferences get() = app.getSharedPreferences("elder_rules", Context.MODE_PRIVATE)
    private val cacheFolder get() = File(app.filesDir, "elder-rules").apply { mkdirs() }

    @Volatile
    var ready = false
        private set
    @Volatile
    private var status = "正在准备广告规则…"

    fun statusText(): String = status

    fun setAutomaticRuleUpdates(enabled: Boolean) {
        RuntimeOptions.setAutomaticRuleUpdatesEnabled(enabled)
        if (enabled) startDailyUpdates()
        // Let an in-flight install complete; the loop exits before another automatic check.
    }

    @Synchronized
    fun ensureInitialized() {
        if (ready || initializing) return
        initializing = true
        appScope.launch(Dispatchers.IO) {
            try {
                operationMutex.withLock {
                    if (!ready) initialize()
                }
                startDailyUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "广告规则准备失败，请重新打开应用重试"
                LogUtils.error("ElderRules initialization", e)
            } finally {
                synchronized(this@ElderRules) { initializing = false }
            }
        }
    }

    /** Returns promptly; callers always receive their result on the main dispatcher. */
    fun checkUpdates(callback: (String) -> Unit) {
        appScope.launch(Dispatchers.IO) {
            val message = try {
                operationMutex.withLock {
                    if (!ready) initialize()
                    update()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.error("ElderRules update", e)
                if (ready) "暂时无法更新，现有广告规则继续使用" else "规则准备失败，请稍后重试"
            }
            startDailyUpdates()
            withContext(Dispatchers.Main) { callback(message) }
        }
    }

    private suspend fun initialize() {
        if (!preferences.getBoolean("settings_migrated", false)) {
            val legacyPaused = app.getSharedPreferences("assist", Context.MODE_PRIVATE)
                .getBoolean("protection_paused", false)
            storeFlow.update {
                it.copy(enableMatch = it.enableMatch && !legacyPaused,
                    toastWhenClick = false, updateSubsInterval = 0)
            }
            preferences.edit().putBoolean("settings_migrated", true).apply()
        }
        val initial = sources.map { source ->
            var best: RawSubscription? = null
            // Select last-good cache without retaining several complete parsed rule trees.
            for (file in listOf(subsFolder.resolve("${source.id}.json"), cacheFolder.resolve(source.file))) {
                if (!file.isFile) continue
                val candidate = runCatching { parse(source, file.readText()) }.getOrNull() ?: continue
                if (best == null || candidate.version > best.version) best = candidate
            }
            // Same/newer valid cache already contains the complete source; avoid reparsing assets.
            if (best != null && best.version >= source.bundledVersion) best
            else app.assets.open("elder_rules/${source.key}/${source.file}")
                .bufferedReader().use { parse(source, it.readText()) }
        }
        install(initial)
    }

    private suspend fun update(): String {
        preferences.edit().putLong("last_check", System.currentTimeMillis()).apply()
        val next = loaded.toMutableList()
        val rawUpdates = mutableMapOf<Source, String>()
        var failed = 0
        var attempted = 0
        sources.forEachIndexed { index, source ->
            val url = if (source.key == "easyrescue")
                app.getString(R.string.elder_rules_url).takeIf { it.isNotBlank() }
            else source.url
            url ?: return@forEachIndexed
            attempted++
            try {
                val text = withTimeoutOrNull(30_000L) {
                    // Scoped execution streams instead of buffering an unbounded response first.
                    client.prepareGet(url).execute { response ->
                        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                        val advertisedSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        require(advertisedSize == null || advertisedSize <= MAX_SOURCE_LENGTH) {
                            "Rule source is too large"
                        }
                        val input = response.bodyAsChannel()
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.readAvailable(buffer, 0,
                                minOf(buffer.size, MAX_SOURCE_LENGTH + 1 - bytes.size()))
                            if (count < 0) break
                            bytes.write(buffer, 0, count)
                            require(bytes.size() <= MAX_SOURCE_LENGTH) { "Rule source is too large" }
                        }
                        bytes.toString(Charsets.UTF_8.name())
                    }
                } ?: error("Source request timed out")
                val subscription = parse(source, text)
                if (subscription.version > loaded[index].version) {
                    next[index] = subscription
                    rawUpdates[source] = text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                LogUtils.d("ElderRules source update", source.key, e.message)
            }
        }
        if (rawUpdates.isNotEmpty()) {
            val previous = loaded
            try {
                install(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    install(previous)
                } catch (restore: Exception) {
                    e.addSuppressed(restore)
                }
                throw e
            }
            rawUpdates.forEach { (source, raw) -> atomicWrite(cacheFolder.resolve(source.file), raw) }
        }
        return when {
            failed == attempted -> "暂时无法联网更新，现有广告规则继续使用"
            failed > 0 -> "已检查规则，部分来源暂时无法连接；现有规则继续使用"
            rawUpdates.isNotEmpty() -> "已更新 ${rawUpdates.size} 个规则源"
            else -> "广告规则已是最新"
        }
    }

    private suspend fun install(subscriptions: List<RawSubscription>) {
        val defaults = ElderRuleDefaults.select(subscriptions)
        val expected = defaults.associateBy { it.key }
        ready = false
        updateSubsMutex.withStateLock {
            val existing = DbSet.subsConfigDao.queryAll().associateBy {
                ElderRuleDefaults.Key(it.subsId, it.type, it.appId, it.groupKey)
            }
            val configurations = defaults.map { choice ->
                val key = choice.key
                (existing[key] ?: SubsConfig(type = key.type, subsId = key.source,
                    appId = key.appId, groupKey = key.group)).copy(
                    enable = choice.enabled, exclude = choice.exclude)
            }
            // Explicit overrides cover *all* groups, including disabled non-ad groups in new versions.
            DbSet.subsConfigDao.insert(*configurations.toTypedArray())
            subscriptions.forEachIndexed { index, raw ->
                atomicWrite(subsFolder.resolve("${raw.id}.json"), json.encodeToString(raw))
                // Upstream raw updater cannot bypass the ad-only defaults. This module owns updates.
                DbSet.subsItemDao.insert(SubsItem(id = raw.id, enable = true, enableUpdate = false,
                    order = index, updateUrl = null))
            }
            subsMapFlow.update { previous -> previous + subscriptions.associateBy { it.id } }
        }
        val versions = subscriptions.associate { it.id to it.version }
        val synchronized = withTimeoutOrNull(20_000L) {
            usedSubsEntriesFlow.first { entries ->
                versions.all { (id, version) -> entries.any {
                    it.subscription.id == id && it.subscription.version == version
                } }
            }
            val expectedGlobals = subscriptions.flatMap { raw ->
                raw.globalGroups.filter { group ->
                    expected[ElderRuleDefaults.Key(raw.id, SubsConfig.GlobalGroupType, "", group.key)]
                        ?.enabled == true && group.valid
                }.map { ElderRuleDefaults.Key(raw.id, SubsConfig.GlobalGroupType, "", it.key) }
            }.toSet()
            ruleSummaryFlow.first { summary ->
                summary.globalGroups.filter { it.subscription.id in versions }.map {
                    ElderRuleDefaults.Key(it.subscription.id, SubsConfig.GlobalGroupType, "", it.group.key)
                }.toSet() == expectedGlobals &&
                    (summary.globalGroups + summary.appIdToAllGroups.values.flatten()).all { group ->
                        if (group.subscription.id !in versions) true else {
                            val key = ElderRuleDefaults.Key(group.subscription.id, group.group.groupType,
                                group.appId.orEmpty(), group.group.key)
                            group.subscription.version == versions[group.subscription.id] &&
                                group.config?.enable == expected[key]?.enabled &&
                                group.config?.exclude == expected[key]?.exclude
                        }
                    }
            }
            true
        }
        check(synchronized == true) { "Rule state did not finish loading" }
        loaded = subscriptions
        val selectedGroups = defaults.count { it.enabled }
        status = "${subscriptions.size} 个完整规则源 · $selectedGroups 组广告规则"
        ready = true
    }

    @Synchronized
    private fun startDailyUpdates() {
        if (!RuntimeOptions.automaticRuleUpdatesEnabled || !ready || dailyJob?.isActive == true) return
        dailyJob = appScope.launch(Dispatchers.IO) {
            delay(5000L)
            while (isActive && RuntimeOptions.automaticRuleUpdatesEnabled) {
                val elapsed = System.currentTimeMillis() - preferences.getLong("last_check", 0L)
                if (ready && (elapsed >= DAY || elapsed < 0)) {
                    try {
                        operationMutex.withLock {
                            if (ready && RuntimeOptions.automaticRuleUpdatesEnabled) update()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogUtils.d("ElderRules daily update", e)
                    }
                }
                delay(60 * 60_000L)
            }
        }
    }

    internal fun parse(source: Source, text: String): RawSubscription {
        require(text.length <= MAX_SOURCE_LENGTH) { "Rule source is too large" }
        return RawSubscription.parse(text).also {
            require(it.id == source.id && it.version >= 0 && it.hasRule) { "Unexpected rule source" }
        }
    }

    private fun atomicWrite(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Throwable) {
            atomic.failWrite(output)
            throw e
        }
    }
}
