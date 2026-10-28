package li.songe.gkd.rescue

import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import li.songe.selector.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the bundled rule data and defaults; these are not device-level acceptance tests. */
class ElderRuleDefaultsTest {
    private val assets = listOf(File("src/main/assets/elder_rules"),
        File("app/src/main/assets/elder_rules")).first { it.isDirectory }
    private val subscriptions by lazy {
        ElderRules.sources.map { source ->
            ElderRules.parse(source, File(assets, "${source.key}/${source.file}").readText())
        }
    }

    @Test
    fun completeLicensedSourcesAreParsedByTheUpstreamParserIncludingSourceZero() {
        assertEquals(listOf(2026101801L, 233L, 86L, 0L), subscriptions.map { it.id })
        assertEquals(listOf(4, 72, 89, 3), subscriptions.map { it.version })
        assertEquals(listOf(5, 610, 72, 615), subscriptions.map { it.apps.size })
        assertEquals(listOf(5, 1389, 280, 1380), subscriptions.map { it.apps.sumOf { a -> a.groups.size } })
        assertEquals(629, subscriptions.flatMap { it.apps }.map { it.id }.distinct().size)
        ElderRules.sources.forEach { source ->
            assertEquals("Bundled version must match the startup cache floor", source.bundledVersion,
                subscriptions.first { it.id == source.id }.version)
            val license = File(assets, "${source.key}/LICENSE").readText()
            assertTrue(source.key, license.contains("Permission is hereby granted")
                || license.contains("GNU GENERAL PUBLIC LICENSE"))
        }
    }

    @Test
    fun realWeiboAndDouyinAdsAreEnabledButNonAdBusinessFlowsAreNot() {
        val defaults = ElderRuleDefaults.select(subscriptions).associateBy { it.key }
        fun enabled(app: String, key: Int): Boolean = defaults.getValue(
            ElderRuleDefaults.Key(233L, SubsConfig.AppGroupType, app, key)).enabled
        listOf(5, 8, 13, 17).forEach { assertTrue("Weibo ad group $it", enabled("com.sina.weibo", it)) }
        listOf(3, 4, 6, 7, 9, 10, 11, 14, 15, 16, 18, 19).forEach {
            assertFalse("Weibo business group $it", enabled("com.sina.weibo", it))
        }
        assertTrue("Douyin local ad group", enabled("com.ss.android.ugc.aweme", 4))
        assertFalse("Douyin full-screen group is replaced locally", enabled("com.ss.android.ugc.aweme", 5))
        listOf(0, 1, 2, 3, 6, 7, 10, 11, 12, 13, 14, 15).forEach {
            assertFalse("Douyin business group $it", enabled("com.ss.android.ugc.aweme", it))
        }
        assertFalse(enabled("com.ss.android.ugc.aweme.lite", 2))
    }

