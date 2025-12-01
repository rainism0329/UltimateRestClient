package com.phil.rest.ui.component

import com.intellij.icons.AllIcons
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

/**
 * 极客风格沉浸式地址栏
 */
class GeekAddressBar(
    private val project: Project,
    private val onSend: () -> Unit // 修改回调：不需要传参，外部直接读取属性即可
) : JPanel(BorderLayout()) {

    private var selectedMethod = "GET"

    // 1. Method 组件
    private val methodLabel = object : JLabel(selectedMethod) {
        init {
            font = Font("JetBrains Mono", Font.BOLD, 14)
            foreground = UIConstants.getMethodColor(selectedMethod)
            border = JBUI.Borders.empty(0, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // [修复] 使用 e.component 获取当前组件 (即 methodLabel)
                    showMethodPopup(e.component as JComponent)
                }
            })
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

    // 3. Send 组件
    private val sendBtn = object : JComponent() {
        private var isHover = false

        init {
            preferredSize = Dimension(80, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onSend() // 触发发送
                }
                override fun mouseEntered(e: MouseEvent) { isHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 动态背景色
            val baseColor = UIConstants.getMethodColor(selectedMethod)
            val color = if (isHover) baseColor.brighter() else baseColor
            g2.color = color

            // 绘制圆角矩形
            val shape = RoundRectangle2D.Float(4f, 4f, width - 8f, height - 8f, 8f, 8f)
            g2.fill(shape)

            // 绘制文字
            g2.color = Color.WHITE
            g2.font = Font("JetBrains Mono", Font.BOLD, 13)
            val text = "SEND"
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(text)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, x, y)
        }
    }

    init {
        // 背景色适配 IDEA 主题
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground

        // 外层边框
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0), // 外留白
            RoundedBorder(JBColor.border()) // 圆角边框
        )

        // 组装布局
        add(methodLabel, BorderLayout.WEST)

        // 中间部分 (分隔线 + URL)
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

    // --- 公开属性 ---

    var method: String
        get() = selectedMethod
        set(value) {
            selectedMethod = value.uppercase()
            methodLabel.text = selectedMethod
            methodLabel.foreground = UIConstants.getMethodColor(selectedMethod)
            sendBtn.repaint() // 刷新按钮颜色
        }

    var url: String
        get() = urlField.text
        set(value) { urlField.text = value }

    // --- 内部方法 ---

    private fun showMethodPopup(component: JComponent) {
        val methods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

        // 1. 创建构建器
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(methods)

        // 2. *** 关键修复：注入自定义渲染器 ***
        builder.setRenderer(object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                // 获取默认的组件（处理了背景色、选中状态等）
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel

                val method = value.toString()
                c.text = method
                // 强制设置文字颜色为我们的 Geek 颜色
                c.foreground = UIConstants.getMethodColor(method)
                // 保持字体一致
                c.font = Font("JetBrains Mono", Font.BOLD, 12)

                return c
            }
        })

        // 3. 其他配置保持不变
        builder.setFont(Font("JetBrains Mono", Font.BOLD, 12))
        builder.setItemChosenCallback { method = it }

        // 4. 显示
        builder.createPopup().show(RelativePoint.getSouthWestOf(component))
    }

    // 自定义圆角边框绘制
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