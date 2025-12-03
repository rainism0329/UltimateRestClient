package com.phil.rest.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.json.JsonFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.phil.rest.model.RestResponse
import com.phil.rest.service.CodeGenerator
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.*

class ResponsePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var editor: Editor? = null // Pretty JSON Editor
    private val document = EditorFactory.getInstance().createDocument("")

    // [Fix] 数据黑匣子：独立存储 Body 内容，不受 UI clear() 影响
    private var previousBody: String? = null
    private var currentBody: String? = null

    // Hex View 组件
    private val hexTextArea = JBTextArea().apply {
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        isEditable = false
        foreground = JBColor(Color(0, 128, 0), Color(169, 183, 198)) // Matrix Green
        background = JBColor(Color(245, 245, 245), Color(43, 43, 43))
    }

    // 图片组件
    private val imageLabel = JLabel("", SwingConstants.CENTER)

    // 多视图切换
    private val tabs = JBTabbedPane()

    // 状态栏
    private val statusLabel = JBLabel("Ready", AllIcons.General.Balloon, SwingConstants.LEFT).apply {
        font = Font("JetBrains Mono", Font.BOLD, 12)
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val timeLabel = JBLabel().apply {
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        foreground = JBColor.GRAY
    }

    init {
        // --- Header (状态栏 + 工具栏) ---
        val headerColor = JBColor.namedColor("Breadcrumbs.Current.bg", JBUI.CurrentTheme.ToolWindow.headerBackground())
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = headerColor
        headerPanel.border = JBUI.Borders.empty(4, 10)

        val statusInfoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0))
        statusInfoPanel.isOpaque = false
        statusInfoPanel.add(statusLabel)
        statusInfoPanel.add(timeLabel)

        val actionGroup = DefaultActionGroup()
        actionGroup.add(createCopyAction())
        actionGroup.add(createDiffAction()) // Diff 按钮
        actionGroup.add(createExportAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("ResponseToolbar", actionGroup, true)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()

        headerPanel.add(statusInfoPanel, BorderLayout.WEST)
        headerPanel.add(toolbar.component, BorderLayout.EAST)

        // --- Editor (Pretty) ---
        editor = EditorFactory.getInstance().createViewer(document, project)
        val editorEx = editor as EditorEx
        editorEx.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, JsonFileType.INSTANCE)
        editorEx.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            isIndentGuidesShown = true
            isUseSoftWraps = true
        }

        // --- 组装 Tabs ---
        tabs.addTab("Pretty", editor!!.component)
        tabs.addTab("Preview", ScrollPaneFactory.createScrollPane(imageLabel))
        tabs.addTab("Hex", ScrollPaneFactory.createScrollPane(hexTextArea))

        add(headerPanel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    fun updateResponse(response: RestResponse?) {
        if (response == null) {
            clear() // 这里的 clear 只是为了重置 UI，不应影响数据流转
            return
        }

        // [Fix] 核心修复：使用 currentBody 变量进行历史轮转，而不是读取 document.text
        // 因为 document.text 在请求开始时已经被 clear() 清空了
        if (!currentBody.isNullOrBlank()) {
            previousBody = currentBody
        }

        // 更新当前 Body
        currentBody = response.body

        // 1. 更新 Pretty View
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(StringUtil.convertLineSeparators(response.body))
        }
        editor?.scrollingModel?.scrollTo(editor!!.offsetToLogicalPosition(0), ScrollType.MAKE_VISIBLE)

        // 2. 更新 Image View
        if (response.rawBody != null && response.rawBody.isNotEmpty()) {
            try {
                val icon = ImageIcon(response.rawBody)
                if (icon.iconWidth > 0) {
                    imageLabel.icon = icon
                    imageLabel.text = ""
                } else {
                    imageLabel.icon = null
                    imageLabel.text = "No Image Preview"
                }
            } catch (e: Exception) {
                imageLabel.icon = null
                imageLabel.text = "Error Loading Image"
            }
        } else {
            imageLabel.icon = null
            imageLabel.text = "Empty Body"
        }

        // 3. 更新 Hex View
        val hexDump = CodeGenerator.generateHexDump(response.rawBody ?: ByteArray(0))
        hexTextArea.text = hexDump
        hexTextArea.caretPosition = 0

        // 4. 智能切换 Tab
        val contentType = response.headers["Content-Type"]?.firstOrNull() ?: ""
        if (contentType.startsWith("image/")) {
            tabs.selectedIndex = 1 // Preview
        } else {
            tabs.selectedIndex = 0 // Pretty
        }

        // 5. 更新状态栏
        updateStatusLabel(response)
    }

    private fun updateStatusLabel(response: RestResponse) {
        statusLabel.text = "${response.statusCode} ${getStatusText(response.statusCode)}"
        timeLabel.text = "${response.durationMs} ms"

        if (response.statusCode in 200..299) {
            statusLabel.icon = AllIcons.RunConfigurations.TestState.Green2
            statusLabel.foreground = Color(54, 150, 70)
        } else {
            statusLabel.icon = AllIcons.RunConfigurations.TestState.Red2
            statusLabel.foreground = Color(200, 50, 50)
        }
    }

    fun clear() {
        // [注意] 这里只清空 UI 显示，绝对不要清空 previousBody 和 currentBody
        WriteCommandAction.runWriteCommandAction(project) { document.setText("") }
        imageLabel.icon = null
        hexTextArea.text = ""
        statusLabel.text = "Ready"
        statusLabel.icon = AllIcons.General.Balloon
        statusLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        timeLabel.text = ""
        tabs.selectedIndex = 0
    }

    private fun getStatusText(code: Int): String = when(code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        else -> ""
    }

    override fun dispose() {
        editor?.let { if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it) }
    }

    // Actions

    private fun createDiffAction() = object : DumbAwareAction("Compare with Previous", "Diff current vs previous response", AllIcons.Actions.Diff) {
        override fun actionPerformed(e: AnActionEvent) {
            // [Fix] 使用变量而非 document 读取，更安全
            val curr = currentBody
            val prev = previousBody

            if (prev.isNullOrBlank() || curr.isNullOrBlank()) {
                // [Fix] 更新为您喜欢的提示文案
                showBalloon("Send another request to generate a comparison!", MessageType.WARNING)
                return
            }

            val contentFactory = DiffContentFactory.getInstance()
            val content1 = contentFactory.create(prev, JsonFileType.INSTANCE)
            val content2 = contentFactory.create(curr, JsonFileType.INSTANCE)

            val request = SimpleDiffRequest("Response Diff", content1, content2, "Previous Response", "Current Response")
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun createCopyAction() = object : DumbAwareAction("Copy", "Copy body", AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) {
            val text = document.text
            if (text.isNotEmpty()) {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                showBalloon("Copied!", MessageType.INFO)
            }
        }
    }

    private fun createExportAction() = object : DumbAwareAction("Export", "Export to file", AllIcons.Actions.MenuSaveall) {
        override fun actionPerformed(e: AnActionEvent) {
            val descriptor = FileSaverDescriptor("Export Response", "Save response body", "json", "txt")
            val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            val wrapper = dialog.save(null as VirtualFile?, "response.json")
            if (wrapper != null) {
                try {
                    wrapper.file.writeText(document.text)
                    showBalloon("Saved to ${wrapper.file.name}", MessageType.INFO)
                } catch (ex: Exception) {}
            }
        }
    }

    private fun showBalloon(msg: String, type: MessageType) {
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(msg, type, null)
            .setFadeoutTime(2000).createBalloon()
            .show(RelativePoint.getSouthEastOf(statusLabel), Balloon.Position.atRight)
    }
}