    @Test
    fun douyinLocalFullscreenPreservesPopupAndScopesSdkAndCompletedAdRules() {
        val appId = "com.ss.android.ugc.aweme"
        val source = subscriptions.first()
        val group = source.getAppGroups(appId).single()
        val popup = subscriptions.first { it.id == 233L }.getAppGroups(appId).first { it.key == 5 }
        val sdk = subscriptions.first { it.id == 86L }.globalGroups.first { it.key == 1 }
        assertTrue(group.valid)
        assertEquals(listOf(0, 1, 2, 3), group.rules.map { it.key })
        assertEquals(null, group.activityIds)
        assertEquals(null, group.matchRoot)
        assertEquals(null, group.matchTime)
        assertEquals(null, group.actionMaximum)
        val original = group.rules.first()
        assertEquals(popup.rules.single().matches, original.matches)
        assertEquals(popup.fastQuery, original.fastQuery)
        assertEquals(null, original.activityIds)
        assertEquals(null, original.versionCode)
        assertEquals(null, original.matchTime)
        assertEquals(null, original.actionMaximum)
        group.rules.slice(1..2).zip(listOf(4, 5)).forEach { (rule, key) ->
            assertEquals(sdk.rules.first { it.key == key }.matches, rule.matches)
            assertEquals(listOf("com.tt.miniapphost.placeholder.MiniAppChildProcessMultiInsActivity0"),
                rule.activityIds)
            assertTrue(rule.versionCode!!.match(400701))
            assertFalse(rule.versionCode!!.match(400700))
            assertFalse(rule.versionCode!!.match(400702))
            assertFalse(rule.versionCode!!.match(400801))
            assertEquals(null, rule.matchTime)
            assertEquals(null, rule.actionMaximum)
        }
        val completed = group.rules.last()
        assertEquals(listOf("com.tt.miniapphost.placeholder.MiniAppChildProcessMultiInsActivity0"),
            completed.activityIds)
        assertTrue(completed.versionCode!!.match(400801))
        assertFalse(completed.versionCode!!.match(400701))
        assertFalse(completed.versionCode!!.match(400800))
        assertFalse(completed.versionCode!!.match(400802))
        assertEquals(true, completed.matchRoot)
        assertEquals("back", completed.action)
        assertEquals(3000L, completed.actionCd)
        assertEquals(300L, completed.actionDelay)
        assertEquals(null, completed.matchTime)
        assertEquals(null, completed.actionMaximum)
        assertEquals(listOf("""[desc="领取成功，关闭，按钮"][name="android.view.ViewGroup"][childCount=1] <<n @[id="com.ss.android.ugc.aweme:id/ir-"][visibleToUser=true][left=0][top=0][width=parent.width][height=parent.height]"""), completed.matches)
        val defaults = ElderRuleDefaults.select(subscriptions).associateBy { it.key }
        assertTrue(defaults.getValue(ElderRuleDefaults.Key(source.id,
            SubsConfig.AppGroupType, appId, group.key)).enabled)
        assertFalse(defaults.getValue(ElderRuleDefaults.Key(233L,
            SubsConfig.AppGroupType, appId, 5)).enabled)
        assertTrue(defaults.getValue(ElderRuleDefaults.Key(233L,
            SubsConfig.AppGroupType, appId, 4)).enabled)
        assertTrue(appId in defaults.getValue(ElderRuleDefaults.Key(86L,
            SubsConfig.GlobalGroupType, "", 1)).exclude.lines())
    }

    @Test
    fun wingPayLocalSplashOwnsOnlySplashAndExcludesTheGenericSecondClick() {
        val appId = "com.chinatelecom.bestpayclient"
        val source = subscriptions.first()
        val group = source.getAppGroups(appId).single()
        val rule = group.rules.single()
        assertEquals(2026101801L, source.id)
        assertTrue(group.valid)
        assertEquals("开屏广告-启动页", group.name)
        assertEquals(listOf("com.chinatelecom.bestpayclient.ui.MainActivity"), group.activityIds)
        assertEquals(true, group.fastQuery)
        assertEquals(10000L, group.matchTime)
        assertEquals("app", group.resetMatch)
        assertEquals(1, group.actionMaximum)
        assertEquals("clickNode", rule.action)
        assertEquals(listOf("[id=\"com.chinatelecom.bestpayclient:id/tv_ad_skip\"][text^=\"跳过\"][clickable=true]"), rule.matches)
        assertTrue(rule.anyMatches.isNullOrEmpty())
        assertTrue(subscriptions.drop(1).flatMap { it.getAppGroups(appId) }.none {
            ElderRuleDefaults.category(it.name) == "开屏广告"
        })

        val defaults = ElderRuleDefaults.select(subscriptions).associateBy { it.key }
        assertTrue(defaults.getValue(ElderRuleDefaults.Key(source.id,
            SubsConfig.AppGroupType, appId, group.key)).enabled)
        val opening = defaults.getValue(ElderRuleDefaults.Key(86L,
            SubsConfig.GlobalGroupType, "", 0))
        assertTrue(opening.enabled)
        assertTrue(appId in opening.exclude.lines())
        // The local splash rule must not remove the existing in-app full-screen popup rules.
        assertTrue(defaults.getValue(ElderRuleDefaults.Key(233L,
            SubsConfig.AppGroupType, appId, 3)).enabled)
    }

