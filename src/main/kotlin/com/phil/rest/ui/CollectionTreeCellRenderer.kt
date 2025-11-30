package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.phil.rest.model.CollectionNode
import javax.swing.JTree

class CollectionTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? javax.swing.tree.DefaultMutableTreeNode ?: return
        val data = node.userObject as? CollectionNode ?: return

        if (data.isFolder) {
            icon = AllIcons.Nodes.Folder
            append(data.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        } else {
            // 请求节点
            val method = data.request?.method ?: "GET"
            icon = AllIcons.FileTypes.Json // 或者是特定的 Method 图标
            append("[$method] ", SimpleTextAttributes.GRAY_ATTRIBUTES)
            append(data.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}