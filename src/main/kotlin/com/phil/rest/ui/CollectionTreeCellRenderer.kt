package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode
import com.phil.rest.ui.render.UIConstants
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * 统一渲染器：同时支持 Live 模式 (ApiDefinition) 和 Collections 模式 (CollectionNode)
 * 风格：极客、彩色 Method 徽章
 */
class CollectionTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject ?: return

        // --- 1. 容器节点渲染 (文件夹 / Controller 类) ---

        // Case A: Live 模式的 Controller (通常是 String 类名)
        if (userObject is String) {
            // 判断是不是根节点 (Project Name)
            if (node.isRoot) {
                icon = AllIcons.Nodes.Project
                append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else {
                icon = AllIcons.Nodes.Class // 或者是 AllIcons.Nodes.Package
                append(userObject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                // 加上 item 数量提示 (可选)
                append("  (${node.childCount})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            return
        }

        // Case B: Collections 模式的文件夹
        if (userObject is CollectionNode && userObject.isFolder) {
            icon = AllIcons.Nodes.Folder
            append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            return
        }

        // --- 2. 请求节点渲染 (Method Badge + Name + URL) ---

        var method = "GET"
        var displayName = ""
        var url = ""
        var isSaved = false

        if (userObject is ApiDefinition) {
            // Live 模式
            method = userObject.method
            displayName = userObject.methodName // 显示方法名
            url = userObject.url
            isSaved = false
        } else if (userObject is CollectionNode && !userObject.isFolder) {
            // Collections 模式
            val req = userObject.request ?: return
            method = req.method
            displayName = userObject.name // 显示保存的名称
            url = req.url
            isSaved = true
        } else {
            // 未知类型
            append(userObject.toString())
            return
        }

        // a. 绘制彩色 Method 胶囊
        icon = MethodBadgeIcon(method)

        // b. 绘制名称
        val nameAttr = if (isSaved) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(" $displayName", nameAttr)

        // c. 绘制 URL (淡灰色，作为补充信息)
        if (url.isNotBlank()) {
            // 如果 URL 太长，截断一下
            val displayUrl = if (url.length > 40) url.substring(0, 40) + "..." else url
            append("  $displayUrl", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * 自定义图标：绘制彩色的 HTTP Method 文字
     */
    private class MethodBadgeIcon(val method: String) : Icon {
        override fun paintIcon(c: java.awt.Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val color = UIConstants.getMethodColor(method)
            g2.color = color

            // 选用 Bold 字体让它看起来像个 Badge
            g2.font = g2.font.deriveFont(java.awt.Font.BOLD, 10f)

            // 计算居中位置
            val fm = g2.fontMetrics
            val textX = x + (iconWidth - fm.stringWidth(method.uppercase())) / 2
            val textY = y + (iconHeight - fm.height) / 2 + fm.ascent

            g2.drawString(method.uppercase(), textX, textY)
        }

        override fun getIconWidth(): Int = 36 // 稍微宽一点，容纳 DELETE
        override fun getIconHeight(): Int = 16
    }
}