    @Test
    fun doubanCustomSplashIsSingleClickAndOwnsOnlyTheSplashCategory() {
        val appId = "com.douban.frodo"
        val source = subscriptions.first()
        val group = source.getAppGroups(appId).single()
        val rule = group.rules.single()
        assertTrue(group.valid)
        assertEquals(1, group.actionMaximum)
        assertEquals("app", group.resetMatch)
        assertEquals(10000L, group.forcedTime)
        assertEquals(listOf("com.douban.frodo.activity.SplashActivity"), group.activityIds)
        assertEquals("clickCenter", rule.action)
        assertTrue(rule.versionCode!!.match(364))
        assertTrue(rule.versionCode!!.match(365))
        assertFalse(rule.versionCode!!.match(366))
        assertEquals("width * 0.12", rule.position!!.right)
        assertEquals("width * 0.142", rule.position!!.top)
        assertTrue(rule.position!!.isValid)
        val defaults = ElderRuleDefaults.select(subscriptions).associateBy { it.key }
        assertTrue(defaults.getValue(ElderRuleDefaults.Key(source.id,
            SubsConfig.AppGroupType, appId, group.key)).enabled)
        assertTrue(appId in defaults.getValue(ElderRuleDefaults.Key(86L,
            SubsConfig.GlobalGroupType, "", 0)).exclude.lines())
        assertTrue(defaults.getValue(ElderRuleDefaults.Key(233L,
            SubsConfig.AppGroupType, appId, 10)).enabled)
    }

    @Test
    fun fivePlayAndMeetingSplashRulesOwnOnlyOpeningAndClickOnceInTheirObservedActivities() {
        val source = subscriptions.first()
        val defaults = ElderRuleDefaults.select(subscriptions).associateBy { it.key }
        for ((appId, activity) in listOf(
            "com.fiveplay" to "com.fiveplay.sihp_homepage.module.main.Main2Activity",
            "com.tencent.wemeet.app" to "com.tencent.wemeet.sdk.meeting.premeeting.home.HomeActivity")) {
            val group = source.getAppGroups(appId).single()
            assertTrue(group.valid)
            assertEquals("开屏广告", ElderRuleDefaults.category(group.name))
            assertEquals(listOf(activity), group.activityIds)
            assertEquals(10000L, group.matchTime)
            assertEquals(10000L, group.forcedTime)
            assertEquals("app", group.resetMatch)
            assertEquals(1, group.actionMaximum)
            assertEquals("clickNode", group.rules.single().action)
            assertTrue(defaults.getValue(ElderRuleDefaults.Key(source.id,
                SubsConfig.AppGroupType, appId, group.key)).enabled)
            assertTrue(appId in defaults.getValue(ElderRuleDefaults.Key(86L,
                SubsConfig.GlobalGroupType, "", 0)).exclude.lines())
        }
    }

    @Test
    fun permissionsRewardsRecommendationsAndUnrelatedPromptsNeverBecomeDefaultActions() {
        for (name in listOf("权限提示", "功能类-自动登录", "全屏广告-定位权限",
            "全屏广告-APP评分弹窗", "分段广告-关闭用户推荐", "全屏广告-设置无声进入抖音",
            "全屏广告-休息提醒", "全屏广告-功能体验邀请弹窗", "局部广告-首页顶部话题分享窗口",
            "局部广告-好友热播", "全屏广告-绑定账号", "全屏广告-公告弹窗",
            "全屏广告-添加到首页", "全屏广告-开启音乐DJ", "全屏广告-未成年人模式")) {
            assertFalse(name, ElderRuleDefaults.isAdvertisement(name, null))
        }
        for (description in listOf("自动领取奖励", "观看广告领取VIP", "自动观看广告30s",
            "请求关联抖音账号", "流量提醒自动确定", "注册说明", "NSFW内容提示",
            "开启云服务", "添加小组件", "密码过期", "自动备份", "相册同步", "用户引导")) {
            assertFalse(description, ElderRuleDefaults.isAdvertisement("全屏广告-弹窗", description))
        }
        assertTrue(ElderRuleDefaults.isAdvertisement("分段广告-信息流广告", "点击X-点击不感兴趣"))
    }

