package com.phil.rest.ui.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.phil.rest.model.RestEnv
import com.phil.rest.service.EnvService
import com.phil.rest.ui.EnvManagerDialog
import javax.swing.JComponent

class EnvironmentComboAction(
    private val project: Project,
    private val onEnvChange: () -> Unit
) : ComboBoxAction(), DumbAware {

    private val service = EnvService.getInstance(project)

    override fun createPopupActionGroup(button: JComponent?): DefaultActionGroup {
        val group = DefaultActionGroup()

        // 1. 添加 "No Environment"
        group.add(object : AnAction("No Environment") {
            override fun actionPerformed(e: AnActionEvent) {
                service.selectedEnv = null
                onEnvChange()
            }
        })
        group.addSeparator()

        // 2. 添加所有环境
        service.envs.forEach { env ->
            group.add(object : AnAction(env.name) {
                override fun actionPerformed(e: AnActionEvent) {
                    service.selectedEnv = env
                    onEnvChange()
                }
            })
        }

        // 3. 添加管理按钮
        group.addSeparator()
        group.add(object : AnAction("Manage Environments...") {
            override fun actionPerformed(e: AnActionEvent) {
                if (EnvManagerDialog(project).showAndGet()) {
                    onEnvChange() // 刷新
                }
            }
        })

        return group
    }

    override fun update(e: AnActionEvent) {
        val env = service.selectedEnv
        e.presentation.text = env?.name ?: "No Environment"
        e.presentation.description = "Select environment"
    }
}