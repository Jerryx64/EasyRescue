package li.songe.gkd.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ElderAppUpdatesTest {
    private val endpoint = "https://downloads.example.org/easyrescue/app-update.json"
    private val raw = """{"packageName":"com.easyrescue.app","versionCode":9,"versionName":"0.6.3","downloadUrl":"./easyrescue-0.6.3.apk","changelog":"更新广告规则"}"""

    @Test fun releaseMustIdentifyOurAppAndResolveAnHttpsDownload() {
        val release = ElderAppUpdates.parse(raw, endpoint)
        assertEquals(9, release.versionCode)
        assertEquals("https://downloads.example.org/easyrescue/easyrescue-0.6.3.apk", release.downloadUrl)
        assertEquals("更新广告规则", release.changelog)
    }

    @Test fun otherPackagesInvalidVersionsAndUnsafeDownloadsAreRejected() {
        for (invalid in listOf(
            raw.replace("com.easyrescue.app", "li.songe.gkd"),
            raw.replace("\"versionCode\":9", "\"versionCode\":0"),
            raw.replace("0.6.3\",\"downloadUrl", "\",\"downloadUrl"),
            raw.replace("./easyrescue-0.6.3.apk", "http://downloads.example.org/new.apk"),
            raw.replace("./easyrescue-0.6.3.apk", "https://user:secret@example.org/new.apk"),
            raw.replace("./easyrescue-0.6.3.apk", "intent://new.apk"),
            raw.replace("./easyrescue-0.6.3.apk", ""),
            raw + " ".repeat(65_536))) {
            assertThrows(Exception::class.java) { ElderAppUpdates.parse(invalid, endpoint) }
        }
        assertThrows(Exception::class.java) { ElderAppUpdates.parse(raw, "http://example.org/app-update.json") }
    }
}
