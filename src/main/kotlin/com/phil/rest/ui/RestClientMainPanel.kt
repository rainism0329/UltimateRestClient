package com.phil.rest.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBTabbedPane
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode

class RestClientMainPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    // 将组件提升为属性，方便后续交互
    private val requestEditor: RequestEditorPanel
    private val navPanel: NavigationPanel

    init {
        // 1. 初始化右侧编辑器
        // 传入 onSaveSuccess 回调：当用户保存成功后，我们要刷新左侧的 Collections 列表
        requestEditor = RequestEditorPanel(project) {
            refreshCollectionsTree()
        }

        // 2. 初始化左侧导航面板
        // 实现 onCreateNew 回调
        navPanel = NavigationPanel(project, { selectedObject ->
            when (selectedObject) {
                is ApiDefinition -> requestEditor.renderApi(selectedObject)
                is CollectionNode -> requestEditor.renderSavedRequest(selectedObject)
            }
        }, {
            // 当点击 "New Request" 时，调用编辑器的清空方法
            requestEditor.createNewEmptyRequest()
        })

        // 3. 使用 Splitter 进行左右布局
        val splitter = OnePixelSplitter(false, 0.3f)
        splitter.firstComponent = navPanel
        splitter.secondComponent = requestEditor

        setContent(splitter)
    }

    /**
     * 刷新左侧 Collections 树的辅助方法
     * 这是一个稍微有点"硬"的查找方式，但在 Swing 组件树中很常见
     */
    private fun refreshCollectionsTree() {
        // NavigationPanel 里面是一个 JBTabbedPane
        val tabbedPane = navPanel.components.find { it is JBTabbedPane } as? JBTabbedPane

        // 假设 CollectionsTreePanel 是第二个 Tab (index 1)
        // 更好的做法是把 CollectionsTreePanel 公开为 NavigationPanel 的属性，但这里先这样做
        if (tabbedPane != null && tabbedPane.tabCount > 1) {
            val collectionTab = tabbedPane.getComponentAt(1) as? CollectionsTreePanel
            collectionTab?.reloadTree()

            // 可选：保存成功后自动切换到 Collections Tab
            tabbedPane.selectedIndex = 1
        }
    }
}