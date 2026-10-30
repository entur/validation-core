package no.entur.kafka.deadletter

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.RetryListener
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/** Capped exponential backoff for failures that are retried until they succeed. */
data class RetryPolicy(
    val initialInterval: Duration = Duration.ofSeconds(1),
    val multiplier: Double = 2.0,
    val maxInterval: Duration = Duration.ofSeconds(60),
) {
    internal fun backOff(): ExponentialBackOff =
        ExponentialBackOff(initialInterval.toMillis(), multiplier).apply { maxInterval = this@RetryPolicy.maxInterval.toMillis() }

    /** Capped as a Double: a Long product overflows to negative after ~50 attempts. */
    internal fun interval(failedAttempts: Int): Long =
        (initialInterval.toMillis() * multiplier.pow(failedAttempts - 1))
            .coerceAtMost(maxInterval.toMillis().toDouble())
            .toLong()
}

/** What happens to a permanent failure when there is no [DeadLetterRecoverer]. */
enum class WithoutDeadLetterTopic {
    /** Retried forever like a transient failure: a stalled partition shows as lag, a commit shows nowhere. */
    RETRY,

    /** Logged and committed. */
    LOG_AND_COMMIT,
}

/**
 * Dead-letters permanent failures (see [FailureClassifier]) through [recoverer] and commits them;
 * retries transient ones with [retry] and never commits them. Keeps retry state per partition, so
 * use one instance per container. Committing needs AckMode MANUAL_IMMEDIATE or RECORD.
 */
fun deadLetteringErrorHandler(
    recoverer: DeadLetterRecoverer?,
    classifier: FailureClassifier,
    retry: RetryPolicy = RetryPolicy(),
    withoutDeadLetterTopic: WithoutDeadLetterTopic = WithoutDeadLetterTopic.RETRY,
    logger: Logger = LoggerFactory.getLogger("no.entur.kafka.deadletter.DeadLetterErrorHandler"),
): DefaultErrorHandler {
    val failedDeadLetters = FailedDeadLetters(retry)
    val permanentIsGivenUp = recoverer != null || withoutDeadLetterTopic == WithoutDeadLetterTopic.LOG_AND_COMMIT
    val latestFailures = LatestFailures()
    val handler =
        DefaultErrorHandler(
            { record, exception ->
                if (recoverer != null) {
                    // Before the log line: if publishing throws, the record is not given up on.
                    recoverer.accept(record, exception)
                    logger.error(
                        "Dead-lettered {}-{}@{}, key={} to {}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        sanitizeForLog(record.key()),
                        recoverer.topic,
                        exception,
                    )
                } else {
                    logger.error(
                        "Giving up on {}-{}@{}, key={}; no dead-letter topic is configured",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        sanitizeForLog(record.key()),
                        exception,
                    )
                }
            },
            // Spring decides once, from this BackOff, whether to retry at all: one that stops at once
            // sends every failure to the recoverer without asking the function below.
            retry.backOff(),
        )
    // Spring's default fatal list would skip the function below, dead-lettering a registry outage
    // that surfaces as a DeserializationException and retrying a failed publish with no backoff.
    handler.setClassifications(emptyMap(), true)
    handler.setBackOffFunction { record, exception ->
        val permanent = classifier.isPermanent(exception)
        when {
            !permanent && permanentIsGivenUp -> latestFailures.untilPermanent(record, retry.backOff())
            !permanent -> retry.backOff()
            recoverer != null -> failedDeadLetters.backOffFor(record)
            withoutDeadLetterTopic == WithoutDeadLetterTopic.LOG_AND_COMMIT -> FixedBackOff(0, 0)
            else -> retry.backOff()
        }
    }
    // Otherwise the skip is in-memory only, and a restart before the next ack dead-letters it again.
    handler.setCommitRecovered(true)
    handler.setRetryListeners(
        // Spring logs a retried failure only at DEBUG, so a stalled partition would show nothing but lag.
        RetryListener { record, exception, deliveryAttempt ->
            val permanent = exception != null && classifier.isPermanent(exception)
            latestFailures.record(record, permanent)
            if (permanent && permanentIsGivenUp) {
                return@RetryListener
            }
            logger.warn(
                "Processing {}-{}@{}, key={} failed on attempt {}; will retry{}",
                record.topic(),
                record.partition(),
                record.offset(),
                sanitizeForLog(record.key()),
                deliveryAttempt,
                if (permanent) " (a permanent failure, but no dead-letter topic is configured)" else "",
                exception,
            )
        },
        failedDeadLetters,
    )
    return handler
}

/**
 * Spring keeps a record's first BackOff until the exception's class changes, and a registry outage and
 * an unknown schema id are both a DeserializationException: without this, a record that turns
 * permanent while being retried would be retried forever instead of given up on.
 */
private class LatestFailures {
    private val permanentOffsets = ConcurrentHashMap<TopicPartition, Long>()

    /** Called on every failed delivery, before Spring asks the BackOff for its next interval. */
    fun record(
        record: ConsumerRecord<*, *>,
        permanent: Boolean,
    ) {
        if (permanent) permanentOffsets[record.topicPartition()] = record.offset() else permanentOffsets.remove(record.topicPartition())
    }

    fun untilPermanent(
        record: ConsumerRecord<*, *>,
        backOff: BackOff,
    ) = BackOff {
        val execution = backOff.start()
        BackOffExecution {
            if (permanentOffsets[record.topicPartition()] == record.offset()) BackOffExecution.STOP else execution.nextBackOff()
        }
    }
}

/**
 * Backs off between dead-letter attempts: when the recoverer throws, Spring forgets the record and
 * redelivers it at once, so a missing DLT would otherwise be retried as fast as the consumer polls.
 */
private class FailedDeadLetters(
    private val retry: RetryPolicy,
) : RetryListener {
    private val failures = ConcurrentHashMap<TopicPartition, Pair<Long, Int>>()

    fun backOffFor(record: ConsumerRecord<*, *>): BackOff {
        val (_, attempts) =
            failures[record.topicPartition()]
                ?.takeIf { (offset, _) -> offset == record.offset() }
                ?: return FixedBackOff(0, 0)
        return FixedBackOff(retry.interval(attempts), 1)
    }

    override fun failedDelivery(
        record: ConsumerRecord<*, *>,
        ex: Exception?,
        deliveryAttempt: Int,
    ) = Unit

    override fun recovered(
        record: ConsumerRecord<*, *>,
        ex: Exception?,
    ) {
        failures.remove(record.topicPartition())
    }

    override fun recoveryFailed(
        record: ConsumerRecord<*, *>,
        original: Exception?,
        failure: Exception,
    ) {
        failures.compute(record.topicPartition()) { _, previous ->
            val attempts = previous?.takeIf { it.first == record.offset() }?.second ?: 0
            record.offset() to attempts + 1
        }
    }
}

private fun ConsumerRecord<*, *>.topicPartition() = TopicPartition(topic(), partition())

/** The key is whatever the producer set, so newlines and control characters could forge log lines. */
internal fun sanitizeForLog(value: Any?): String =
    value
        ?.toString()
        .orEmpty()
        .map { if (it.isISOControl() || it == ' ' || it == ' ') '_' else it }
        .joinToString("")
