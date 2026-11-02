package no.entur.kafka

import org.apache.kafka.common.header.Headers
import org.slf4j.MDC

/** Carries one validation run's id across every message and service it passes through. */
const val CORRELATION_ID_HEADER = "X-Correlation-Id"

const val CLIENT_NAME_HEADER = "ET-Client-Name"

/** What a service that may not mint its own correlation id sends when it was given none. */
const val NOT_SET = "not set"

const val CORRELATION_ID_MDC_KEY = "correlationId"

/** The *sender's* client name while a consumed record is processed, not this service's own. */
const val CLIENT_NAME_MDC_KEY = "clientName"

/**
 * Any header casing is accepted, since producers don't all spell it the same. [NOT_SET] counts as
 * none, so a service that mints ids mints one for it too.
 */
fun Headers.correlationId(): String? = lastValue(CORRELATION_ID_HEADER)?.takeUnless { it == NOT_SET }

fun Headers.clientName(): String? = lastValue(CLIENT_NAME_HEADER)

fun currentCorrelationId(): String? = MDC.get(CORRELATION_ID_MDC_KEY)?.takeUnless { it.isBlank() || it == NOT_SET }

internal fun Headers.lastValue(key: String): String? =
    lastOrNull { it.key().equals(key, ignoreCase = true) }
        ?.value()
        ?.decodeToString()
        ?.takeIf { it.isNotBlank() }

internal fun Headers.replace(
    key: String,
    value: String,
) {
    filter { it.key().equals(key, ignoreCase = true) }.map { it.key() }.toSet().forEach { remove(it) }
    add(key, value.toByteArray())
}
