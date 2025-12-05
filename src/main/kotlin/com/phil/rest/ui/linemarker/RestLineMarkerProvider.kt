package com.phil.rest.ui.linemarker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader // [新增] 用于加载自定义图标
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.phil.rest.service.SpringScannerService
import com.phil.rest.ui.RestClientMainPanel
import javax.swing.Icon

class RestLineMarkerProvider : LineMarkerProvider {

    // [新增] 加载你的插件 LOGO (确保路径正确)
    // 建议使用 13x13 或 16x16 的小图标，显示效果最佳
    private val PLUGIN_ICON: Icon = IconLoader.getIcon("/icons/lineIcon.svg", RestLineMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 1. 基础判断：必须是方法的标识符 (方法名)
        if (element is PsiIdentifier && element.parent is PsiMethod) {
            val method = element.parent as PsiMethod
            val service = SpringScannerService(element.project)

            // 扫描阶段：传 false (不解析 Body)，保证编辑器流畅度
            // 这里会自动复用最新的 SpringScannerService 逻辑（包含 Module 识别）
            val apiDef = service.parseSingleMethod(method, false) ?: return null

            // 2. 创建 LineMarker
            return LineMarkerInfo(
                element,
                element.textRange,
                PLUGIN_ICON, // [修改] 这里换成你的图标
                { "Debug with RestPilot: ${apiDef.method} ${apiDef.url}" }, // Tooltip 提示
                { _, elt ->
                    // 点击阶段：传 true (解析完整 Body)
                    val project = elt.project
                    val fullApiDef = service.parseSingleMethod(method, true)

                    if (fullApiDef != null) {
                        // 激活 ToolWindow 并跳转
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("RestPilot")
                        toolWindow?.show { // 使用 show() 确保窗口打开
                            val content = toolWindow.contentManager.getContent(0)
                            val mainPanel = content?.component as? RestClientMainPanel
                            mainPanel?.openApiFromCode(fullApiDef)
                        }
                    }
                },
                GutterIconRenderer.Alignment.LEFT,
                { "RestPilot" }
            )
        }
        return null
    }
}