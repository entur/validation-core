package no.entur.kafka

import org.apache.kafka.common.security.scram.ScramLoginModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.security.auth.callback.NameCallback
import javax.security.auth.callback.PasswordCallback
import javax.security.auth.callback.TextOutputCallback
import javax.security.auth.callback.UnsupportedCallbackException
import javax.security.sasl.Sasl

class AuthenticationCallbackHandlerTest {
    private val auth = Authentication("alice", "s3cret")
    private val handler =
        AuthenticationCallbackHandler().apply {
            configure(
                mapOf(AuthenticationCallbackHandler.AUTHENTICATION_CONFIG to auth),
                "SCRAM-SHA-512",
                emptyList(),
            )
        }

    @Test
    fun `answers a name callback with the configured user`() {
        val callback = NameCallback("Name:")

        handler.handle(arrayOf(callback))

        assertEquals("alice", callback.name)
    }

    @Test
    fun `answers a password callback with the configured password`() {
        val callback = PasswordCallback("Password:", false)

        handler.handle(arrayOf(callback))

        assertEquals("s3cret", String(callback.password))
    }

    @Test
    fun `rejects a callback it doesn't know how to answer`() {
        assertThrows(UnsupportedCallbackException::class.java) {
            handler.handle(arrayOf(TextOutputCallback(TextOutputCallback.INFORMATION, "hi")))
        }
    }

    /**
     * Not a mock of the contract: a real `javax.security.sasl.SaslClient` for Kafka's own
     * SCRAM-SHA-512 mechanism, run against this handler exactly as `ScramSaslClient` runs it
     * during an actual broker handshake -- proof the username reaches Kafka's real SASL client,
     * not just this handler's own idea of the callback contract.
     */
    @Test
    fun `a real Kafka SCRAM SASL client pulls the username from this handler`() {
        ScramLoginModule() // Registers ScramSaslClientProvider as a side effect of class loading.

        val saslClient =
            checkNotNull(
                Sasl.createSaslClient(arrayOf("SCRAM-SHA-512"), null, "kafka", "localhost", emptyMap<String, Any>(), handler),
            )
        val clientFirstMessage = String(saslClient.evaluateChallenge(ByteArray(0)), Charsets.UTF_8)

        assertTrue(clientFirstMessage.contains(",n=alice,"), clientFirstMessage)
    }
}
