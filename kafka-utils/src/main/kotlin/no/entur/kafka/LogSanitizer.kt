package no.entur.kafka

/** Replaces line breaks and control characters so an untrusted record value can't forge log lines (CWE-117). */
fun sanitizeForLog(value: String): String {
    if (value.none(::isUnsafeLogChar)) return value
    return value.map { ch -> if (isUnsafeLogChar(ch)) '_' else ch }.joinToString("")
}

/** [LINE_SEPARATOR]/[PARAGRAPH_SEPARATOR]: not [Char.isISOControl], but still a line break to a log viewer. */
private fun isUnsafeLogChar(ch: Char): Boolean = ch.isISOControl() || ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR

private val LINE_SEPARATOR = Char(0x2028)
private val PARAGRAPH_SEPARATOR = Char(0x2029)
