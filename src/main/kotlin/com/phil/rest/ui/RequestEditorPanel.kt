package com.phil.rest.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.phil.rest.model.ApiDefinition
import com.phil.rest.service.HttpExecutor
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

class RequestEditorPanel : JPanel(BorderLayout()) {

    private val methodComboBox = ComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH"))
    private val urlField = JTextField()
    private val sendButton = JButton("Send")
    private val tabbedPane = JBTabbedPane()

    // 响应区域组件
    private val responseArea = JBTextArea().apply { isEditable = false }
    private val responseStatusLabel = javax.swing.JLabel("Ready")

    init {
        // 1. 顶部请求栏
        val topPanel = panel {
            row {
                cell(methodComboBox)
                cell(urlField).align(AlignX.FILL)
                cell(sendButton)
            }
        }

        // 2. 响应区域布局 (状态栏 + 文本区域)
        val responsePanel = JPanel(BorderLayout())
        responsePanel.add(responseStatusLabel, BorderLayout.NORTH)
        responsePanel.add(JBScrollPane(responseArea), BorderLayout.CENTER)

        // 3. 构建 Tabs
        tabbedPane.addTab("Params", JPanel())
        tabbedPane.addTab("Body", JPanel())
        tabbedPane.addTab("Headers", JPanel())
        tabbedPane.addTab("Response", responsePanel)

        // 4. 组装主界面
        add(topPanel, BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)

        // 5. 绑定 Send 按钮事件
        sendButton.addActionListener {
            sendRequest()
        }
    }

    private fun sendRequest() {
        val method = methodComboBox.selectedItem as String
        val url = urlField.text

        if (url.isBlank()) return

        // UI 切换到 Loading 状态
        sendButton.isEnabled = false
        responseStatusLabel.text = "Sending..."
        responseArea.text = ""
        // 自动切到 Response Tab
        tabbedPane.selectedIndex = 3

        // 异步执行网络请求 (不要在 EDT UI 线程做!)
        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            val response = executor.execute(method, url)

            // 回到 UI 线程更新界面
            SwingUtilities.invokeLater {
                sendButton.isEnabled = true

                if (response.statusCode == 0) {
                    responseStatusLabel.text = "Failed (${response.durationMs}ms)"
                    responseArea.text = response.body // 显示错误信息
                } else {
                    responseStatusLabel.text = "Status: ${response.statusCode}  Time: ${response.durationMs}ms"
                    // 简单显示 Headers 和 Body
                    val sb = StringBuilder()
                    sb.append("--- Headers ---\n")
                    sb.append(response.headersString)
                    sb.append("\n--- Body ---\n")
                    sb.append(response.body)

                    responseArea.text = sb.toString()
                    // 滚动到顶部
                    responseArea.caretPosition = 0
                }
            }
        }
    }

    fun renderApi(api: ApiDefinition) {
        methodComboBox.selectedItem = api.method.uppercase()
        // 这里可以做个简单的处理，如果扫描到的 URL 是相对路径，可以在前面加个 localhost:8080 方便调试
        // 但为了严谨，我们先原样填充
        urlField.text = "http://localhost:8080" + api.url
    }
}