package org.hyperion.audioreactive

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalizationResourcesTest {
    @Test fun englishUkrainianAndPolishCataloguesHaveMatchingKeys() {
        val english = strings("values")
        val ukrainian = strings("values-uk")
        val polish = strings("values-pl")

        assertEquals(english.entries.map { it.name to it.kind }, ukrainian.entries.map { it.name to it.kind })
        assertEquals(english.entries.map { it.name to it.kind }, polish.entries.map { it.name to it.kind })
        english.entries.zip(ukrainian.entries).forEach { (source, localized) ->
            assertEquals("Ukrainian item count for ${source.name}", source.values.size, localized.values.size)
            assertEquals("Ukrainian placeholders for ${source.name}", placeholders(source.values), placeholders(localized.values))
        }
        english.entries.zip(polish.entries).forEach { (source, localized) ->
            assertEquals("Polish item count for ${source.name}", source.values.size, localized.values.size)
            assertEquals("Polish placeholders for ${source.name}", placeholders(source.values), placeholders(localized.values))
        }
        assertEquals("Turn on Audio Reactive", english.byName("remote_command_on").values.single())
        assertEquals("Увімкнути Audio Reactive", ukrainian.byName("remote_command_on").values.single())
        assertEquals("Włącz Audio Reactive", polish.byName("remote_command_on").values.single())
        assertNotEquals(english.byName("capture_mode").values, ukrainian.byName("capture_mode").values)
        assertNotEquals(english.byName("capture_mode").values, polish.byName("capture_mode").values)
    }

    @Test fun resourceArrayLengthsMatchTheStableEnumCatalogues() {
        val english = strings("values")
        val expected = mapOf(
            "render_mode_labels" to RenderMode.entries.size,
            "output_mode_labels" to OutputMode.entries.size,
            "video_quality_labels" to VideoQuality.entries.size,
            "video_effect_labels" to VideoEffect.entries.size,
            "video_audio_effect_labels" to VideoAudioEffectCatalogue.visible.size,
            "animation_effect_labels" to AnimationEffect.entries.size,
            "animation_colour_labels" to AnimationColour.entries.size,
            "local_visual_pattern_labels" to LocalVisualPattern.entries.size,
            "wled_diagnostic_labels" to WledDiagnosticPattern.entries.size,
            "screen_edge_labels" to ScreenEdge.entries.size,
            "perimeter_direction_labels" to PerimeterDirection.entries.size,
            "capture_status_texts" to CaptureStatus.entries.size,
            "audio_input_labels" to AudioInput.entries.size,
            "effect_labels" to Effect.entries.size,
        )
        expected.forEach { (name, count) -> assertEquals("$name ordinal mapping", count, english.byName(name).values.size) }
    }

    private data class Resource(val kind: String, val name: String, val values: List<String>)
    private data class ResourceFile(val entries: List<Resource>) {
        fun byName(name: String) = entries.single { it.name == name }
    }

    private fun strings(directory: String): ResourceFile {
        val file = sequenceOf(
            File("src/main/res/$directory/strings.xml"),
            File("app/src/main/res/$directory/strings.xml"),
        ).firstOrNull(File::isFile) ?: error("$directory/strings.xml not found")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.documentElement
        return ResourceFile((0 until root.childNodes.length).mapNotNull { index ->
            val element = root.childNodes.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            when (element.tagName) {
                "string" -> Resource("string", element.getAttribute("name"), listOf(element.textContent))
                "string-array" -> Resource("string-array", element.getAttribute("name"),
                    (0 until element.getElementsByTagName("item").length).map { item -> element.getElementsByTagName("item").item(item).textContent })
                else -> error("Unsupported resource ${element.tagName}")
            }
        })
    }

    private fun placeholders(values: List<String>): List<List<String>> = values.map { value ->
        Regex("%(?:[1-9]\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]").findAll(value).map { it.value }.toList()
    }
}
