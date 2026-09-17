package cn.gbk.logbacklens.ui

import cn.gbk.logbacklens.settings.LogLensApplicationSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LogLensPopupPanelTest : BasePlatformTestCase() {
    fun testColorPickersIdentifyTheSettingTheyEdit() {
        val panel = LogLensPopupPanel(project)

        assertEquals("Message expression color", panel.messageColor.pickerTitle)
        assertEquals("Route color", panel.routeColor.pickerTitle)
    }

    fun testLensTogglesApplyImmediatelyAndPreviewUpdatesWithoutApplyButton() {
        val settings = LogLensApplicationSettings.getInstance()
        val original = settings.snapshot()
        try {
            settings.update(original.copy(messageLensEnabled = true, routeLensEnabled = true))
            val panel = LogLensPopupPanel(project)
            assertTrue(panel.messageToggle.isSelected)
            assertTrue(panel.routeToggle.isSelected)

            panel.messageToggle.doClick()
            assertFalse(settings.snapshot().messageLensEnabled)
            assertTrue(panel.previewLabel.text.contains("order {}"))

            panel.routeToggle.doClick()
            assertFalse(settings.snapshot().routeLensEnabled)
            assertFalse(panel.previewLabel.text.contains("sample/app.log"))
        } finally {
            settings.update(original)
        }
    }
}
