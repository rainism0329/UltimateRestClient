package com.phil.rest.ui.linemarker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.phil.rest.service.SpringScannerService
import com.phil.rest.ui.RestClientMainPanel
import com.phil.rest.ui.NavigationPanel
import com.intellij.ui.components.JBTabbedPane

class RestLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 1. 只关注方法名标识符 (PsiIdentifier)，且父节点是方法 (PsiMethod)
        if (element is PsiIdentifier && element.parent is PsiMethod) {
            val method = element.parent as PsiMethod

            // 2. 利用 Service 快速判断这是否是一个 API 方法
            // 为了性能，我们不在这里做深度解析，只做简单判断，或者直接尝试解析
            // 这里的逻辑：如果 parseSingleMethod 返回非空，说明它是 API
            val service = SpringScannerService(element.project)
            val apiDef = service.parseSingleMethod(method) ?: return null

            // 3. 创建 LineMarker
            // element: 锚点元素
            // range: 图标显示的范围
            // icon: 图标 (暂时用 IDEA 自带的 Run 图标，你可以换成自己的 pluginIcon)
            // tooltip: 鼠标悬停提示
            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.Actions.Execute,
                { "Debug API: ${apiDef.method} ${apiDef.url}" },
                { _, elt ->
                    // 4. 点击事件处理
                    val project = elt.project
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("UltimateREST")

                    toolWindow?.activate {
                        // 获取我们的主面板
                        val content = toolWindow.contentManager.getContent(0)
                        val mainPanel = content?.component as? RestClientMainPanel

                        if (mainPanel != null) {
                            // 调用 mainPanel 的公开方法来渲染 API
                            // 注意：我们需要给 RestClientMainPanel 加一个 openApi 方法
                            mainPanel.openApiFromCode(apiDef)
                        }
                    }
                },
                GutterIconRenderer.Alignment.LEFT,
                { "UltimateREST" }
            )
        }
        return null
    }
}