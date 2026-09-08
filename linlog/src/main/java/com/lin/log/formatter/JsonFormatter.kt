package com.lin.log.formatter

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 字符串美化格式化器接口
 */
public fun interface JsonFormatter : Formatter<String>

/**
 * 默认 JSON 格式化器（基于平台自带的 JSONObject / JSONArray 进行带缩进美化）
 *
 * @param indentSpaces 缩进空格数，默认为 4
 */
public class DefaultJsonFormatter(private val indentSpaces: Int = 4) : JsonFormatter {
    override fun format(data: String): String {
        if (data.isBlank()) return ""
        return try {
            val trimmed = data.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(indentSpaces)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(indentSpaces)
                else -> data
            }
        } catch (_: Throwable) {
            data
        }
    }
}