    @Test
    fun subscriptionsDoNotRaceTheSameAppCategoryAndLegacyOnlyAddsNewPackages() {
        val defaults = ElderRuleDefaults.select(subscriptions)
        val appGroups = defaults.filter { it.key.type == SubsConfig.AppGroupType && it.enabled }
        val owners = appGroups.groupBy { choice ->
            val raw = subscriptions.first { it.id == choice.key.source }
                .getAppGroups(choice.key.appId).first { it.key == choice.key.group }
            choice.key.appId to ElderRuleDefaults.category(choice.key.source, choice.key.appId, raw)
        }
        assertTrue(owners.values.all { it.map { choice -> choice.key.source }.distinct().size == 1 })
        val primaryPackages = subscriptions.filter { it.id != 0L }.flatMap { it.apps }.map { it.id }.toSet()
        assertTrue(appGroups.filter { it.key.source == 0L }.all { it.key.appId !in primaryPackages })
        val globals = defaults.filter { it.key.type == SubsConfig.GlobalGroupType && it.enabled }
        assertEquals(setOf(86L to 0, 86L to 1), globals.map { it.key.source to it.key.group }.toSet())
        val openingApps = owners.keys.filter { it.second == "开屏广告" }.map { it.first }.toSet()
        assertEquals(openingApps, globals.first { it.key.group == 0 }.exclude.lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun selectedRulesKeepTheirFullSelectorsExclusionsAndMultiStepDependencies() {
        val defaults = ElderRuleDefaults.select(subscriptions).associateBy { it.key }
        val enabledGroups = subscriptions.flatMap { source ->
            source.apps.flatMap { application -> application.groups.filter {
                defaults.getValue(ElderRuleDefaults.Key(source.id, SubsConfig.AppGroupType,
                    application.id, it.key)).enabled
            } } + source.globalGroups.filter {
                defaults.getValue(ElderRuleDefaults.Key(source.id, SubsConfig.GlobalGroupType, "", it.key)).enabled
            }
        }
        val selectors = enabledGroups.flatMap { it.rules }.flatMap { it.getAllSelectorStrings() }
        println("Elder defaults: ${enabledGroups.size} ad groups, ${selectors.size} full selectors, " +
            "${defaults.values.filter { it.enabled && it.key.type == SubsConfig.AppGroupType }.map { it.key.appId }.distinct().size} apps with dedicated enabled ad groups")
        assertTrue("Must exercise the complete engine instead of a ten-rule adapter", selectors.size > 500)
        // Use the exact type schema used by RawGroupProps.valid in the production GKD host.
        val runtimeTypes = li.songe.selector.initDefaultTypeInfo().globalType
        selectors.forEach { Selector.parse(it).checkType(runtimeTypes) }
        assertTrue(enabledGroups.flatMap { it.rules }.any { !it.preKeys.isNullOrEmpty() })
        val globalOpening = subscriptions.first { it.id == 86L }.globalGroups.first { it.key == 0 }
        assertTrue(globalOpening.rules.any { !it.anyMatches.isNullOrEmpty() })
        assertTrue(globalOpening.rules.any { !it.excludeMatches.isNullOrEmpty() })
        assertEquals(2, globalOpening.actionMaximum)
        assertEquals(9000L, globalOpening.matchTime)
    }
}
