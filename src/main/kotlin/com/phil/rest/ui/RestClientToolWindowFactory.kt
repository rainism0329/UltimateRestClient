package com.phil.rest.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.Timer

class RestClientToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 1. 创建主面板
        val mainPanel = RestClientMainPanel(project)

        // 2. 添加到 ToolWindow
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // 3. 启动图标闪烁动画 (传入 project)
        // [Fix] 这里传入 project
        startIconAnimation(project, toolWindow, content)
    }

    // [Fix] 方法签名增加 project 参数
    private fun startIconAnimation(project: Project, toolWindow: ToolWindow, parentDisposable: Disposable) {
        // 加载 4 帧动画图标 (确保这些文件在 resources/icons/ 目录下存在)
        val icons = try {
            listOf(
                IconLoader.getIcon("/icons/icon_step_1.svg", RestClientToolWindowFactory::class.java),
                IconLoader.getIcon("/icons/icon_step_2.svg", RestClientToolWindowFactory::class.java),
                IconLoader.getIcon("/icons/icon_step_3.svg", RestClientToolWindowFactory::class.java),
                IconLoader.getIcon("/icons/icon_step_4.svg", RestClientToolWindowFactory::class.java)
            )
        } catch (e: Exception) {
            return
        }

        var index = 0

        // 创建定时器，每 500ms 切换一次 (呼吸节奏)
        val timer = Timer(500) {
            // [Fix] 现在这里可以访问 project 了
            if (!project.isDisposed) {
                toolWindow.setIcon(icons[index])
                index = (index + 1) % icons.size
            }
        }

        timer.start()

        // 当 ToolWindow 关闭时停止定时器
        Disposer.register(parentDisposable) {
            timer.stop()
        }
    }
}