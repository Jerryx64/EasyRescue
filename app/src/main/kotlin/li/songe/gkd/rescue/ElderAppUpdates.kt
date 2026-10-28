package li.songe.gkd.rescue

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import li.songe.gkd.util.client
import java.io.ByteArrayOutputStream
import java.net.URI

@Serializable
internal data class ElderRelease(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String = "",
)

/** Only checks release metadata; the browser downloads and Android confirms installation. */
internal object ElderAppUpdates {
    private const val MAX_LENGTH = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    private fun httpsUri(address: String): URI = URI(address).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawFragment == null) { "Invalid release URL" }
    }

    internal fun parse(raw: String, manifestUrl: String): ElderRelease {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_LENGTH) { "Release metadata too large" }
        val base = httpsUri(manifestUrl)
        val release = json.decodeFromString<ElderRelease>(raw)
        require(release.packageName == "com.easyrescue.app" && release.versionCode > 0 &&
            release.versionName.isNotBlank() && release.versionName.length <= 80 &&
            release.changelog.length <= 8000 && release.downloadUrl.isNotBlank()) { "Invalid release metadata" }
        return release.copy(downloadUrl = httpsUri(base.resolve(release.downloadUrl).toString()).toString())
    }

    suspend fun check(manifestUrl: String): ElderRelease = withTimeout(30_000L) {
        httpsUri(manifestUrl)
        client.prepareGet(manifestUrl).execute { response ->
            require(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val bytes = ByteArrayOutputStream()
            val input = response.bodyAsChannel()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.readAvailable(buffer, 0, minOf(buffer.size, MAX_LENGTH + 1 - bytes.size()))
                if (count < 0) break
                bytes.write(buffer, 0, count)
                require(bytes.size() <= MAX_LENGTH) { "Release metadata too large" }
            }
            parse(bytes.toString(Charsets.UTF_8.name()), manifestUrl)
        }
    }
}
