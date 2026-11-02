package no.entur.kafka

/**
 * Neutralizes a value read off a Kafka record before it is interpolated into a log message,
 * defending against log injection/forging (CWE-117): a key, header or field value is untrusted
 * input that could otherwise smuggle in newlines or control characters to forge log lines.
 */
fun sanitizeForLog(value: String): String {
    if (value.none(::isUnsafeLogChar)) return value
    return value.map { ch -> if (isUnsafeLogChar(ch)) '_' else ch }.joinToString("")
}

/** [LINE_SEPARATOR]/[PARAGRAPH_SEPARATOR]: not [Char.isISOControl], but still a line break to a log viewer. */
private fun isUnsafeLogChar(ch: Char): Boolean = ch.isISOControl() || ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR

private val LINE_SEPARATOR = Char(0x2028)
private val PARAGRAPH_SEPARATOR = Char(0x2029)
