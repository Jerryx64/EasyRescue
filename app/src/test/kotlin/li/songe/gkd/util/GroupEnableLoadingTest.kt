package li.songe.gkd.util

import kotlinx.serialization.json.JsonPrimitive
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEnableLoadingTest {
    private fun group(
        selector: String,
        enable: Boolean = true,
        position: String? = null,
    ): RawSubscription.RawAppGroup {
        val positionProperty = position?.let { ", position: $it" }.orEmpty()
        return RawSubscription.parse(
            """{
                id: 1, name: "loading test", version: 1,
                apps: [{id: "com.example.test", groups: [{
                    key: 1, name: "广告", enable: $enable,
                    rules: [{matches: [${JsonPrimitive(selector)}]$positionProperty}]
                }]}]
            }"""
        ).apps.single().groups.single()
    }

    private fun lazyInitialized(group: RawSubscription.RawAppGroup, property: String): Boolean {
        val field = group.javaClass.getDeclaredField("${property}\$delegate")
        field.isAccessible = true
        return (field.get(group) as Lazy<*>).isInitialized()
    }

    @Test
    fun disabledInvalidSelectorsNeverInitializeValidationOrSelectorCaches() {
        val disabledByDefault = group("[", enable = false)
        val disabledByOverride = group("[")
        val configOverride = SubsConfig(id = 1L, type = SubsConfig.AppGroupType,
            subsId = 1L, appId = "com.example.test", groupKey = 1, enable = false)

        for ((candidate, config) in listOf(disabledByDefault to null, disabledByOverride to configOverride)) {
            assertFalse(getGroupEnable(candidate, config))
            // A false result alone would also pass when the invalid selector was parsed first.
            // Inspect the production lazy fields to prove that compilation was never attempted.
            for (property in listOf("valid", "errorDesc", "cacheMap")) {
                assertFalse(property, lazyInitialized(candidate, property))
            }
        }
    }

    @Test
    fun enabledInvalidPositionsStillFailProductionValidation() {
        val candidate = group("[text=\"跳过\"]", position = "{}")

        assertFalse(getGroupEnable(candidate, null))
        assertTrue(lazyInitialized(candidate, "valid"))
        assertTrue(candidate.errorDesc.orEmpty().startsWith("非法位置:"))
        assertNotNull(candidate.cacheMap["[text=\"跳过\"]"])
    }

    @Test
    fun enabledValidGroupsStillCompileAndCacheTheirSelectors() {
        val selector = "[text=\"跳过\"]"
        val candidate = group(selector)

        assertFalse(lazyInitialized(candidate, "valid"))
        assertTrue(getGroupEnable(candidate, null))
        assertTrue(lazyInitialized(candidate, "valid"))
        assertNotNull(candidate.cacheMap[selector])
    }
}
