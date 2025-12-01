package com.phil.rest.ui.render

import com.intellij.ui.JBColor
import java.awt.Color

object UIConstants {
    // 定义 Method 颜色 (参考 Postman/Swagger 经典配色)
    val GET_COLOR = JBColor(Color(97, 175, 239), Color(97, 175, 239))    // 蓝色
    val POST_COLOR = JBColor(Color(73, 204, 144), Color(73, 204, 144))   // 绿色
    val PUT_COLOR = JBColor(Color(252, 161, 48), Color(252, 161, 48))    // 橙色
    val DELETE_COLOR = JBColor(Color(249, 62, 62), Color(249, 62, 62))   // 红色
    val PATCH_COLOR = JBColor(Color(80, 227, 194), Color(80, 227, 194))  // 青色

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