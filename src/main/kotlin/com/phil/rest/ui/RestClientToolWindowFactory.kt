package com.phil.rest.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.ui.Messages
import javax.swing.JComponent

class RestClientToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 创建内容面板
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(createPanel(project), "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createPanel(project: Project): JComponent {
        return panel {
            group("Dashboard") {
                row {
                    label("Welcome to Ultimate REST Client")
                }
                row {
                    text("Current Project: ${project.name}")
                }
            }

            group("Actions") {
                row {
                    button("Scan Spring Controllers") {
                        // 1. 获取 Service 实例
                        val scanner = com.phil.rest.service.SpringScannerService(project)

                        // 2. 执行扫描 (这是耗时操作，理论上应该在后台线程做，但演示先直接跑)
                        val apis = scanner.scanCurrentProject()

                        // 3. 格式化输出结果
                        val resultText = if (apis.isEmpty()) {
                            "No Spring Controllers found. (Ensure you have Spring Web dependency)"
                        } else {
                            apis.joinToString("\n") { it.toString() }
                        }

                        // 4. 弹窗显示
                        Messages.showInfoMessage(project, resultText, "Scan Result (${apis.size} Found)")
                    }
                }
            }
        }
    }
}