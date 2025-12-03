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
    private val onCancel: () -> Unit, // [新增] 取消回调
    private val onImportCurl: (String) -> Boolean
) : JPanel(BorderLayout()) {

    private var selectedMethod = "GET"
    private var progressWidth = 0
    private var timer: Timer? = null

    var isBusy: Boolean = false
        set(value) {
            field = value
            sendBtn.repaint()
            // 加载时依然保持手型指针，因为现在它是 Cancel 按钮
            sendBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            if (value) {
                progressWidth = 0
                timer?.stop()
                timer = Timer(10) {
                    if (progressWidth < width) {
                        val step = (width - progressWidth) / 40 + 1
                        progressWidth += step
                        repaint()
                    }
                }.apply { start() }
            } else {
                timer?.stop()
                progressWidth = 0
                repaint()
            }
        }

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        if (isBusy) {
            val g2 = g as Graphics2D
            g2.color = UIConstants.getMethodColor(selectedMethod)
            g2.fillRect(0, height - 3, progressWidth, 3)
        }
    }

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

    private val urlField = JBTextField().apply {
        font = Font("JetBrains Mono", Font.PLAIN, 14)
        border = JBUI.Borders.empty(8, 10)
        background = null
        isOpaque = false
        emptyText.text = "https://api.example.com/v1/..."
        transferHandler = object : TransferHandler() {
            override fun importData(support: TransferSupport): Boolean {
                if (canImport(support)) {
                    try {
                        val text = support.transferable.getTransferData(DataFlavor.stringFlavor) as String
                        if (text.trim().startsWith("curl", ignoreCase = true)) {
                            if (onImportCurl(text)) return true
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                return super.importData(support)
            }
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor)
            }
        }
    }

    private val sendBtn = object : JComponent() {
        private var isHover = false
        init {
            preferredSize = Dimension(80, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // [修改] 核心交互逻辑
                    if (!isBusy) onSend() else onCancel()
                }
                override fun mouseEntered(e: MouseEvent) { isHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHover = false; repaint() }
            })
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // [修改] 颜色逻辑
            // 加载中显示红色背景（或者你想保持原色也可以，这里用红色表示警示/停止）
            // 这里为了美观，我们保持底色，只变文字，或者把背景变成淡红色
            val baseColor = if (isBusy) UIConstants.getMethodColor(selectedMethod) else UIConstants.getMethodColor(selectedMethod)
            val color = if (isHover) baseColor.brighter() else baseColor

            g2.color = color
            val shape = RoundRectangle2D.Float(4f, 4f, width - 8f, height - 8f, 8f, 8f)
            g2.fill(shape)

            g2.color = Color.WHITE
            g2.font = Font("JetBrains Mono", Font.BOLD, 13)

            // [修改] 文字逻辑
            val text = if (isBusy) "STOP" else "SEND"

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