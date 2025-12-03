package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.phil.rest.model.ApiDefinition
import com.phil.rest.ui.render.UIConstants
import java.awt.*
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class ApiTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject ?: return

        if (userObject is String) {
            if (node.level == 1) {
                icon = AllIcons.Nodes.Class
                append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else {
                icon = AllIcons.Nodes.Project
                append(userObject)
            }
            return
        }

        if (userObject is ApiDefinition) {
            // 使用新版徽章
            icon = MethodBadgeIcon(userObject.method)

            append(" ${userObject.methodName}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${userObject.url}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * Outline + Tint 风格 (复用自 CollectionTreeCellRenderer)
     */
    private class MethodBadgeIcon(val method: String) : Icon {
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseColor = UIConstants.getMethodColor(method)

            // 1. Tint Background
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 40)
            g2.fillRoundRect(x, y + 1, iconWidth, iconHeight - 2, 6, 6)

            // 2. Outline
            g2.color = baseColor
            g2.drawRoundRect(x, y + 1, iconWidth, iconHeight - 2, 6, 6)

            // 3. Text
            g2.color = baseColor
            g2.font = g2.font.deriveFont(Font.BOLD, 10f)

            val fm = g2.fontMetrics
            val textX = x + (iconWidth - fm.stringWidth(method.uppercase())) / 2
            val textY = y + (iconHeight - fm.height) / 2 + fm.ascent

            g2.drawString(method.uppercase(), textX, textY)
        }

        override fun getIconWidth(): Int = 42
        override fun getIconHeight(): Int = 18
    }
}