package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode

class RestClientMainPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val requestEditor: RequestEditorPanel
    private val navPanel: NavigationPanel
    private val splitter: OnePixelSplitter

    init {
        // 1. 初始化组件 (保持不变)
        requestEditor = RequestEditorPanel(project) { refreshCollectionsTree() }
        navPanel = NavigationPanel(project, { selectedObject ->
            when (selectedObject) {
                is ApiDefinition -> requestEditor.renderApi(selectedObject)
                is CollectionNode -> requestEditor.renderSavedRequest(selectedObject)
            }
        }, { requestEditor.createNewEmptyRequest() })

        // 2. 初始化 Splitter (默认左右分割)
        // proportion = 0.2f 让左侧变窄，解决"右侧被挤压"感
        splitter = OnePixelSplitter(false, 0.2f)
        splitter.firstComponent = navPanel
        splitter.secondComponent = requestEditor

        // 3. 创建顶部工具栏 (用于切换布局)
        val toolbar = createToolbar()
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        setContent(splitter)
    }

    private fun createToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup()

        // 布局切换按钮
        val toggleLayoutAction = object : AnAction("Switch Layout", "Toggle horizontal/vertical split", AllIcons.Actions.SplitVertically) {
            override fun actionPerformed(e: AnActionEvent) {
                // 切换 orientation
                val isVertical = splitter.orientation
                splitter.orientation = !isVertical

                // 更新图标
                e.presentation.icon = if (isVertical) AllIcons.Actions.SplitVertically else AllIcons.Actions.SplitHorizontally
            }
        }

        actionGroup.add(toggleLayoutAction)
        return ActionManager.getInstance().createActionToolbar("RestClientMainToolbar", actionGroup, true)
    }

    private fun refreshCollectionsTree() {
        val tabbedPane = navPanel.components.find { it is javax.swing.JTabbedPane } as? javax.swing.JTabbedPane
        val collectionTab = tabbedPane?.getComponentAt(1) as? CollectionsTreePanel
        collectionTab?.reloadTree()
    }

    // LineMarker 调用入口
    fun openApiFromCode(api: ApiDefinition) {
        requestEditor.renderApi(api)
    }
}