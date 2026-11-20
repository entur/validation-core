package no.entur.exposed.paging

import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or

/**
 * A hand-rolled recursive-descent parser for the restricted https://google.aip.dev/160 subset
 * [PagingSupport.parseFilter] supports:
 * ```
 * expression  := term (AND term)*
 * term        := factor (OR factor)*
 * factor      := '(' expression ')' | restriction
 * restriction := fieldPath '=' value
 * fieldPath   := IDENT ('.' IDENT)*
 * value       := IDENT | '"' ... '"'
 * ```
 * `AND`/`OR` are case-sensitive bare words, per spec. Following AIP-160's own precedence, `OR`
 * binds *tighter* than `AND` (the opposite of most languages): `a = X AND b = Y OR b = Z` means
 * `a = X AND (b = Y OR b = Z)`.
 */
internal class FilterParser<T : Message>(
    filter: String,
    private val filterableProperties: FilterableProperties<T>,
) {
    private val length = filter.length
    private val tokens = tokenize(filter)
    private var position = 0

    fun parse(): Op<Boolean> {
        val result = parseExpression()
        require(
            position == tokens.size,
        ) { "Invalid filter: unexpected input after position ${tokens.getOrNull(position)?.start ?: length}" }
        return result
    }

    private fun parseExpression(): Op<Boolean> {
        var result = parseTerm()
        while (peekIsKeyword("AND")) {
            position++
            result = result and parseTerm()
        }
        return result
    }

    private fun parseTerm(): Op<Boolean> {
        var result = parseFactor()
        while (peekIsKeyword("OR")) {
            position++
            result = result or parseFactor()
        }
        return result
    }

    private fun parseFactor(): Op<Boolean> {
        if (peekIs(TokenType.LPAREN)) {
            position++
            val result = parseExpression()
            expect(TokenType.RPAREN)
            return result
        }
        return parseRestriction()
    }

    private fun parseRestriction(): Op<Boolean> {
        val field = expect(TokenType.IDENT)
        expect(TokenType.EQUALS)
        val value = next()
        require(value.type == TokenType.IDENT || value.type == TokenType.STRING) {
            "Invalid filter: expected a value after '${field.text} =' at position ${value.start}"
        }
        return filterableProperties.byName(field.text).equalCondition(value.text)
    }

    private fun peekIs(type: TokenType): Boolean = position < tokens.size && tokens[position].type == type

    private fun peekIsKeyword(keyword: String): Boolean =
        position < tokens.size && tokens[position].type == TokenType.IDENT && tokens[position].text == keyword

    private fun expect(type: TokenType): Token {
        require(peekIs(type)) {
            "Invalid filter: expected '$type' at position ${tokens.getOrNull(position)?.start ?: "end of input"}"
        }
        return tokens[position++]
    }

    private fun next(): Token {
        require(position < tokens.size) { "Invalid filter: unexpected end of input" }
        return tokens[position++]
    }

    private enum class TokenType { LPAREN, RPAREN, EQUALS, IDENT, STRING }

    private data class Token(
        val type: TokenType,
        val text: String,
        val start: Int,
    )

    private companion object {
        fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

        fun tokenize(input: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    c.isWhitespace() -> {
                        i++
                    }

                    c == '(' -> {
                        tokens += Token(TokenType.LPAREN, "(", i)
                        i++
                    }

                    c == ')' -> {
                        tokens += Token(TokenType.RPAREN, ")", i)
                        i++
                    }

                    c == '=' -> {
                        tokens += Token(TokenType.EQUALS, "=", i)
                        i++
                    }

                    c == '"' -> {
                        val end = input.indexOf('"', i + 1)
                        require(end >= 0) { "Invalid filter: unterminated string starting at position $i" }
                        tokens += Token(TokenType.STRING, input.substring(i + 1, end), i)
                        i = end + 1
                    }

                    isIdentChar(c) -> {
                        val start = i
                        while (i < input.length && isIdentChar(input[i])) i++
                        tokens += Token(TokenType.IDENT, input.substring(start, i), start)
                    }

                    else -> {
                        throw IllegalArgumentException("Invalid filter: unexpected character '$c' at position $i")
                    }
                }
            }
            return tokens
        }
    }
}
