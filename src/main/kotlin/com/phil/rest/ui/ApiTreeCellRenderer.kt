package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.phil.rest.model.ApiDefinition
import javax.swing.JTree

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
        val node = value as? javax.swing.tree.DefaultMutableTreeNode ?: return
        val userObject = node.userObject ?: return

        when (userObject) {
            is String -> {
                // 项目名称 (Root) 或 Controller 名称
                // 判断是否是 Controller (根据层级简单判断，或者判断是否包含 ApiDefinition 子节点)
                if (node.level == 1) {
                    icon = AllIcons.Nodes.Class
                    append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                } else {
                    icon = AllIcons.Nodes.Project
                    append(userObject)
                }
            }
            is ApiDefinition -> {
                // API 节点
                // 根据 HTTP Method 设置不同的图标或颜色 (这里暂时统一用 Method 图标)
                icon = AllIcons.Nodes.Method

                // 拼接显示文本: [GET] /api/users
                append("[${userObject.method}] ", SimpleTextAttributes.GRAY_ATTRIBUTES)
                append(userObject.url, SimpleTextAttributes.REGULAR_ATTRIBUTES)

                // 可以追加方法名作为提示
                append("  (${userObject.methodName})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}