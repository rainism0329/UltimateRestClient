package com.phil.rest.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.SavedRequest
import java.awt.BorderLayout
import javax.swing.JPanel

class NavigationPanel(
    project: Project,
    private val onApiSelect: (Any) -> Unit,
    private val onCreateNew: () -> Unit // 透传回调
) : JPanel(BorderLayout()) {

    init {
        val tabbedPane = JBTabbedPane()

        // Tab 1: Live (扫描出来的代码)
        // 复用之前的 ApiTreePanel
        val livePanel = ApiTreePanel(project) { apiDef ->
            onApiSelect(apiDef)
        }
        tabbedPane.addTab("Live", livePanel)

        // 传入 onCreateNew
        val collectionPanel = CollectionsTreePanel(project, { savedReq ->
            onApiSelect(savedReq)
        }, onCreateNew)

        tabbedPane.addTab("Collections", collectionPanel)
        add(tabbedPane, BorderLayout.CENTER)
    }
}