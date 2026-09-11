package app.lecturevault.data

internal object SessionJsonCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(session: LectureSession): String {
        val value = SessionValues.normalized(session)
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
            append("  \"id\": ").appendJsonString(value.id).append(",\n")
            append("  \"title\": ").appendJsonString(value.title).append(",\n")
            append("  \"course\": ").appendJsonString(value.course).append(",\n")
            append("  \"createdAt\": ").append(value.createdAt).append(",\n")
            append("  \"startedAt\": ").append(value.startedAt).append(",\n")
            append("  \"endedAt\": ")
            if (value.endedAt == null) append("null") else append(value.endedAt)
            append(",\n")
            append("  \"status\": ").appendJsonString(value.status.name).append(",\n")
            append("  \"segmentFiles\": [")
            value.segmentFiles.forEachIndexed { index, path ->
                if (index > 0) append(", ")
                appendJsonString(path)
            }
            append("],\n")
            append("  \"progress\": ").append(value.progress).append(",\n")
            append("  \"errorMessage\": ")
            if (value.errorMessage == null) append("null") else appendJsonString(value.errorMessage)
            append(",\n")
            append("  \"noteRelativePath\": ")
            if (value.noteRelativePath == null) append("null") else appendJsonString(value.noteRelativePath)
            append("\n}\n")
        }
    }

    fun decode(json: String): LectureSession {
        val root = JsonParser(json).parseRootObject()
        require(root.requiredLong("schemaVersion") == SCHEMA_VERSION.toLong()) {
            "Unsupported session schema version"
        }

        val statusName = root.requiredString("status")
        val status = runCatching { SessionStatus.valueOf(statusName) }
            .getOrElse { throw IllegalArgumentException("Unknown session status") }

        return SessionValues.normalized(
            LectureSession(
                id = root.requiredString("id"),
                title = root.requiredString("title"),
                course = root.requiredString("course"),
                createdAt = root.requiredLong("createdAt"),
                startedAt = root.requiredLong("startedAt"),
                endedAt = root.nullableLong("endedAt"),
                status = status,
                segmentFiles = root.requiredStringArray("segmentFiles"),
                progress = root.requiredInt("progress"),
                errorMessage = root.nullableString("errorMessage"),
                noteRelativePath = root.nullableString("noteRelativePath"),
            ),
        )
    }

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        return append('"')
    }
}

private sealed interface JsonValue

private data class JsonObject(val values: Map<String, JsonValue>) : JsonValue {
    fun requiredString(name: String): String = when (val value = required(name)) {
        is JsonString -> value.value
        else -> invalidField(name)
    }

    fun nullableString(name: String): String? = when (val value = required(name)) {
        JsonNull -> null
        is JsonString -> value.value
        else -> invalidField(name)
    }

    fun requiredLong(name: String): Long = when (val value = required(name)) {
        is JsonNumber -> value.raw.toLongOrNull() ?: invalidField(name)
        else -> invalidField(name)
    }

    fun nullableLong(name: String): Long? = when (val value = required(name)) {
        JsonNull -> null
        is JsonNumber -> value.raw.toLongOrNull() ?: invalidField(name)
        else -> invalidField(name)
    }

    fun requiredInt(name: String): Int {
        val longValue = requiredLong(name)
        require(longValue in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid JSON field: $name" }
        return longValue.toInt()
    }

    fun requiredStringArray(name: String): List<String> = when (val value = required(name)) {
        is JsonArray -> value.values.map { item ->
            (item as? JsonString)?.value ?: invalidField(name)
        }
        else -> invalidField(name)
    }

    private fun required(name: String): JsonValue =
        values[name] ?: throw IllegalArgumentException("Missing JSON field: $name")

    private fun invalidField(name: String): Nothing =
        throw IllegalArgumentException("Invalid JSON field: $name")
}

private data class JsonArray(val values: List<JsonValue>) : JsonValue
private data class JsonString(val value: String) : JsonValue
private data class JsonNumber(val raw: String) : JsonValue
private data class JsonBoolean(val value: Boolean) : JsonValue
private data object JsonNull : JsonValue

/** Small strict parser for the repository's dependency-free JSON format. */
private class JsonParser(private val source: String) {
    private var index = 0

    fun parseRootObject(): JsonObject {
        skipWhitespace()
        val result = parseObject()
        skipWhitespace()
        require(index == source.length) { invalidJsonMessage() }
        return result
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        require(index < source.length) { invalidJsonMessage() }
        return when (source[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            'n' -> parseLiteral("null", JsonNull)
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException(invalidJsonMessage())
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWhitespace()
        if (consumeIf('}')) return JsonObject(emptyMap())

        val values = linkedMapOf<String, JsonValue>()
        while (true) {
            skipWhitespace()
            require(index < source.length && source[index] == '"') { invalidJsonMessage() }
            val name = parseString()
            require(!values.containsKey(name)) { "Duplicate JSON field: $name" }
            skipWhitespace()
            expect(':')
            values[name] = parseValue()
            skipWhitespace()
            if (consumeIf('}')) break
            expect(',')
        }
        return JsonObject(values)
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWhitespace()
        if (consumeIf(']')) return JsonArray(emptyList())

        val values = mutableListOf<JsonValue>()
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consumeIf(']')) break
            expect(',')
        }
        return JsonArray(values)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> result.append(parseEscape())
                character.code < 0x20 -> throw IllegalArgumentException(invalidJsonMessage())
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException(invalidJsonMessage())
    }

    private fun parseEscape(): Char {
        require(index < source.length) { invalidJsonMessage() }
        return when (val escaped = source[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> throw IllegalArgumentException(invalidJsonMessage())
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + 4 <= source.length) { invalidJsonMessage() }
        val raw = source.substring(index, index + 4)
        val codePoint = raw.toIntOrNull(16) ?: throw IllegalArgumentException(invalidJsonMessage())
        index += 4
        return codePoint.toChar()
    }

    private fun parseNumber(): JsonNumber {
        val start = index
        consumeIf('-')
        require(index < source.length) { invalidJsonMessage() }
        if (consumeIf('0')) {
            require(index >= source.length || source[index] !in '0'..'9') { invalidJsonMessage() }
        } else {
            require(source[index] in '1'..'9') { invalidJsonMessage() }
            while (index < source.length && source[index] in '0'..'9') index++
        }
        if (consumeIf('.')) {
            require(index < source.length && source[index] in '0'..'9') { invalidJsonMessage() }
            while (index < source.length && source[index] in '0'..'9') index++
        }
        if (index < source.length && source[index] in charArrayOf('e', 'E')) {
            index++
            if (index < source.length && source[index] in charArrayOf('+', '-')) index++
            require(index < source.length && source[index] in '0'..'9') { invalidJsonMessage() }
            while (index < source.length && source[index] in '0'..'9') index++
        }
        return JsonNumber(source.substring(start, index))
    }

    private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
        require(source.regionMatches(index, literal, 0, literal.length)) { invalidJsonMessage() }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
    }

    private fun expect(expected: Char) {
        require(index < source.length && source[index] == expected) { invalidJsonMessage() }
        index++
    }

    private fun consumeIf(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index++
        return true
    }

    private fun invalidJsonMessage(): String = "Invalid session JSON at offset $index"
}
