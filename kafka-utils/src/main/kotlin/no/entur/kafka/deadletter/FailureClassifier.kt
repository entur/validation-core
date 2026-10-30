package no.entur.kafka.deadletter

import com.google.protobuf.InvalidProtocolBufferException
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.SerializationException
import org.springframework.kafka.support.converter.ConversionException
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.messaging.converter.MessageConversionException
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException
import java.io.EOFException
import java.io.IOException

/** A [FailureClassifier] rule's verdict on one exception in a cause chain; [NONE] leaves it to the next rule. */
enum class Verdict { PERMANENT, TRANSIENT, NONE }

/**
 * Whether a listener failure is a property of the record, so it fails again however often it is
 * redelivered ([Verdict.PERMANENT]), or of the moment ([Verdict.TRANSIENT]). Anything unrecognised is
 * transient: a wrong guess that way stalls the partition, visible as consumer lag, instead of dropping
 * a record.
 *
 * [overrides] hold whatever the exception wraps: the outermost service override wins, then the
 * outermost shared one. Otherwise the innermost cause with a verdict wins, asking [causes] before the
 * shared rules, since the outer frames only say where the failure surfaced.
 */
class FailureClassifier(
    private val overrides: (Throwable) -> Verdict = { Verdict.NONE },
    private val causes: (Throwable) -> Verdict = { Verdict.NONE },
) {
    fun isPermanent(exception: Throwable): Boolean {
        val chain =
            generateSequence(exception) { current -> current.cause?.takeIf { it !== current } }
                .take(MAX_CAUSE_DEPTH)
                .toList()
        val decoding = chain.any { it is DeserializationException }
        val verdict =
            chain.firstDecided(overrides)
                ?: chain.firstDecided(::sharedOverride)
                ?: chain.mapNotNull { causes(it).decided() ?: sharedCause(it, decoding).decided() }.lastOrNull()
        return verdict == Verdict.PERMANENT
    }

    companion object {
        internal const val MAX_CAUSE_DEPTH = 20

        /** Confluent's error codes for a subject or schema id the registry does not know. */
        private val SCHEMA_NOT_FOUND = setOf(40401, 40403)

        private fun List<Throwable>.firstDecided(rule: (Throwable) -> Verdict): Verdict? = firstNotNullOfOrNull { rule(it).decided() }

        private fun Verdict.decided(): Verdict? = takeUnless { it == Verdict.NONE }

        private fun sharedOverride(exception: Throwable): Verdict =
            when (exception) {
                // Spring's default fatal exceptions, which the error handler no longer short-circuits:
                // a record the listener method cannot be invoked with.
                is MessageConversionException, is ConversionException, is MethodArgumentResolutionException,
                is NoSuchMethodException, is ClassCastException,
                -> Verdict.PERMANENT

                else -> Verdict.NONE
            }

        private fun sharedCause(
            exception: Throwable,
            decoding: Boolean,
        ): Verdict =
            when (exception) {
                // Also what a registry that could not be asked surfaces as, so a cause below usually decides.
                is DeserializationException -> Verdict.PERMANENT

                // A wrapper the Confluent deserializers put around both bad bytes and registry I/O.
                is SerializationException -> Verdict.NONE

                // Truncated bytes while decoding; elsewhere, such as a dropped download, a plain I/O failure.
                is EOFException if decoding -> Verdict.PERMANENT

                // Ahead of IOException, which it extends, as does EOFException.
                is InvalidProtocolBufferException -> Verdict.PERMANENT

                // The record's schema id is not in the registry: a real one answers 40403, the mock 40401.
                is RestClientException if exception.errorCode in SCHEMA_NOT_FOUND -> Verdict.PERMANENT

                is RestClientException -> Verdict.TRANSIENT

                is KafkaException -> Verdict.TRANSIENT

                is IOException -> Verdict.TRANSIENT

                else -> Verdict.NONE
            }
    }
}
