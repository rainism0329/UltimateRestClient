package com.phil.rest.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter

class RestClientMainPanel(project: Project) : SimpleToolWindowPanel(true, true) {

    init {
        // 1. 创建右侧编辑器
        val requestEditor = RequestEditorPanel()

        // 2. 创建左侧树，并传入回调：当点击树时，调用 editor.renderApi()
        val apiTree = ApiTreePanel(project) { selectedApi ->
            requestEditor.renderApi(selectedApi)
        }

        // 3. 创建分割面板 (左右布局)
        val splitter = OnePixelSplitter(false, 0.3f) // false表示水平分割，0.3f是左侧默认宽度比例
        splitter.firstComponent = apiTree
        splitter.secondComponent = requestEditor

        // 4. 设置为主内容
        setContent(splitter)
    }
}