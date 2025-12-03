package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode
import com.phil.rest.ui.render.UIConstants
import java.awt.*
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * 风格：极简微着色徽章 (Outline + Tint)
 */
class CollectionTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject ?: return

        // --- 1. 容器节点 ---
        if (userObject is String) {
            if (node.isRoot) {
                icon = AllIcons.Nodes.Project
                append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else {
                icon = AllIcons.Nodes.Class
                append(userObject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  (${node.childCount})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            return
        }

        if (userObject is CollectionNode && userObject.isFolder) {
            icon = AllIcons.Nodes.Folder
            append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            return
        }

        // --- 2. 请求节点 ---
        var method = "GET"
        var displayName = ""
        var url = ""
        var isSaved = false

        if (userObject is ApiDefinition) {
            method = userObject.method
            displayName = userObject.methodName
            url = userObject.url
            isSaved = false
        } else if (userObject is CollectionNode && !userObject.isFolder) {
            val req = userObject.request ?: return
            method = req.method
            displayName = userObject.name
            url = req.url
            isSaved = true
        } else {
            append(userObject.toString())
            return
        }

        // 使用新版图标
        icon = MethodBadgeIcon(method)

        val nameAttr = SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(" $displayName", nameAttr)

        if (url.isNotBlank()) {
            val displayUrl = if (url.length > 40) url.substring(0, 40) + "..." else url
            append("  $displayUrl", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * 新版：Outline + Tint 风格
     */
    private class MethodBadgeIcon(val method: String) : Icon {
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseColor = UIConstants.getMethodColor(method)

            // 1. 绘制背景 (Tint): 极淡的透明填充 (Alpha ~15%)
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 40)
            g2.fillRoundRect(x, y + 1, iconWidth, iconHeight - 2, 6, 6)

            // 2. 绘制边框 (Outline)
            g2.color = baseColor
            g2.drawRoundRect(x, y + 1, iconWidth, iconHeight - 2, 6, 6)

            // 3. 绘制文字 (使用原色，不再是白色)
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