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
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import kotlin.math.sin

class GeekAddressBar(
    private val project: Project,
    private val onSend: () -> Unit,
    private val onCancel: () -> Unit,
    private val onImportCurl: (String) -> Boolean
) : JPanel(BorderLayout()) {

    private var selectedMethod = "GET"

    // [动画参数]
    private var progressWidth = 0.0 // 改为 double 获得更平滑的动画
    private var timer: Timer? = null

    var isBusy: Boolean = false
        set(value) {
            field = value
            sendBtn.repaint()
            sendBtn.cursor = if (value) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            if (value) {
                progressWidth = 0.0
                timer?.stop()
                // 提高帧率到 60fps (16ms)
                timer = Timer(16) {
                    if (progressWidth < width) {
                        // 缓动算法：(目标 - 当前) * 0.1 产生一种顺滑的减速效果
                        val diff = width - progressWidth
                        val step = if (diff > 1) diff * 0.05 + 0.5 else 0.5
                        progressWidth += step
                        repaint()
                    }
                }.apply { start() }
            } else {
                timer?.stop()
                progressWidth = 0.0
                repaint()
            }
        }

    // [核心升级] 赛博朋克霓虹绘制
    override fun paintChildren(g: Graphics) {
        super.paintChildren(g) // 绘制子组件

        if (isBusy && progressWidth > 1) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseColor = UIConstants.getMethodColor(selectedMethod)
            val barHeight = 3
            val y = height - barHeight
            val w = progressWidth.toInt()

            // 1. 绘制光晕 (Glow) - 宽而淡
            // 使用 alpha = 50 的颜色，宽度稍微大一点
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 60)
            g2.fillRect(0, y - 1, w, barHeight + 2)

            // 2. 绘制核心光束 (Core) - 渐变拖尾
            // 从左(透明) 到 右(纯色) 的渐变
            val gradient = GradientPaint(
                0f, 0f, Color(baseColor.red, baseColor.green, baseColor.blue, 0),
                w.toFloat(), 0f, baseColor
            )
            g2.paint = gradient
            g2.fillRect(0, y, w, barHeight)

            // 3. 绘制头部高光 (Spark) - 就像光剑的顶端
            g2.color = Color.WHITE
            g2.fillRect(w - 2, y, 2, barHeight)
        }
    }

    // [新增] 物理震动反馈
    fun shake() {
        val originalLocation = location
        val shakeTimer = Timer(30, null)
        var counter = 0

        shakeTimer.addActionListener {
            val amplitude = 5.0 // 震动幅度
            val decay = 0.8 // 衰减系数

            // 简单的阻尼正弦波
            val offset = (sin(counter.toDouble()) * amplitude * Math.pow(decay, counter.toDouble())).toInt()

            // 这里我们震动的是 AddressBar 内部的组件，或者你可以震动整个 Panel (如果 Layout 允许)
            // 为了简单且安全，我们微调 URL Field 的 border 产生视觉错位，或者直接微调 JPanel 的绘制坐标
            // 但 Swing 布局中改 location 可能会被 LayoutManager 强制复位。
            // 最简单的视觉欺骗：改变 border padding

            val pad = 2 + offset
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(2, if(pad>0) pad else 2, 2, if(pad<0) -pad else 2), // 左右晃动
                RoundedBorder(JBColor.border())
            )

            counter++
            if (counter > 15) {
                shakeTimer.stop()
                // 复位
                border = BorderFactory.createCompoundBorder(JBUI.Borders.empty(2, 0), RoundedBorder(JBColor.border()))
            }
        }
        shakeTimer.start()
    }

    // --- 组件定义 (保持原逻辑，微调 UI) ---

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
                // 鼠标悬停时的背景也改为淡色光晕
                val c = UIConstants.getMethodColor(selectedMethod)
                g2.color = Color(c.red, c.green, c.blue, 30) // 30 alpha
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
                    if (!isBusy) onSend() else onCancel()
                }
                override fun mouseEntered(e: MouseEvent) { isHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHover = false; repaint() }
            })
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseColor = if (isBusy) JBColor.RED else UIConstants.getMethodColor(selectedMethod) // Busy时变红(Stop)
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