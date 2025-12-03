package com.phil.rest.service

import com.phil.rest.model.SavedRequest

object CodeGenerator {

    fun generateJava11(req: SavedRequest): String {
        val sb = StringBuilder()
        sb.append("import java.net.URI;\n")
        sb.append("import java.net.http.HttpClient;\n")
        sb.append("import java.net.http.HttpRequest;\n")
        sb.append("import java.net.http.HttpResponse;\n\n")

        sb.append("HttpClient client = HttpClient.newHttpClient();\n")
        sb.append("HttpRequest request = HttpRequest.newBuilder()\n")
        sb.append("    .uri(URI.create(\"${req.url}\"))\n")

        req.headers.forEach {
            if (it.name.isNotBlank()) sb.append("    .header(\"${it.name}\", \"${it.value}\")\n")
        }

        // 简单处理 Body
        if (req.method.equals("POST", true) || req.method.equals("PUT", true)) {
            val body = if (req.bodyContent.isNullOrBlank()) "HttpRequest.BodyPublishers.noBody()"
            else "HttpRequest.BodyPublishers.ofString(\"${req.bodyContent!!.replace("\"", "\\\"")}\")"
            sb.append("    .${req.method.uppercase()}($body)\n")
        } else {
            sb.append("    .${req.method.uppercase()}()\n")
        }

        sb.append("    .build();\n\n")
        sb.append("HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());\n")
        sb.append("System.out.println(response.body());")
        return sb.toString()
    }

    fun generateKotlinOkHttp(req: SavedRequest): String {
        val sb = StringBuilder()
        sb.append("val client = OkHttpClient()\n")
        sb.append("val request = Request.Builder()\n")
        sb.append("    .url(\"${req.url}\")\n")

        req.headers.forEach {
            if (it.name.isNotBlank()) sb.append("    .addHeader(\"${it.name}\", \"${it.value}\")\n")
        }

        if (!req.bodyContent.isNullOrBlank() && req.method.uppercase() in listOf("POST", "PUT", "PATCH")) {
            // 简单假设是 JSON
            sb.append("    .${req.method.lowercase()}(\"${req.bodyContent!!.replace("\"", "\\\"")}\".toRequestBody(\"application/json\".toMediaType()))\n")
        } else if (req.method.uppercase() != "GET") {
            sb.append("    .method(\"${req.method.uppercase()}\", null)\n")
        }

        sb.append("    .build()\n\n")
        sb.append("client.newCall(request).execute().use { response ->\n")
        sb.append("    println(response.body?.string())\n")
        sb.append("}")
        return sb.toString()
    }

    // 生成 Hex Dump 字符串
    fun generateHexDump(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "Empty Body"
        val sb = StringBuilder()
        val width = 16
        for (i in bytes.indices step width) {
            // 1. Offset
            sb.append(String.format("%08X  ", i))

            // 2. Hex
            for (j in 0 until width) {
                if (i + j < bytes.size) {
                    sb.append(String.format("%02X ", bytes[i + j]))
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(" ") // 中间加个空格
            }
            sb.append(" |")

            // 3. ASCII
            for (j in 0 until width) {
                if (i + j < bytes.size) {
                    val b = bytes[i + j]
                    // 只显示可打印字符 (32-126)
                    val c = if (b in 32..126) b.toChar() else '.'
                    sb.append(c)
                }
            }
            sb.append("|\n")
        }
        return sb.toString()
    }
}