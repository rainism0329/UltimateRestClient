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

class RestLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 1. 基础判断
        if (element is PsiIdentifier && element.parent is PsiMethod) {
            val method = element.parent as PsiMethod
            val service = SpringScannerService(element.project)

            // [修复] 移除 "resolveBody ="，直接传 false
            // 扫描阶段：传 false，不解析 Body，保证 IDE 流畅
            val apiDef = service.parseSingleMethod(method, false) ?: return null

            // 2. 创建 LineMarker
            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.Actions.Execute,
                { "Debug API: ${apiDef.method} ${apiDef.url}" },
                { _, elt ->
                    // 点击阶段：传 true，解析完整 Body
                    val project = elt.project
                    // [修复] 移除 "resolveBody ="，直接传 true
                    val fullApiDef = service.parseSingleMethod(method, true)

                    if (fullApiDef != null) {
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("UltimateREST")
                        toolWindow?.activate {
                            val content = toolWindow.contentManager.getContent(0)
                            val mainPanel = content?.component as? RestClientMainPanel
                            mainPanel?.openApiFromCode(fullApiDef)
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