package com.phil.rest.ui.render

import com.intellij.ui.JBColor
import java.awt.Color

object UIConstants {
    // 调色板：更明亮、更适合 Neon 效果的颜色
    val GET_COLOR = JBColor(Color(65, 145, 255), Color(65, 145, 255))    // 亮蓝
    val POST_COLOR = JBColor(Color(73, 204, 144), Color(73, 204, 144))   // 保持经典的绿
    val PUT_COLOR = JBColor(Color(255, 160, 0), Color(255, 160, 0))      // 亮橙
    val DELETE_COLOR = JBColor(Color(240, 50, 50), Color(240, 50, 50))   // 亮红
    val PATCH_COLOR = JBColor(Color(50, 200, 200), Color(50, 200, 200))  // 青色

    fun getMethodColor(method: String): Color {
        return when (method.uppercase()) {
            "GET" -> GET_COLOR
            "POST" -> POST_COLOR
            "PUT" -> PUT_COLOR
            "DELETE" -> DELETE_COLOR
            "PATCH" -> PATCH_COLOR
            else -> JBColor.GRAY
        }
    }
}