package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level regression guard for D-pad-reachable settings beyond the viewport. */
class MainActivityScrollLayoutSourceTest {
    private val source by lazy { sourceFile().readText() }

    @Test fun eachOfTheTwoFixedTabPanelsIsContainedInAFillViewportScrollView() {
        assertContains("fun scrollablePanel(panel: LinearLayout) = ScrollView(this).apply")
        assertContains("isFillViewport = true")
        assertContains("descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS")
        assertContains("val controlScroll = scrollablePanel(controlPanel)")
        assertContains("val configurationScroll = scrollablePanel(configurationPanel)")
        assertContains("tabPanels += listOf(controlScroll, configurationScroll)")
        assertContains("addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))")
    }

    @Test fun scrollContainersRemainTheOnlyTwoOrdinaryTabPanels() {
        assertContains("tabPanels.forEach { panel ->\n            tabLayoutContainer.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))\n        }")
        assertFalse(source.contains("TabHost"))
        assertFalse(source.contains("TabWidget"))
    }

    @Test fun twoTabStripUsesStyledFocusableTextViewsRatherThanRawButtons() {
        assertContains("controlTab = tabView(LeanbackTabPolicy.tabs[0]) { showTab(0) }")
        assertContains("additionalTab = tabView(LeanbackTabPolicy.tabs[1]) { showTab(1) }")
        assertFalse(source.contains("controlTab = Button(this)"))
        assertFalse(source.contains("additionalTab = Button(this)"))
        assertContains("private fun tabView(label: String, onClick: () -> Unit) = TextView(this).apply")
        assertContains("isFocusable = true")
        assertContains("isClickable = true")
        assertContains("background = StateListDrawable().apply")
        assertContains("android.R.attr.state_selected, android.R.attr.state_focused")
        assertContains("android.R.attr.state_focused")
        assertContains("android.R.attr.state_selected")
    }

    @Test fun tabSelectionUpdatesStyledSelectedStateAndKeepsDpadContract() {
        assertContains("controlTab.isSelected = selected == 0")
        assertContains("additionalTab.isSelected = selected == 1")
        assertContains("controlTab.nextFocusLeftId = controlTab.id")
        assertContains("controlTab.nextFocusRightId = additionalTab.id")
        assertContains("additionalTab.nextFocusLeftId = controlTab.id")
        assertContains("additionalTab.nextFocusRightId = additionalTab.id")
        assertContains("controlTab.nextFocusDownId = captureButton.id")
        assertContains("additionalTab.nextFocusDownId = qualityRow.getChildAt(1).id")
    }

    private fun assertContains(expected: String) = assertTrue("Missing: $expected", source.contains(expected))

    private fun sourceFile(): File = sequenceOf(
        File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
    ).firstOrNull(File::isFile) ?: error("MainActivity.kt not found")
}
