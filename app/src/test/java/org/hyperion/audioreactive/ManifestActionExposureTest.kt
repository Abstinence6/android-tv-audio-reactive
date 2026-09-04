package org.hyperion.audioreactive

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestActionExposureTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val commandActions = setOf(
        "org.hyperion.audioreactive.action.TOGGLE",
        "org.hyperion.audioreactive.action.ON",
        "org.hyperion.audioreactive.action.OFF",
    )
    private val remoteActivities = mapOf(
        ".RemoteOnActivity" to "Увімкнути Audio Reactive",
        ".RemoteOffActivity" to "Вимкнути Audio Reactive",
        ".RemoteToggleActivity" to "Перемкнути Audio Reactive",
    )

    @Test fun mainActivityPreservesOnlySeparateCategoryFreeCommandFilters() {
        val mainActivity = activities().single { it.androidName() == ".MainActivity" }

        assertEquals("true", mainActivity.androidAttribute("exported"))
        val commandFilters = children(mainActivity, "intent-filter").filter { filter ->
            children(filter, "action").any { it.androidAttribute("name") in commandActions }
        }
        assertEquals(3, commandFilters.size)
        assertEquals(commandActions, commandFilters.map { filter ->
            val actions = children(filter, "action")
            assertEquals(1, actions.size)
            assertTrue(children(filter, "category").isEmpty())
            actions.single().androidAttribute("name")
        }.toSet())
    }

    @Test fun exactlyThreeVisibleRemoteComponentsHaveUniqueUkrainianLabelsAndNoLauncherCategories() {
        val activities = activities()
        val remote = activities.filter { it.androidName() in remoteActivities }
        assertEquals(3, remote.size)
        assertEquals(remoteActivities.keys, remote.map { it.androidName() }.toSet())
        assertEquals(remoteActivities.values.toSet(), remote.map { stringValue(it.androidAttribute("label")) }.toSet())
        remote.forEach { activity ->
            assertEquals("true", activity.androidAttribute("exported"))
            assertTrue(children(activity, "intent-filter").isEmpty())
        }
        assertEquals(3, activities.count { it.androidAttribute("label").startsWith("@string/remote_command_") })
    }

    @Test fun internalActivitiesAndServicesRemainNonExported() {
        val components = activities() + elements("service")
        setOf(".CaptureToggleActivity", ".AudioReactiveService", ".MqttControlService").forEach { name ->
            assertEquals("false", components.single { it.androidName() == name }.androidAttribute("exported"))
        }
    }

    private fun activities() = elements("activity")

    private fun elements(tag: String): List<org.w3c.dom.Element> {
        val nodes = document().getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }

    private fun document() = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(manifestFile())

    private fun stringValue(reference: String): String {
        val name = reference.removePrefix("@string/")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile())
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == name }
            .textContent
    }

    private fun manifestFile(): File = sourceFile("AndroidManifest.xml", "src/main", "app/src/main")
    private fun stringsFile(): File = sourceFile("strings.xml", "src/main/res/values", "app/src/main/res/values")

    private fun sourceFile(name: String, vararg roots: String): File = roots.asSequence()
        .map { File(it, name) }
        .firstOrNull(File::isFile) ?: error("$name not found")

    private fun org.w3c.dom.Element.androidName() = androidAttribute("name")
    private fun org.w3c.dom.Element.androidAttribute(name: String) = getAttributeNS(androidNs, name)

    private fun children(parent: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> =
        (0 until parent.childNodes.length).mapNotNull { index ->
            parent.childNodes.item(index).takeIf { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE } as? org.w3c.dom.Element
        }.filter { it.tagName == tag }
}
