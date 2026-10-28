package li.songe.gkd.rescue

import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig

/** Selects defaults only. Every selector, step, cooldown and action remains subscription data. */
internal object ElderRuleDefaults {
    data class Key(val source: Long, val type: Int, val appId: String, val group: Int)
    data class Default(val key: Key, val enabled: Boolean, val exclude: String = "")

    private val adType = Regex("^(开屏广告|全屏广告|局部广告|分段广告)(?:$|[-—－：:])")
    private val legacyAliases = mapOf(
        Triple("cisdom.com.bigteaapp", 1, "广告弹窗") to "全屏广告",
        Triple("client.android.yixiaotong", 1, "弹窗广告") to "全屏广告",
        Triple("client.android.yixiaotong", 2, "卡片式广告") to "局部广告",
        Triple("client.android.yixiaotong", 3, "底部卡片广告") to "局部广告"
    )
    private val unrelated = Regex(
        "权限|通知|定位|访问位置|更新|升级|登录|登陆|注册|授权|协议|服务声明|评分|好评|评价|签到|青少年|未成年(?:人)?模式"
            + "|休息|无声进入|领奖|领取|领券|提现|打招呼|朋友推荐|好友推荐|兴趣推荐|博主推荐|关注推荐"
            + "|用户推荐|推荐用户|内容推荐|推荐内容|商品推荐|商城推荐|直播推荐|推荐列表|推荐关注|个性化|流量提醒|流量提示"
            + "|NSFW|内容提示|内容警告|云服务|添加.{0,8}(?:桌面|小组件)|小组件添加|桌面添加|创建快捷方式"
            + "|密码过期|ROOT提示|系统推送|备份|相册同步|关联抖音|默认进入|服务协议|隐私声明"
            + "|好友热播|好友爱看|空友爱看|绑定账号|账号绑定|公告|添加.{0,8}首页|开启音乐DJ|关注提示"
            + "|用户引导|新手引导|内测体验|功能体验|体验邀请|话题分享|自动观看广告|看广告.{0,8}(?:VIP|会员|奖励)|福利中心"
            + "|有概率误触|谨慎.{0,6}开启|只开启一个|配合.{0,8}(?:组|规则)",
        RegexOption.IGNORE_CASE
    )

    internal fun category(name: String): String? = adType.find(name)?.groupValues?.get(1)

    internal fun category(source: Long, appId: String, group: RawSubscription.RawAppGroup): String? =
        category(group.name) ?: if (source == 0L) legacyAliases[Triple(appId, group.key, group.name)] else null

    internal fun isAdvertisement(name: String, description: String?): Boolean =
        category(name) != null && semanticsSafe(name, description)

    private fun semanticsSafe(name: String, description: String?): Boolean = !name.contains("推荐")
            && !unrelated.containsMatchIn(name + "\n" + description.orEmpty())

    fun select(sources: List<RawSubscription>): List<Default> {
        val defaults = mutableListOf<Default>()
        val owner = mutableMapOf<Pair<String, String>, Long>()
        val knownApps = mutableSetOf<String>()
        // Caller order: local adaptations, ganlinte, aoguai supplements, legacy unique apps.
        sources.forEach { source ->
            source.apps.forEach { application ->
                application.groups.forEach { group ->
                    val kind = category(source.id, application.id, group)
                    val categoryKey = application.id to kind.orEmpty()
                    val distinctLegacyApp = source.id != 0L || application.id !in knownApps
                    val allowed = distinctLegacyApp && kind != null && semanticsSafe(group.name, group.desc)
                        && (owner[categoryKey] == null || owner[categoryKey] == source.id)
                    if (allowed) owner[categoryKey] = source.id
                    defaults += Default(
                        Key(source.id, SubsConfig.AppGroupType, application.id, group.key), allowed
                    )
                }
            }
            knownApps += source.apps.map { it.id }
        }
        sources.forEach { source ->
            source.globalGroups.forEach { group ->
                // A single maintained global source avoids two subscriptions racing the same SDK.
                val allowed = source.id == 86L && group.key in setOf(0, 1)
                    && isAdvertisement(group.name, group.desc)
                val kind = category(group.name)
                val exclusions = owner.keys.filter { it.second == kind }.map { it.first }.sorted()
                defaults += Default(
                    Key(source.id, SubsConfig.GlobalGroupType, "", group.key), allowed,
                    if (allowed) exclusions.joinToString("\n") else ""
                )
            }
        }
        return defaults
    }
}
