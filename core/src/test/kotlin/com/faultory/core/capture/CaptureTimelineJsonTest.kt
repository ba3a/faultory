package com.faultory.core.capture

import com.faultory.core.config.FaultoryJson
import com.faultory.core.shop.systems.ChanceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureTimelineJsonTest {
    @Test
    fun `a timeline round-trips through JSON`() {
        val timeline = CaptureTimeline(
            cues = listOf(
                CaptureCue(atSeconds = 1f, action = CaptureAction.Chance(ChanceKind.SABOTAGE, outcome = true)),
                CaptureCue(atSeconds = 2f, action = CaptureAction.Preset(ChromePreset.TECHNICAL)),
                CaptureCue(atSeconds = 3f, action = CaptureAction.Record(recording = true)),
                CaptureCue(atSeconds = 4f, action = CaptureAction.Camera(x = 640f, y = 420f, zoom = 2f, seconds = 3f))
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
              {"atSeconds":10.0,"action":{"type":"preset","preset":"CLEAN"}},
              {"atSeconds":12.0,"action":{"type":"camera","x":640.0,"y":420.0,"zoom":2,"seconds":3.0}},
              {"atSeconds":15.0,"action":{"type":"camera","x":800.0,"y":480.0,"zoom":0.5}}
            ]}
        """.trimIndent()

        val timeline = FaultoryJson.instance.decodeFromString(CaptureTimeline.serializer(), json)

        assertEquals(4, timeline.cues.size)
        assertEquals(CaptureAction.Chance(ChanceKind.SABOTAGE, outcome = true), timeline.cues[0].action)
        assertEquals(CaptureAction.Preset(ChromePreset.CLEAN), timeline.cues[1].action)
        assertEquals(CaptureAction.Camera(x = 640f, y = 420f, zoom = 2f, seconds = 3f), timeline.cues[2].action)
        assertEquals(CaptureAction.Camera(x = 800f, y = 480f, zoom = 0.5f, seconds = 0f), timeline.cues[3].action)
    }

    @Test
    fun `a camera cue off the zoom ladder is rejected when the timeline loads`() {
        val json = """{"cues":[{"atSeconds":1.0,"action":{"type":"camera","x":800.0,"y":480.0,"zoom":1.5}}]}"""

        assertFailsWith<IllegalArgumentException> {
            FaultoryJson.instance.decodeFromString(CaptureTimeline.serializer(), json)
        }
    }
}
