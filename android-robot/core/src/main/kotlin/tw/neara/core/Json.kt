package tw.neara.core

/** 極簡 JSON 輸出（核心模組不依賴任何第三方函式庫，方便在 Android 與 JVM 共用、測試）。 */
object Json {
    fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    fun str(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${str(it.key.toString())}:${value(it.value)}" }
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        is Array<*> -> v.joinToString(",", "[", "]") { value(it) }
        is JsonWritable -> v.toJson()
        else -> str(v.toString())
    }
}

interface JsonWritable { fun toJson(): String }
