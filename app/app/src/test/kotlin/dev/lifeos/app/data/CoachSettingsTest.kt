package dev.lifeos.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachSettingsTest {
    @Test
    fun `the bridge's settings are read, also with the older frequency names`() {
        val settings = BridgeJson.decodeFromString(
            CoachSettings.serializer(),
            """{"frequency": "3", "quiet_start": "23:00", "quiet_end": "07:30", "questions": false}""",
        )
        assertEquals(CoachSettings("3", "23:00", "07:30", false), settings)
        assertEquals(CoachSettingRules.ONCE, CoachSettingRules.frequencyOf("low"))
        assertEquals(CoachSettingRules.UP_TO_THREE, CoachSettingRules.frequencyOf("normal"))
        assertEquals(CoachSettingRules.OFF, CoachSettingRules.frequencyOf("off"))
        assertEquals(CoachSettingRules.ONCE, CoachSettingRules.frequencyOf("unknown"))
    }

    @Test
    fun `a change sends only the fields that changed`() {
        val before = CoachSettings()
        assertEquals(JsonObject(mapOf("frequency" to JsonPrimitive("off"))), CoachSettingRules.changes(before, before.copy(frequency = "off")))
        assertEquals(
            JsonObject(mapOf("quiet_start" to JsonPrimitive("21:30"), "questions" to JsonPrimitive(false))),
            CoachSettingRules.changes(before, before.copy(quietStart = "21:30", questions = false)),
        )
        assertEquals(JsonObject(emptyMap()), CoachSettingRules.changes(before, before))
    }

    @Test
    fun `equal start and end mean no quiet hours`() {
        assertTrue(CoachSettingRules.hasQuietHours(CoachSettings()))
        assertFalse(CoachSettingRules.hasQuietHours(CoachSettings(quietStart = "08:00", quietEnd = "08:00")))
    }

    @Test
    fun `a skip may pause the questions`() {
        assertNull(BridgeJson.decodeFromString(CoachSkip.serializer(), """{"skipped": true, "questions_paused_until": null}""").pausedUntil)
        assertEquals(
            "2026-01-08T08:00:00+00:00",
            BridgeJson.decodeFromString(CoachSkip.serializer(), """{"skipped": true, "questions_paused_until": "2026-01-08T08:00:00+00:00"}""").pausedUntil,
        )
    }
}
