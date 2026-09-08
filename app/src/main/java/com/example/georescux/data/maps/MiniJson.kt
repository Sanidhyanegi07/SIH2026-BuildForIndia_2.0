package com.example.georescux.data.maps

/**
 * Minimal recursive-descent JSON parser for the offline map files.
 * Supports objects, arrays, strings (with escapes), numbers, booleans and
 * null; numbers are parsed as Double. Pure Kotlin — JVM testable, no
 * Android/org.json dependency (whose stubs throw in local unit tests).
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("Unexpected trailing content at ${parser.position}")
        return value
    }

    private class Parser(private val text: String) {
        var position = 0

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun peek(): Char {
            if (atEnd()) throw IllegalArgumentException("Unexpected end of JSON input")
            return text[position]
        }

        fun expect(expected: Char) {
            if (atEnd() || text[position] != expected) {
                throw IllegalArgumentException("Expected '$expected' at $position")
            }
            position++
        }

        fun parseValue(): Any? = when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = mutableMapOf<String, Any?>()
            if (!atEnd() && peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $position")
                }
            }
        }

        fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (!atEnd() && peek() == ']') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $position")
                }
            }
        }

        fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val character = text[position++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> builder.append(parseEscapedCharacter())
                    else -> builder.append(character)
                }
            }
        }

        fun parseEscapedCharacter(): Char {
            if (atEnd()) throw IllegalArgumentException("Unterminated escape sequence")
            val escape = text[position++]
            return when (escape) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> 0x000C.toChar()
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> throw IllegalArgumentException("Invalid escape sequence '\\$escape'")
            }
        }

        fun parseUnicodeEscape(): Char {
            if (position + 4 > text.length) throw IllegalArgumentException("Incomplete unicode escape")
            val hex = text.substring(position, position + 4)
            position += 4
            return hex.toInt(16).toChar()
        }

        fun parseNumber(): Double {
            val start = position
            if (!atEnd() && (peek() == '-' || peek() == '+')) position++
            while (!atEnd() && text[position].isDigit()) position++
            if (!atEnd() && text[position] == '.') {
                position++
                while (!atEnd() && text[position].isDigit()) position++
            }
            if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!atEnd() && (text[position] == '+' || text[position] == '-')) position++
                while (!atEnd() && text[position].isDigit()) position++
            }
            val number = text.substring(start, position)
            if (number.isEmpty() || number == "-" || number == "+" || number == ".") {
                throw IllegalArgumentException("Invalid number at $start")
            }
            return number.toDouble()
        }

        fun parseLiteral(literal: String, value: Any?): Any? {
            if (text.regionMatches(position, literal, 0, literal.length)) {
                position += literal.length
                return value
            }
            throw IllegalArgumentException("Invalid literal at $position")
        }
    }
}
