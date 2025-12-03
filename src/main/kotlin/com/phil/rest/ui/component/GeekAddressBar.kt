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
    private val onCancel: () -> Unit,
    private val onImportCurl: (String) -> Boolean
) : JPanel(BorderLayout()) {

    private var selectedMethod = "GET"

    // 动画参数
    private var progressWidth = 0.0
    private var timer: Timer? = null

    // 边框闪烁参数
    private var currentBorderColor: Color = JBColor.border()

    // --- 组件定义 (必须在 init 之前定义) ---

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
                // 悬停背景：极淡的 Method 颜色
                val c = UIConstants.getMethodColor(selectedMethod)
                g2.color = Color(c.red, c.green, c.blue, 25)
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

        // cURL 粘贴支持
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
                    if (!isBusy) onSend() else onCancel()
                }
                override fun mouseEntered(e: MouseEvent) { isHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 按钮颜色：保持极简，Hover 时变亮
            // Busy 状态下显示红色 (STOP)
            val baseColor = if (isBusy) JBColor(Color(200, 80, 80), Color(200, 80, 80))
            else UIConstants.getMethodColor(selectedMethod)

            val color = if (isHover) baseColor.brighter() else baseColor

            g2.color = color
            val shape = RoundRectangle2D.Float(4f, 4f, width - 8f, height - 8f, 8f, 8f)
            g2.fill(shape)

            g2.color = Color.WHITE
            g2.font = Font("JetBrains Mono", Font.BOLD, 13)

            val text = if (isBusy) "STOP" else "SEND"
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(text)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, x, y)
        }
    }

    // --- isBusy 属性 (依赖 sendBtn，所以放组件后面) ---

    var isBusy: Boolean = false
        set(value) {
            field = value
            sendBtn.repaint()
            // 忙碌时变为手型（表示可点击取消）
            sendBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            if (value) {
                // 启动进度条动画 (60fps)
                progressWidth = 0.0
                timer?.stop()
                timer = Timer(16) {
                    if (progressWidth < width) {
                        // 缓动算法：越接近终点越慢
                        val diff = width - progressWidth
                        val step = if (diff > 1) diff * 0.05 + 0.5 else 0.5
                        progressWidth += step
                        repaint()
                    }
                }.apply { start() }
            } else {
                // 停止动画
                timer?.stop()
                progressWidth = 0.0
                repaint()
            }
        }

    // --- Init Block (最后执行) ---

    init {
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        updateBorder() // 初始化边框

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

    // --- 核心绘制与方法 ---

    /**
     * 绘制霓虹进度条 (Neon Glow)
     */
    override fun paintChildren(g: Graphics) {
        super.paintChildren(g) // 先绘制子组件

        if (isBusy && progressWidth > 1) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseColor = UIConstants.getMethodColor(selectedMethod)
            val barHeight = 3
            val y = height - barHeight
            val w = progressWidth.toInt()

            // 1. 绘制淡淡的光晕 (Glow)
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 60)
            g2.fillRect(0, y - 1, w, barHeight + 2)

            // 2. 绘制核心光束 (Core) - 带渐变拖尾
            val gradient = GradientPaint(
                0f, 0f, Color(baseColor.red, baseColor.green, baseColor.blue, 0), // 左侧透明
                w.toFloat(), 0f, baseColor // 右侧纯色
            )
            g2.paint = gradient
            g2.fillRect(0, y, w, barHeight)

            // 3. 头部高光 (Spark)
            g2.color = Color.WHITE
            g2.fillRect(w - 2, y, 2, barHeight)
        }
    }

    /**
     * 呼吸灯闪烁效果 (Flash)
     */
    fun flash(flashColor: Color) {
        val steps = 20
        val delay = 20
        var count = 0
        val originalColor = JBColor.border()

        val flashTimer = Timer(delay, null)
        flashTimer.addActionListener {
            count++
            // 线性插值
            val ratio = count.toFloat() / steps
            val r = (flashColor.red * (1 - ratio) + originalColor.red * ratio).toInt()
            val g = (flashColor.green * (1 - ratio) + originalColor.green * ratio).toInt()
            val b = (flashColor.blue * (1 - ratio) + originalColor.blue * ratio).toInt()

            currentBorderColor = Color(r, g, b)
            updateBorder()
            repaint()

            if (count >= steps) {
                flashTimer.stop()
                currentBorderColor = originalColor
                updateBorder()
            }
        }
        flashTimer.start()
    }

    private fun updateBorder() {
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0),
            RoundedBorder(currentBorderColor)
        )
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