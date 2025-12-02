package com.phil.rest.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.phil.rest.model.ExtractRule

object JsonExtractor {
    private val mapper = ObjectMapper()

    fun executeExtraction(jsonBody: String, rules: List<ExtractRule>, project: Project): Int {
        if (rules.isEmpty() || jsonBody.isBlank()) return 0

        var count = 0
        try {
            val root = mapper.readTree(jsonBody)
            val envService = EnvService.getInstance(project)
            val currentEnv = envService.selectedEnv ?: return 0

            for (rule in rules) {
                if (rule.variable.isNotBlank() && rule.path.isNotBlank()) {
                    val value = extractValue(root, rule.path)
                    if (value != null) {
                        currentEnv.variables[rule.variable] = value
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return count
    }

    // 支持 data.user.id 或 data.list[0].id 格式
    private fun extractValue(root: JsonNode, path: String): String? {
        var current = root
        // 简单按点分割，暂不支持带点的key
        val parts = path.split('.')

        for (part in parts) {
            if (part.contains('[')) {
                // 处理数组: list[0]
                val name = part.substringBefore('[')
                val indexStr = part.substringAfter('[').substringBefore(']')
                val index = indexStr.toIntOrNull() ?: 0

                current = current.path(name)
                if (current.isArray && current.size() > index) {
                    current = current.get(index)
                } else {
                    return null
                }
            } else {
                // 普通字段
                current = current.path(part)
            }

            if (current.isMissingNode) return null
        }

        return if (current.isContainerNode) current.toString() else current.asText()
    }
}