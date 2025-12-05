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
 * 统一渲染器：支持 Live 模式 (ApiDefinition) 和 Collections 模式 (CollectionNode)
 * 风格：极简微着色徽章 (Outline + Tint)
 */
class CollectionTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject ?: return

        // --- 1. 容器节点 (Live 模式的 String 节点) ---
        if (userObject is String) {
            if (node.isRoot) {
                // 根节点
                icon = AllIcons.Nodes.Project
                append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else {
                // 判断是 Module 还是 Controller
                // 逻辑：如果它有子节点，且第一个子节点也是 String (Controller)，说明它是 Module
                // 否则（子节点是 API），说明它是 Controller
                val firstChild = if (node.childCount > 0) node.getChildAt(0) as? DefaultMutableTreeNode else null
                val isModule = firstChild?.userObject is String

                if (isModule) {
                    icon = AllIcons.Nodes.Module
                    append(userObject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    icon = AllIcons.Nodes.Class
                    append(userObject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    // Controller 显示 API 数量
                    append("  (${node.childCount})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            return
        }

        // --- 2. 容器节点 (Collections 模式的 Folder) ---
        if (userObject is CollectionNode && userObject.isFolder) {
            icon = AllIcons.Nodes.Folder
            append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            return
        }

        // --- 3. 请求节点 (Badge + Name + URL) ---
        var method = "GET"
        var displayName = ""
        var url = ""
        var isSaved = false

        if (userObject is ApiDefinition) {
            // Live 模式
            method = userObject.method
            displayName = userObject.methodName
            url = userObject.url
            isSaved = false
        } else if (userObject is CollectionNode && !userObject.isFolder) {
            // Collections 模式
            val req = userObject.request ?: return
            method = req.method
            displayName = userObject.name
            url = req.url
            isSaved = true
        } else {
            // 未知类型兜底
            append(userObject.toString())
            return
        }

        // 绘制 Outline + Tint 风格徽章
        icon = MethodBadgeIcon(method)

        val nameAttr = SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(" $displayName", nameAttr)

        // 显示 URL (灰色补充信息)
        if (url.isNotBlank()) {
            val displayUrl = if (url.length > 40) url.substring(0, 40) + "..." else url
            append("  $displayUrl", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * 图标绘制类：Outline + Tint 风格
     */
    private class MethodBadgeIcon(val method: String) : Icon {
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseColor = UIConstants.getMethodColor(method)

            // 1. 绘制背景 (Tint): 极淡的透明填充 (Alpha 40/255)
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 40)
            g2.fillRoundRect(x, y + 1, iconWidth, iconHeight - 2, 6, 6)

            // 2. 绘制边框 (Outline)
            g2.color = baseColor
            g2.drawRoundRect(x, y + 1, iconWidth, iconHeight - 2, 6, 6)

            // 3. 绘制文字 (使用原色)
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