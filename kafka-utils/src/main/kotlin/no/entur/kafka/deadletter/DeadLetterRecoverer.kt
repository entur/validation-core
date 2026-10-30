package no.entur.kafka.deadletter

import com.google.protobuf.ByteString.copyFrom
import com.google.protobuf.Message
import no.entur.kafka.CORRELATION_ID_HEADER
import no.entur.kafka.correlationId
import no.entur.proto.time.toProtoTimestamp
import no.entur.validation.proto.v1.DeadLetter
import no.entur.validation.proto.v1.DeadLetterKt.consumer
import no.entur.validation.proto.v1.DeadLetterKt.failure
import no.entur.validation.proto.v1.DeadLetterKt.header
import no.entur.validation.proto.v1.DeadLetterKt.record
import no.entur.validation.proto.v1.deadLetter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.record.RecordBatch
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.KafkaUtils
import org.springframework.kafka.support.serializer.DeserializationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.google.protobuf.Any as ProtoAny

/**
 * Publishes a record no attempt can process to [topic] as a [DeadLetter] carrying its value: the
 * protobuf message it decoded to, or else the bytes it was consumed as.
 * Throws if the publish fails or takes longer than [sendTimeout], which keeps the offset uncommitted.
 */
class DeadLetterRecoverer(
    private val template: KafkaOperations<String, DeadLetter>,
    val topic: String,
    private val failingService: String,
    private val sendTimeout: Duration = Duration.ofSeconds(30),
) : ConsumerRecordRecoverer {
    override fun accept(
        record: ConsumerRecord<*, *>,
        exception: Exception,
    ) {
        val deadLetter = toDeadLetter(record, exception)
        // Null partition: Kafka picks one by key, since the source topics and the DLT need not
        // have the same partition count.
        val producerRecord =
            ProducerRecord<String, DeadLetter>(topic, null, deadLetter.keyOrNull(), deadLetter)
        record.headers().correlationId()?.let { producerRecord.headers().add(CORRELATION_ID_HEADER, it.toByteArray()) }
        try {
            template.send(producerRecord).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KafkaException(failedMessage(record), e)
        } catch (e: ExecutionException) {
            throw KafkaException(failedMessage(record), e.cause ?: e)
        } catch (e: TimeoutException) {
            throw KafkaException(failedMessage(record), e)
        }
    }

    /** The envelope [accept] publishes for [record], without publishing it. */
    fun toDeadLetter(
        record: ConsumerRecord<*, *>,
        exception: Throwable?,
    ): DeadLetter =
        deadLetter {
            this.record = recordOf(record, exception)
            consumer =
                consumer {
                    service = failingService
                    group = KafkaUtils.getConsumerGroupId().orEmpty()
                }
            exception?.let { failure = failureOf(it) }
            correlationId = record.headers().correlationId().orEmpty()
            deadLetteredAt = Instant.now().toProtoTimestamp()
        }

    private fun failedMessage(record: ConsumerRecord<*, *>) =
        "Dead-letter publication to $topic failed for ${record.topic()}-${record.partition()}@${record.offset()}"

    private companion object {
        const val MAX_STACKTRACE_CHARS = 16 * 1024

        val INTERNAL_HEADERS =
            setOf(
                RAW_VALUE_HEADER,
                KafkaUtils.KEY_DESERIALIZER_EXCEPTION_HEADER,
                KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER,
            )

        fun recordOf(
            record: ConsumerRecord<*, *>,
            exception: Throwable?,
        ): DeadLetter.Record =
            record {
                topic = record.topic()
                partition = record.partition()
                offset = record.offset()
                if (record.timestamp() != RecordBatch.NO_TIMESTAMP) {
                    timestamp = Instant.ofEpochMilli(record.timestamp()).toProtoTimestamp()
                }
                record.key()?.let { key = it.toString() }
                record
                    .headers()
                    .filterNot { it.key() in INTERNAL_HEADERS }
                    .forEach {
                        headers +=
                            header {
                                key = it.key()
                                it.value()?.let { value = copyFrom(it) }
                            }
                    }
                when (val value = record.value()) {
                    is Message -> message = ProtoAny.pack(value)
                    else -> consumedBytes(record, exception)?.let { rawValue = copyFrom(it) }
                }
            }

        fun consumedBytes(
            record: ConsumerRecord<*, *>,
            exception: Throwable?,
        ): ByteArray? {
            record.headers().lastHeader(RAW_VALUE_HEADER)?.let { return it.value() }
            when (val value = record.value()) {
                is ByteArray -> {
                    return value
                }

                null -> {}

                else -> {
                    error("No raw value on ${record.topic()}-${record.partition()}@${record.offset()} to dead-letter")
                }
            }
            // A value ErrorHandlingDeserializer could not decode is null on the record, not a tombstone.
            return generateSequence(exception) { current -> current.cause?.takeIf { it !== current } }
                .take(FailureClassifier.MAX_CAUSE_DEPTH)
                .filterIsInstance<DeserializationException>()
                .firstOrNull { !it.isKey }
                ?.data
        }

        fun failureOf(exception: Throwable): DeadLetter.Failure {
            var failed = exception
            while (failed is ListenerExecutionFailedException && failed.cause != null) failed = failed.cause!!
            return failure {
                exceptionClass = failed.javaClass.name
                message = failed.message.orEmpty()
                failed.cause?.let { cause ->
                    causeClass = cause.javaClass.name
                    cause.message?.let { causeMessage = it }
                }
                stacktrace = failed.stackTraceToString().take(MAX_STACKTRACE_CHARS)
            }
        }

        fun DeadLetter.keyOrNull(): String? = if (record.hasKey()) record.key else null
    }
}
