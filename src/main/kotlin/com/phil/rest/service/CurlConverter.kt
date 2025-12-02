package com.phil.rest.service

import com.phil.rest.model.RestParam
import com.phil.rest.model.SavedRequest

object CurlConverter {

    data class CurlRequest(
        val method: String,
        val url: String,
        val headers: List<RestParam>,
        val body: String?,
        val contentType: String?
    )

    fun toCurl(req: SavedRequest): String {
        val sb = StringBuilder("curl")
        if (req.method.uppercase() != "GET") {
            sb.append(" -X ${req.method.uppercase()}")
        }
        sb.append(" '${req.url}'")
        for (header in req.headers) {
            if (header.name.isNotBlank()) {
                sb.append(" \\\n  -H '${header.name}: ${escape(header.value)}'")
            }
        }
        if (!req.bodyContent.isNullOrBlank()) {
            sb.append(" \\\n  -d '${escape(req.bodyContent!!)}'")
        }
        return sb.toString()
    }

    fun parseCurl(command: String): CurlRequest? {
        // [终极净化]
        // 1. 替换中文引号为英文引号
        // 2. 将所有 Unicode 空白字符(\p{Z})和控制字符(\p{C})替换为普通空格
        // 3. trim 去除首尾
        val sanitized = command
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace(Regex("[\\p{Z}\\p{C}]+"), " ")
            .trim()

        if (!sanitized.startsWith("curl", ignoreCase = true)) return null

        val args = tokenize(sanitized)

        var method = "GET"
        var url = ""
        val headers = ArrayList<RestParam>()
        var body: String? = null
        var contentType: String? = null

        var i = 1
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "-X", "--request" -> {
                    if (i + 1 < args.size) method = args[++i].uppercase()
                }
                "-H", "--header" -> {
                    if (i + 1 < args.size) {
                        val headerLine = args[++i]
                        val split = headerLine.split(":", limit = 2)
                        if (split.size == 2) {
                            val key = split[0].trim()
                            val value = split[1].trim()
                            headers.add(RestParam(key, value, RestParam.ParamType.HEADER, "String"))
                            if (key.equals("Content-Type", ignoreCase = true)) {
                                contentType = value
                            }
                        }
                    }
                }
                "-d", "--data", "--data-raw", "--data-binary", "--data-ascii" -> {
                    if (i + 1 < args.size) body = args[++i]
                }
                "--url" -> {
                    if (i + 1 < args.size) url = args[++i]
                }
                else -> {
                    // 如果不是 flag 且 url 为空，则认为是 url
                    if (!arg.startsWith("-") && url.isEmpty()) {
                        url = arg
                    }
                }
            }
            i++
        }

        if (body != null && method == "GET") {
            method = "POST"
        }
        url = url.trim('\'', '"')

        return CurlRequest(method, url, headers, body, contentType)
    }

    private fun escape(s: String): String {
        return s.replace("'", "'\\''")
    }

    private fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false

        for (c in text.toCharArray()) {
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }

            if (c == '\\') {
                if (!inSingleQuote && !inDoubleQuote) {
                    continue
                }
                if (inSingleQuote) sb.append(c) else escaped = true
                continue
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                continue
            }

            if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())

        return tokens
    }
}