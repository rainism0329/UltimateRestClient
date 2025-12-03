package com.phil.rest.ui

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
import com.intellij.util.ui.JBUI
import com.phil.rest.model.RestResponse
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class ResponsePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var editor: Editor? = null
    private val document = EditorFactory.getInstance().createDocument("")

    // [新增] 图片容器
    private val imageLabel = JLabel("", SwingConstants.CENTER)
    private val contentPanel = JPanel(CardLayout()) // 使用 CardLayout 切换 Text/Image

    // --- 状态栏组件 ---
    private val statusLabel = JBLabel("Ready", AllIcons.General.Balloon, SwingConstants.LEFT).apply {
        font = Font("JetBrains Mono", Font.BOLD, 12)
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val timeLabel = JBLabel().apply {
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        foreground = JBColor.GRAY
    }

    init {
        val headerColor = JBColor.namedColor("Breadcrumbs.Current.bg", JBUI.CurrentTheme.ToolWindow.headerBackground())

        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = headerColor
        headerPanel.border = JBUI.Borders.empty(4, 10)

        // 左侧状态
        val statusInfoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0))
        statusInfoPanel.isOpaque = false
        statusInfoPanel.add(statusLabel)
        statusInfoPanel.add(timeLabel)

        // 右侧工具栏
        val actionGroup = DefaultActionGroup()
        actionGroup.add(createCopyAction())
        actionGroup.add(createExportAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("ResponseToolbar", actionGroup, true)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()

        headerPanel.add(statusInfoPanel, BorderLayout.WEST)
        headerPanel.add(toolbar.component, BorderLayout.EAST)

        // Editor 创建
        editor = EditorFactory.getInstance().createViewer(document, project)
        val editorEx = editor as EditorEx

        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, JsonFileType.INSTANCE)
        editorEx.highlighter = highlighter

        val settings = editorEx.settings
        settings.isLineNumbersShown = true
        settings.isFoldingOutlineShown = true
        settings.isUseSoftWraps = true
        settings.isWhitespacesShown = false
        settings.isIndentGuidesShown = true
        settings.isVirtualSpace = false
        settings.isCaretRowShown = false

        // [核心修改] 组装 CardLayout
        contentPanel.add(editor!!.component, "TEXT")
        // 图片外面包一个 ScrollPane，防止图片过大撑爆布局
        contentPanel.add(ScrollPaneFactory.createScrollPane(imageLabel), "IMAGE")

        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun updateResponse(response: RestResponse?) {
        if (response == null) {
            clear()
            return
        }

        val layout = contentPanel.layout as CardLayout

        // 1. 获取 Content-Type 判断是否为图片
        val contentTypeHeader = response.headers["Content-Type"] ?: response.headers["content-type"]
        val contentType = contentTypeHeader?.firstOrNull() ?: ""

        if (contentType.startsWith("image/")) {
            // --- 图片渲染模式 ---
            try {
                if (response.rawBody != null && response.rawBody.isNotEmpty()) {
                    val icon = ImageIcon(response.rawBody)
                    imageLabel.icon = icon
                    imageLabel.text = "" // 清空文字
                    layout.show(contentPanel, "IMAGE")
                } else {
                    imageLabel.icon = null
                    imageLabel.text = "[Empty Image Body]"
                    layout.show(contentPanel, "IMAGE")
                }
            } catch (e: Exception) {
                // 如果解析失败，回退到文本模式显示乱码或错误
                setTextResponse(response.body)
                layout.show(contentPanel, "TEXT")
            }
        } else {
            // --- 文本/JSON 渲染模式 ---
            setTextResponse(response.body)
            layout.show(contentPanel, "TEXT")
        }

        // 更新状态栏
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

    private fun setTextResponse(bodyText: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val normalizedBody = StringUtil.convertLineSeparators(bodyText)
            document.setText(normalizedBody)
        }
        editor?.scrollingModel?.scrollTo(editor!!.offsetToLogicalPosition(0), ScrollType.MAKE_VISIBLE)
    }

    fun clear() {
        WriteCommandAction.runWriteCommandAction(project) { document.setText("") }
        imageLabel.icon = null // 清空图片
        statusLabel.text = "Ready"
        statusLabel.icon = AllIcons.General.Balloon
        statusLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        timeLabel.text = ""
        (contentPanel.layout as CardLayout).show(contentPanel, "TEXT")
    }

    private fun getStatusText(code: Int): String = when(code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        else -> ""
    }

    override fun dispose() {
        editor?.let {
            if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it)
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
                    // 简单起见，Export 还是存文本。
                    // 如果要做得更完美，可以根据当前是文本模式还是图片模式，决定写入 text 还是 bytes
                    wrapper.file.writeText(document.text)
                    showBalloon("Saved to ${wrapper.file.name}", MessageType.INFO)
                } catch (ex: Exception) {}
            }
        }
    }

    private fun showBalloon(msg: String, type: MessageType) {
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(msg, type, null)
            .setFadeoutTime(1500).createBalloon()
            .show(RelativePoint.getSouthEastOf(statusLabel), Balloon.Position.atRight)
    }
}