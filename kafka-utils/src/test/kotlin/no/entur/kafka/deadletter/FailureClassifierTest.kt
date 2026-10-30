package no.entur.kafka.deadletter

import com.google.protobuf.InvalidProtocolBufferException
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.Test
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.messaging.converter.MessageConversionException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.ExecutionException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureClassifierTest {
    private val shared = FailureClassifier()

    @Test
    fun `bytes that do not decode are permanent`() {
        assertTrue(shared.isPermanent(undecodable(IllegalArgumentException("Unknown magic byte!"))))
        assertTrue(shared.isPermanent(undecodable(EOFException())))
        assertTrue(shared.isPermanent(undecodable(InvalidProtocolBufferException("truncated"))))
    }

    @Test
    fun `an EOFException outside decoding is a dropped stream, not a bad record`() {
        assertFalse(shared.isPermanent(RuntimeException("download failed", EOFException())))
    }

    @Test
    fun `a schema id the registry does not have is permanent, from a real or a mock registry`() {
        assertTrue(shared.isPermanent(undecodable(RestClientException("Schema not found", 404, 40403))))
        assertTrue(shared.isPermanent(undecodable(RestClientException("Subject not found", 404, 40401))))
    }

    @Test
    fun `a registry that cannot be asked is transient, though it surfaces as a deserialization failure`() {
        assertFalse(shared.isPermanent(undecodable(ConnectException("Connection refused"))))
        assertFalse(shared.isPermanent(undecodable(RestClientException("Internal Server Error", 500, 50001))))
        assertFalse(shared.isPermanent(deserialization(TimeoutException("registry timed out", IOException()))))
        assertFalse(shared.isPermanent(deserialization(AuthorizationException("denied", RestClientException("denied", 403, 40301)))))
    }

    @Test
    fun `a record the listener cannot be invoked with is permanent, whatever it wraps`() {
        assertTrue(shared.isPermanent(listenerFailure(MessageConversionException("Payload must not be empty"))))
        assertTrue(shared.isPermanent(listenerFailure(ClassCastException("not a ValidationDelivery"))))
        assertTrue(shared.isPermanent(MessageConversionException("cannot convert", IOException("connection reset"))))
    }

    @Test
    fun `a failed publish or anything unrecognised is transient`() {
        val failedPublish =
            ExecutionException(org.springframework.kafka.KafkaException("Send failed", TimeoutException("Expiring 1 record")))

        assertFalse(shared.isPermanent(listenerFailure(failedPublish)))
        assertFalse(shared.isPermanent(listenerFailure(IllegalStateException("connection pool closed"))))
    }

    @Test
    fun `a service override beats every shared verdict, wherever it is in the chain`() {
        val classifier = FailureClassifier(overrides = { if (it is DatasetGoneException) Verdict.PERMANENT else Verdict.NONE })

        assertTrue(classifier.isPermanent(listenerFailure(DatasetGoneException(IOException("404")))))
        assertFalse(shared.isPermanent(listenerFailure(DatasetGoneException(IOException("404")))))

        val lenient = FailureClassifier(overrides = { if (it is MessageConversionException) Verdict.TRANSIENT else Verdict.NONE })
        assertFalse(lenient.isPermanent(listenerFailure(MessageConversionException("not yet"))))

        val retryable = FailureClassifier(overrides = { if (it is DatasetGoneException) Verdict.TRANSIENT else Verdict.NONE })
        assertFalse(retryable.isPermanent(MessageConversionException("wrapped", DatasetGoneException(IOException("503")))))
    }

    @Test
    fun `a service cause is asked before the shared one for the same frame`() {
        val classifier = FailureClassifier(causes = { if (it is ConnectException) Verdict.PERMANENT else Verdict.NONE })

        assertTrue(classifier.isPermanent(listenerFailure(ConnectException("refused"))))
    }

    @Test
    fun `the innermost classified cause wins`() {
        val classifier = FailureClassifier(causes = { if (it is DatasetGoneException) Verdict.TRANSIENT else Verdict.NONE })

        assertTrue(classifier.isPermanent(listenerFailure(DatasetGoneException(InvalidProtocolBufferException("truncated")))))
        assertFalse(classifier.isPermanent(listenerFailure(DatasetGoneException(IllegalStateException()))))
    }

    @Test
    fun `a cause chain that loops back on itself still gets a verdict`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertFalse(shared.isPermanent(first))
    }

    private class DatasetGoneException(
        cause: Throwable,
    ) : RuntimeException(cause)

    private fun listenerFailure(cause: Throwable) = ListenerExecutionFailedException("Listener failed", cause)

    private fun undecodable(cause: Throwable) = deserialization(SerializationException("Error deserializing message for id 1", cause))

    private fun deserialization(cause: Throwable) = DeserializationException("failed", byteArrayOf(0, 0, 0, 0, 1), false, cause)
}
