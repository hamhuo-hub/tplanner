package com.hamhuo.tplanner.syncv3

import org.json.JSONArray
import org.json.JSONObject

/**
 * RFC 8785 (JCS) 规范化序列化 —— 与服务器/桌面端的 canonicalize 语义一致,
 * 保证三端对同一 state 计算出的 stateHash 逐字节相同(键排序、ES 字符串转义、数字格式)。
 * 仅支持协议中实际出现的 JSON 类型(string/boolean/long/double/null/object/array)。
 */
object Jcs {

    fun canonicalize(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Double -> number(value)
        is Float -> number(value.toDouble())
        is JSONObject -> {
            val keys = mutableListOf<String>()
            value.keys().forEach { keys.add(it) }
            keys.sort()
            val parts = keys.joinToString(",") { key ->
                quote(key) + ":" + canonicalize(value.get(key))
            }
            "{$parts}"
        }
        is JSONArray -> {
            val parts = (0 until value.length()).joinToString(",") { i ->
                canonicalize(value.get(i))
            }
            "[$parts]"
        }
        else -> throw IllegalArgumentException("unsupported JSON type in canonical state: ${value.javaClass.name}")
    }

    /** ECMAScript 数字序列化:整数浮点补 .0(JCS 规范),其余用最短表示。 */
    private fun number(d: Double): String {
        if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("non-finite number in canonical state")
        return if (d % 1.0 == 0.0 && d.toString().indexOf('E') < 0 && d.toString().indexOf('e') < 0) {
            "${d.toLong()}.0"
        } else {
            d.toString()
        }
    }

    /** ECMAScript JSON.stringify 字符串转义。 */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
