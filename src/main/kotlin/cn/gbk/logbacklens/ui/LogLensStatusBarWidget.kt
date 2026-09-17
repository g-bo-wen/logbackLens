package cn.gbk.logbacklens.ui

import cn.gbk.logbacklens.configuration.LOGBACK_CONFIGURATION_TOPIC
import cn.gbk.logbacklens.configuration.LogbackConfigurationListener
import cn.gbk.logbacklens.configuration.LogbackConfigurationService
import cn.gbk.logbacklens.settings.LOG_LENS_SETTINGS_TOPIC
import cn.gbk.logbacklens.settings.LogLensSettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

class LogLensStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Log Lens"
    override fun isAvailable(project: Project): Boolean = !project.isDefault
    override fun createWidget(project: Project): StatusBarWidget = LogLensStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun isEnabledByDefault(): Boolean = true

    companion object {
        const val WIDGET_ID = "LogLensStatus"
    }
}

private class LogLensStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = LogLensStatusBarWidgetFactory.WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = LogLensUiText.statusText(LogbackConfigurationService.getInstance(project).currentState())
    override fun getTooltipText(): String = LogLensUiText.tooltip(LogbackConfigurationService.getInstance(project).currentState())
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event -> showPopup(event.component) }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        LogbackConfigurationService.getInstance(project).start()
        project.messageBus.connect(this).subscribe(
            LOGBACK_CONFIGURATION_TOPIC,
            LogbackConfigurationListener { update() },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LOG_LENS_SETTINGS_TOPIC,
            LogLensSettingsListener { update() },
        )
    }

    private fun update() {
        statusBar?.updateWidget(ID())
    }

    private fun showPopup(owner: Component) {
        val panel = LogLensPopupPanel(project)
        val popupDisposable: Disposable = Disposer.newDisposable("Log Lens popup subscriptions")
        project.messageBus.connect(popupDisposable).subscribe(
            LOGBACK_CONFIGURATION_TOPIC,
            LogbackConfigurationListener { panel.refresh() },
        )
        ApplicationManager.getApplication().messageBus.connect(popupDisposable).subscribe(
            LOG_LENS_SETTINGS_TOPIC,
            LogLensSettingsListener { panel.refresh() },
        )
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel.component, panel.pathField.textField)
            .setTitle("Log Lens")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setProject(project)
            .setMayBeParent(true)
            .setCancelOnWindowDeactivation(false)
            .setCancelOnClickOutside(true)
            .createPopup()
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                panel.closeColorPopups()
                Disposer.dispose(popupDisposable)
            }
        })
        popup.showUnderneathOf(owner)
    }

    override fun dispose() {
        statusBar = null
    }
}
