package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level regression guard for D-pad-reachable settings beyond the viewport. */
class MainActivityScrollLayoutSourceTest {
    private val source by lazy { sourceFile().readText() }

    @Test fun singleDpadScreenIsContainedInAFillViewportScrollView() {
        assertContains("val mainPanel = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }")
        assertContains("val mainScroll = ScrollView(this).apply")
        assertContains("isFillViewport = true")
        assertContains("descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS")
        assertContains("addView(mainPanel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))")
        assertContains("root.addView(mainScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))")
    }

    @Test fun allControlGroupsShareTheSingleScrollablePanelWithoutTabs() {
        assertContains("buildControlTab(mainPanel)")
        assertContains("buildModesTab(mainPanel)")
        assertContains("buildOutputsTab(mainPanel)")
        assertContains("buildAdditionalTools(mainPanel)")
        assertFalse(source.contains("TabHost"))
        assertFalse(source.contains("TabWidget"))
        assertFalse(source.contains("tabView("))
        assertFalse(source.contains("showTab("))
        assertContains("captureButton.requestFocus()")
    }

    private fun assertContains(expected: String) = assertTrue("Missing: $expected", source.contains(expected))

    private fun sourceFile(): File = sequenceOf(
        File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
    ).firstOrNull(File::isFile) ?: error("MainActivity.kt not found")
}
