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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

class GeekAddressBar(
    private val project: Project,
    private val onSend: () -> Unit
) : JPanel(BorderLayout()) {

    private var selectedMethod = "GET"

    // [新增] 控制发送状态
    var isBusy: Boolean = false
        set(value) {
            field = value
            sendBtn.repaint()
            sendBtn.cursor = if (value) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

    // 1. Method 组件
    private val methodLabel = object : JLabel(selectedMethod) {
        // [UI 优化] 增加悬停状态，提示可点击
        private var isHover = false

        init {
            font = Font("JetBrains Mono", Font.BOLD, 14)
            foreground = UIConstants.getMethodColor(selectedMethod)
            // 增加内边距，让背景色显示时更好看
            border = JBUI.Borders.empty(4, 12)
            isOpaque = false // 默认透明，paintComponent 里手动画背景
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!isBusy) showMethodPopup(e.component as JComponent)
                }
                override fun mouseEntered(e: MouseEvent) {
                    isHover = true
                    repaint()
                }
                override fun mouseExited(e: MouseEvent) {
                    isHover = false
                    repaint()
                }
            })
        }

        // [UI 优化] 绘制悬停背景
        override fun paintComponent(g: Graphics) {
            if (isHover && !isBusy) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor.PanelBackground.darker() // 微微变暗
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
    }

    // 3. Send 组件 (保持不变，略)
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

    // [核心优化] 下拉框美化
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
                c.font = Font("JetBrains Mono", Font.BOLD, 13) // 字体稍微大一点

                // [关键] 增加 Padding：上下 8px，左右 15px
                c.border = JBUI.Borders.empty(8, 15)

                return c
            }
        })

        builder.setFont(Font("JetBrains Mono", Font.BOLD, 13))
        builder.setItemChosenCallback { method = it }

        // 去掉 Popup 的默认边框，看起来更扁平
        val popup = builder.createPopup()
        popup.show(RelativePoint.getSouthWestOf(component))
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