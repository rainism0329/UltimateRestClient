package com.phil.rest.ui.component

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.phil.rest.ui.render.UIConstants
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

class GeekAddressBar(
    private val project: Project,
    private val onSend: () -> Unit,
    private val onImportCurl: (String) -> Boolean
) : JPanel(BorderLayout()) {

    private var selectedMethod = "GET"

    // [新增] 动画相关属性
    private var progressWidth = 0
    private var timer: Timer? = null

    var isBusy: Boolean = false
        set(value) {
            field = value
            sendBtn.repaint()
            sendBtn.cursor = if (value) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // [新增] 动画控制逻辑
            if (value) {
                // 开始请求：启动动画
                progressWidth = 0
                timer?.stop()
                timer = Timer(10) {
                    // 简单的缓动算法：越接近终点越慢，制造"还在努力加载"的感觉
                    if (progressWidth < width) {
                        val step = (width - progressWidth) / 40 + 1
                        progressWidth += step
                        repaint()
                    }
                }.apply { start() }
            } else {
                // 请求结束：停止动画并重置
                timer?.stop()
                progressWidth = 0
                repaint()
            }
        }

    // [新增] 绘制进度条 (覆盖在子组件之上)
    override fun paintChildren(g: Graphics) {
        super.paintChildren(g) // 先画子组件 (TextField, Button)

        if (isBusy) {
            val g2 = g as Graphics2D
            // 使用当前 HTTP 方法的颜色（例如 GET 是蓝色，POST 是绿色），非常有整体感
            g2.color = UIConstants.getMethodColor(selectedMethod)
            // 在底部画一个 3 像素高的条
            g2.fillRect(0, height - 3, progressWidth, 3)
        }
    }

    // 1. Method 组件
    private val methodLabel = object : JLabel(selectedMethod) {
        private var isHover = false
        init {
            font = Font("JetBrains Mono", Font.BOLD, 14)
            foreground = UIConstants.getMethodColor(selectedMethod)
            border = JBUI.Borders.empty(4, 12)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!isBusy) showMethodPopup(e.component as JComponent)
                }
                override fun mouseEntered(e: MouseEvent) { isHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHover = false; repaint() }
            })
        }
        override fun paintComponent(g: Graphics) {
            if (isHover && !isBusy) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor.PanelBackground.darker()
                g2.fillRoundRect(2, 2, width - 4, height - 4, 6, 6)
            }
            super.paintComponent(g)
        }
    }

    // 2. URL 组件
    private val urlField = JBTextField().apply {
        font = Font("JetBrains Mono", Font.PLAIN, 14)
        border = JBUI.Borders.empty(8, 10)
        background = null
        isOpaque = false
        emptyText.text = "https://api.example.com/v1/..."

        // 拦截粘贴事件以支持 cURL
        transferHandler = object : TransferHandler() {
            override fun importData(support: TransferSupport): Boolean {
                if (canImport(support)) {
                    try {
                        val text = support.transferable.getTransferData(DataFlavor.stringFlavor) as String
                        // 尝试识别 cURL
                        if (text.trim().startsWith("curl", ignoreCase = true)) {
                            if (onImportCurl(text)) {
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                return super.importData(support)
            }

            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor)
            }
        }
    }

    // 3. Send 组件
    private val sendBtn = object : JComponent() {
        private var isHover = false
        init {
            preferredSize = Dimension(80, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (!isBusy) onSend() }
                override fun mouseEntered(e: MouseEvent) { isHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHover = false; repaint() }
            })
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val baseColor = if (isBusy) JBColor.GRAY else UIConstants.getMethodColor(selectedMethod)
            val color = if (isHover && !isBusy) baseColor.brighter() else baseColor
            g2.color = color
            val shape = RoundRectangle2D.Float(4f, 4f, width - 8f, height - 8f, 8f, 8f)
            g2.fill(shape)
            g2.color = Color.WHITE
            g2.font = Font("JetBrains Mono", Font.BOLD, 13)
            val text = if (isBusy) "..." else "SEND"
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(text)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, x, y)
        }
    }

    init {
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0),
            RoundedBorder(JBColor.border())
        )

        add(methodLabel, BorderLayout.WEST)

        val centerPanel = JPanel(BorderLayout())
        centerPanel.isOpaque = false

        val separator = JSeparator(SwingConstants.VERTICAL)
        separator.preferredSize = Dimension(1, 20)
        separator.foreground = JBColor.border()

        centerPanel.add(separator, BorderLayout.WEST)
        centerPanel.add(urlField, BorderLayout.CENTER)

        add(centerPanel, BorderLayout.CENTER)
        add(sendBtn, BorderLayout.EAST)
    }

    var method: String
        get() = selectedMethod
        set(value) {
            selectedMethod = value.uppercase()
            methodLabel.text = selectedMethod
            methodLabel.foreground = UIConstants.getMethodColor(selectedMethod)
            sendBtn.repaint()
        }

    var url: String
        get() = urlField.text
        set(value) { urlField.text = value }

    private fun showMethodPopup(component: JComponent) {
        val methods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(methods)
        builder.setRenderer(object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val m = value.toString()
                c.text = m
                c.foreground = UIConstants.getMethodColor(m)
                c.font = Font("JetBrains Mono", Font.BOLD, 13)
                c.border = JBUI.Borders.empty(8, 15)
                return c
            }
        })
        builder.setFont(Font("JetBrains Mono", Font.BOLD, 13))
        builder.setItemChosenCallback { method = it }
        builder.createPopup().show(RelativePoint.getSouthWestOf(component))
    }

    private class RoundedBorder(val color: Color) : javax.swing.border.AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.drawRoundRect(x, y, width - 1, height - 1, 10, 10)
        }
        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(5)
    }
}