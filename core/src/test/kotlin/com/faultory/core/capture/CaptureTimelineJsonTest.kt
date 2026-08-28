package com.faultory.core.capture

import com.faultory.core.config.FaultoryJson
import com.faultory.core.shop.systems.ChanceKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureTimelineJsonTest {
    @Test
    fun `a timeline round-trips through JSON`() {
        val timeline = CaptureTimeline(
            cues = listOf(
                CaptureCue(atSeconds = 1f, action = CaptureAction.Chance(ChanceKind.SABOTAGE, outcome = true)),
                CaptureCue(atSeconds = 2f, action = CaptureAction.Preset(ChromePreset.TECHNICAL)),
                CaptureCue(atSeconds = 3f, action = CaptureAction.Record(recording = true))
            )
        )

        val json = FaultoryJson.instance.encodeToString(CaptureTimeline.serializer(), timeline)
        val decoded = FaultoryJson.instance.decodeFromString(CaptureTimeline.serializer(), json)

        assertEquals(timeline, decoded)
    }

    @Test
    fun `an authored timeline JSON string decodes as expected`() {
        val json = """
            {"cues":[
              {"atSeconds":5.0,"action":{"type":"chance","kind":"SABOTAGE","outcome":true}},
              {"atSeconds":10.0,"action":{"type":"preset","preset":"CLEAN"}}
            ]}
        """.trimIndent()

        val timeline = FaultoryJson.instance.decodeFromString(CaptureTimeline.serializer(), json)

        assertEquals(2, timeline.cues.size)
        assertEquals(CaptureAction.Chance(ChanceKind.SABOTAGE, outcome = true), timeline.cues[0].action)
        assertEquals(CaptureAction.Preset(ChromePreset.CLEAN), timeline.cues[1].action)
    }
}
