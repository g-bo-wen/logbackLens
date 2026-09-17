package cn.gbk.logbacklens.ui

import cn.gbk.logbacklens.configuration.BindingValidation
import cn.gbk.logbacklens.configuration.LogbackCandidate
import cn.gbk.logbacklens.configuration.LogbackConfigurationService
import cn.gbk.logbacklens.configuration.LogbackConfigurationState
import cn.gbk.logbacklens.settings.LogLensApplicationSettings
import cn.gbk.logbacklens.settings.LogLensApplicationState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ColorPanel
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.colorpicker.ColorPickerBuilder
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

internal class LogLensPopupPanel(private val project: Project) {
    private val configuration = LogbackConfigurationService.getInstance(project)
    private val applicationSettings = LogLensApplicationSettings.getInstance()
    private val candidateModel = DefaultComboBoxModel<LogbackCandidate>()

    internal val statusLabel = JBLabel()
    internal val errorLabel = JBLabel().apply { foreground = Color(0xC7, 0x54, 0x50) }
    internal val pathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("xml"))
    }
    internal val messageToggle = JBCheckBox("Message Lens")
    internal val routeToggle = JBCheckBox("Route Lens")
    internal val messageColor = PopupColorPanel(project, "Message expression color") { color ->
        updateSettings(applicationSettings.snapshot().copy(messageExpressionColor = color.toHex()))
    }
    internal val routeColor = PopupColorPanel(project, "Route color") { color ->
        updateSettings(applicationSettings.snapshot().copy(routeColor = color.toHex()))
    }
    internal val previewLabel = JBLabel()
    private val candidates = JComboBox(candidateModel)

    val component = JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)
        preferredSize = Dimension(470, 300)
        add(statusLabel, BorderLayout.NORTH)
        add(JBScrollPane(buildBody()).apply { border = null }, BorderLayout.CENTER)
        add(previewLabel, BorderLayout.SOUTH)
    }

    init {
        messageToggle.addActionListener {
            updateSettings(applicationSettings.snapshot().copy(messageLensEnabled = messageToggle.isSelected))
        }
        routeToggle.addActionListener {
            updateSettings(applicationSettings.snapshot().copy(routeLensEnabled = routeToggle.isSelected))
        }
        refresh(configuration.currentState(), applicationSettings.snapshot())
        loadCandidates()
    }

    fun refresh(
        configurationState: LogbackConfigurationState = configuration.currentState(),
        settings: LogLensApplicationState = applicationSettings.snapshot(),
    ) {
        statusLabel.text = buildString {
            append(LogLensUiText.tooltip(configurationState))
            configurationState.binding?.let { binding -> append(" Bound: ").append(binding.path) }
        }
        messageToggle.isSelected = settings.messageLensEnabled
        routeToggle.isSelected = settings.routeLensEnabled
        messageColor.selectedColor = Color.decode(settings.messageExpressionColor)
        routeColor.selectedColor = Color.decode(settings.routeColor)
        previewLabel.text = LogLensUiText.previewHtml(settings)
    }

    private fun buildBody(): JPanel = JBPanel<JBPanel<*>>().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(JBLabel("Detected project configurations"))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(candidates)
            add(JButton("Use selected").apply {
                addActionListener {
                    (candidates.selectedItem as? LogbackCandidate)?.let { candidate ->
                        bind(candidate.bindingInput(project.basePath?.let(Path::of)))
                    }
                }
            })
            add(JButton("Rescan").apply { addActionListener { loadCandidates() } })
        })
        add(JBLabel("Logback file (project-relative or absolute)"))
        add(pathField)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("Bind / Change").apply { addActionListener { bind(pathField.text) } })
            add(JButton("Clear").apply {
                addActionListener {
                    configuration.clear()
                    errorLabel.text = ""
                    refresh()
                }
            })
            add(errorLabel)
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(messageToggle)
            add(JBLabel("Expression color"))
            add(messageColor)
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(routeToggle)
            add(JBLabel("Route color"))
            add(routeColor)
        })
    }

    private fun bind(input: String) {
        errorLabel.text = "Validating…"
        configuration.bind(input) { result ->
            when (result) {
                is BindingValidation.Valid -> {
                    pathField.text = result.binding.path
                    errorLabel.text = ""
                }
                is BindingValidation.Invalid -> errorLabel.text = result.message
            }
            refresh()
        }
    }

    private fun loadCandidates() {
        configuration.discoverCandidates { found ->
            candidateModel.removeAllElements()
            found.forEach(candidateModel::addElement)
            if (found.isEmpty()) errorLabel.text = "No standard Logback file found in project content roots."
        }
    }

    private fun updateSettings(state: LogLensApplicationState) {
        applicationSettings.update(state)
        refresh(configuration.currentState(), applicationSettings.snapshot())
    }

    fun closeColorPopups() {
        messageColor.closePopup()
        routeColor.closePopup()
    }
}

internal class PopupColorPanel(
    private val project: Project,
    internal val pickerTitle: String,
    private val colorSelected: (Color) -> Unit,
) : ColorPanel() {
    private var activePopup: JBPopup? = null

    override fun onPressed() {
        if (!isEnabled) return
        closePopup()

        val picker = ColorPickerBuilder(false, false)
            .setOriginalColor(selectedColor ?: background)
            .addSaturationBrightnessComponent()
            .addColorAdjustPanel()
            .addColorValuePanel()
            .withFocus()
            .focusWhenDisplay(true)
            .setFocusCycleRoot(true)
            .addColorListener({ color, _ ->
                selectedColor = color
                colorSelected(color)
            }, true)
            .build()
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(picker.content, picker.content)
            .setTitle(pickerTitle)
            .setProject(project)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
        activePopup = popup
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (event.isOk) picker.closedCallback?.invoke() else picker.cancelCallBack?.invoke()
                if (activePopup === popup) activePopup = null
            }
        })
        popup.show(RelativePoint(this, Point(width / 2, height)))
    }

    fun closePopup() {
        activePopup?.takeUnless(JBPopup::isDisposed)?.cancel()
        activePopup = null
    }
}

private fun Color.toHex(): String = "#%02X%02X%02X".format(red, green, blue)
