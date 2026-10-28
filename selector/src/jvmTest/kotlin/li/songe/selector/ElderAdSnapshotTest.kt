package li.songe.selector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Public snapshots and a sanitized device layout; exercises the engine, not live gestures. */
class ElderAdSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val weibo = """@[id="com.sina.weibo:id/btn_close"] - FrameLayout > [id="com.sina.weibo:id/ad_tag"]"""
    private val douyin = """[id="android:id/content"] >2 RelativeLayout[childCount<=5] > @ImageView[clickable=true][id!=null] - TextView[text="广告"][id!=null]"""
    private val douban by lazy {
        // Query the shipped selector so a rule edit also changes this regression.
        File("../app/src/main/assets/elder_rules/easyrescue/easyrescue.json5")
            .readLines().single { it.contains("com.douban.frodo:id/skip") }
            .substringAfter("matches: '").substringBeforeLast("'")
    }
    private val douyinMiniSdk by lazy {
        File("../app/src/main/assets/elder_rules/easyrescue/easyrescue.json5")
            .readLines().filter { it.contains("matches: '") &&
                (it.contains("tt_reward_full_count_down_after_close") || it.contains("ad_flag_source_layout")) }
            .map { it.substringAfter("matches: '").substringBeforeLast("'") }
            .also { assertEquals(2, it.size) }
    }
    private val douyinRewardComplete by lazy {
        File("../app/src/main/assets/elder_rules/easyrescue/easyrescue.json5")
            .readLines().single { it.contains("matches: '") && it.contains("领取成功，关闭，按钮") }
            .substringAfter("matches: '").substringBeforeLast("'")
    }

    @Test
    fun fivePlaySplashRequiresItsVisibleClickableSkipContainerAndSkipLabel() {
        val selector = File("../app/src/main/assets/elder_rules/easyrescue/easyrescue.json5")
            .readLines().single { it.contains("matches: '") && it.contains("cj_splash_skip_ll") }
            .substringAfter("matches: '").substringBeforeLast("'")
        val name = "fiveplay-730-splash.json"
        assertEquals(listOf(1), query(fixture(name), selector))
        for ((id, attribute, value) in listOf(
            Triple(1, "clickable", JsonPrimitive(false)),
            Triple(1, "visibleToUser", JsonPrimitive(false)),
            Triple(2, "visibleToUser", JsonPrimitive(false)),
            Triple(2, "text", JsonPrimitive("立即进入")),
            Triple(2, "id", JsonPrimitive("com.fiveplay:id/cj_splash_cta_bt")))) {
            val root = fixture(name) { nodes -> nodes.map {
                if (it.id == id) it.withAttr(attribute, value) else it
            } }
            assertTrue(query(root, selector).isEmpty(), "$id $attribute")
        }
    }

    @Test
    fun meetingSplashRequiresAdAncestorsAndClickableVisibleSkipText() {
        val selector = File("../app/src/main/assets/elder_rules/easyrescue/easyrescue.json5")
            .readLines().single { it.contains("matches: '") && it.contains("skipLayout") }
            .substringAfter("matches: '").substringBeforeLast("'")
        val name = "meeting-34710-splash.json"
        assertEquals(listOf(3), query(fixture(name), selector))
        for ((id, attribute, value) in listOf(
            Triple(0, "id", JsonPrimitive("com.tencent.wemeet.app:id/home")),
            Triple(1, "id", JsonPrimitive("com.tencent.wemeet.app:id/meetingToolbar")),
            Triple(3, "clickable", JsonPrimitive(false)),
            Triple(3, "visibleToUser", JsonPrimitive(false)),
            Triple(3, "text", JsonPrimitive("加入会议")))) {
            val root = fixture(name) { nodes -> nodes.map {
                if (it.id == id) it.withAttr(attribute, value) else it
            } }
            assertTrue(query(root, selector).isEmpty(), "$id $attribute")
        }
    }

    @Test
    fun douyinCompletedRewardAdRequiresItsVisibleContainerAndExactCompletionState() {
        // Sanitized 40.8.0 device tree: the folded header has zero height, so return the ad container.
        val name = "douyin-400801-reward-complete.json"
        assertEquals(listOf(151), query(fixture(name), douyinRewardComplete))
        for (description in listOf("08秒后可领奖励，关闭，按钮", "关闭", "看广告奖励翻倍", "立即支付")) {
            val root = fixture(name) { nodes -> nodes.map {
                if (it.id == 203) it.withAttr("desc", JsonPrimitive(description)) else it
            } }
            assertTrue(query(root, douyinRewardComplete).isEmpty(), description)
        }
        for ((attribute, value) in listOf(
            "id" to JsonPrimitive("com.ss.android.ugc.aweme:id/axf"),
            "visibleToUser" to JsonPrimitive(false), "height" to JsonPrimitive(640))) {
            val root = fixture(name) { nodes -> nodes.map {
                if (it.id == 151) it.withAttr(attribute, value) else it
            } }
            assertTrue(query(root, douyinRewardComplete).isEmpty(), attribute)
        }
        val outside = fixture(name) { nodes -> nodes.map { if (it.id == 203) it.copy(pid = 0) else it } }
        assertTrue(query(outside, douyinRewardComplete).isEmpty())
        val closed = fixture(name) { nodes -> nodes.filter { it.id != 203 } }
        assertTrue(query(closed, douyinRewardComplete).isEmpty())
    }

    @Test
    fun douyinMiniSdkSelectorsRequireVisibleAdSpecificControls() {
        // Synthetic SDK controls; this checks selector behavior, not a served ad or reward entitlement.
        fun layout(ids: List<String>, visible: Boolean = true, width: Int = 64,
                   name: String = "android.widget.ImageView"): Node {
            val root = Node(0, -1, mapOf("name" to JsonPrimitive("android.widget.FrameLayout")))
            ids.forEachIndexed { index, id ->
                val node = Node(index + 1, 0, mapOf(
                    "id" to JsonPrimitive("com.ss.android.ugc.aweme:id/$id"), "vid" to JsonPrimitive(id),
                    "name" to JsonPrimitive(name), "index" to JsonPrimitive(index),
                    "visibleToUser" to JsonPrimitive(visible), "childCount" to JsonPrimitive(0),
                    "width" to JsonPrimitive(width), "height" to JsonPrimitive(64),
                    "top" to JsonPrimitive(100), "left" to JsonPrimitive(100)))
                node.parent = root
                root.children.add(node)
            }
            return root
        }
        val rewardClose = listOf("tt_reward_full_count_down_after_close")
        val markedClose = listOf("tt_ad_flag_source_layout", "tt_iv_close")
        assertEquals(listOf(1), query(layout(rewardClose), douyinMiniSdk[0]))
        assertEquals(listOf(2), query(layout(markedClose), douyinMiniSdk[1]))
        assertTrue(query(layout(listOf("tt_iv_close")), douyinMiniSdk[1]).isEmpty())
        assertTrue(query(layout(markedClose.reversed()), douyinMiniSdk[1]).isEmpty())
        listOf(rewardClose, markedClose).zip(douyinMiniSdk).forEach { (ids, selector) ->
            assertTrue(query(layout(ids, visible = false), selector).isEmpty())
            assertTrue(query(layout(ids, width = 500), selector).isEmpty())
            assertTrue(query(layout(ids, name = "android.widget.CheckBox"), selector).isEmpty())
            // Observed miniapp exit/back controls and normal player/payment controls must not match.
            assertTrue(query(layout(listOf("axf", "av4", "operation-like-icon", "pay")), selector).isEmpty())
        }
    }

    @Test
    fun doubanCustomDrawnSkipRequiresItsVisibleAdMarkerAndFullScreenBounds() {
        // Same minimal layout observed on 7.135.1 and 7.136.0; no page content or account data.
        fun layout(skipAttrs: Map<String, JsonPrimitive> = emptyMap(), marker: Boolean = true): Node {
            val root = Node(0, -1, mapOf("name" to JsonPrimitive("android.view.ViewGroup"),
                "id" to JsonPrimitive("com.douban.frodo:id/ad_parent"),
                "width" to JsonPrimitive(1440), "height" to JsonPrimitive(3200)))
            val skip = Node(1, 0, mapOf("name" to JsonPrimitive("android.view.View"),
                "id" to JsonPrimitive("com.douban.frodo:id/skip"),
                "visibleToUser" to JsonPrimitive(true), "clickable" to JsonPrimitive(false),
                "childCount" to JsonPrimitive(0), "left" to JsonPrimitive(0), "top" to JsonPrimitive(0),
                "width" to JsonPrimitive(1440), "height" to JsonPrimitive(3200)) + skipAttrs)
            skip.parent = root
            root.children.add(skip)
            if (marker) {
                val ad = Node(2, 0, mapOf("name" to JsonPrimitive("android.widget.TextView"),
                    "id" to JsonPrimitive("com.douban.frodo:id/ad_mark"),
                    "text" to JsonPrimitive("广告"), "visibleToUser" to JsonPrimitive(true)))
                ad.parent = root
                root.children.add(ad)
            }
            return root
        }
        assertEquals(listOf(1), query(layout(), douban))
        assertTrue(query(layout(marker = false), douban).isEmpty())
        assertTrue(query(layout(mapOf("width" to JsonPrimitive(240))), douban).isEmpty())
        assertTrue(query(layout(mapOf("visibleToUser" to JsonPrimitive(false))), douban).isEmpty())
    }

    @Test
    fun weiboOriginalSelectorFindsOnlyItsAdCloseControl() {
        assertEquals(listOf(6), query(fixture(12750090), weibo))
    }

    @Test
    fun douyinOriginalSelectorFindsOnlyItsAdCloseControl() {
        assertEquals(listOf(11), query(fixture(12769137), douyin))
    }

    @Test
    fun weiboCloseControlWithoutItsAdMarkerDoesNotMatch() {
        val root = fixture(12750090) { nodes ->
            nodes.map { node -> if (node.id == 8) node.withAttr("id", JsonNull) else node }
        }
        assertTrue(query(root, weibo).isEmpty())
    }

    @Test
    fun douyinCloseControlWithoutItsAdMarkerDoesNotMatch() {
        val root = fixture(12769137) { nodes ->
            nodes.map { node -> if (node.id == 10) node.withAttr("text", JsonNull) else node }
        }
        assertTrue(query(root, douyin).isEmpty())
    }

    @Test
    fun weiboDifferentSiblingOrderDoesNotMatch() {
        val root = fixture(12750090) { nodes ->
            nodes.map { node ->
                when (node.id) {
                    5 -> node.withAttr("index", JsonPrimitive(2))
                    6 -> node.withAttr("index", JsonPrimitive(1))
                    else -> node
                }
            }
        }
        assertTrue(query(root, weibo).isEmpty())
    }

    @Test
    fun douyinAdMarkerAfterAllCloseControlsDoesNotMatch() {
        val root = fixture(12769137) { nodes ->
            nodes.map { node ->
                when (node.id) {
                    10 -> node.withAttr("index", JsonPrimitive(4))
                    11 -> node.withAttr("index", JsonPrimitive(1))
                    12 -> node.withAttr("index", JsonPrimitive(2))
                    13 -> node.withAttr("index", JsonPrimitive(3))
                    else -> node
                }
            }
        }
        assertTrue(query(root, douyin).isEmpty())
    }

    private fun query(root: Node, source: String) =
        transform.querySelectorAll(root, Selector.parse(source)).map { it.id }.toList()

    private fun Node.withAttr(name: String, value: JsonPrimitive) =
        copy(attr = attr + (name to value))

    private fun fixture(id: Int, modify: (List<Node>) -> List<Node> = { it }): Node =
        fixture("$id.json", modify)

    private fun fixture(name: String, modify: (List<Node>) -> List<Node> = { it }): Node {
        val raw = requireNotNull(javaClass.getResourceAsStream("/elder-rules/$name")) {
            "Missing bundled snapshot $name"
        }.bufferedReader().use { it.readText() }
        val nodes = modify(json.decodeFromString<Snapshot>(raw).nodes)
        val byId = nodes.associateBy { it.id }
        nodes.forEach { node ->
            node.parent = byId[node.pid]
            node.parent?.children?.add(node)
        }
        nodes.forEach { node -> node.children.sortBy { it.attr["index"]?.intOrNull ?: 0 } }
        return nodes.single { it.pid == -1 }
    }
}
