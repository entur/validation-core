package no.entur.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler
import javax.security.auth.callback.Callback
import javax.security.auth.callback.NameCallback
import javax.security.auth.callback.PasswordCallback
import javax.security.auth.callback.UnsupportedCallbackException
import javax.security.auth.login.AppConfigurationEntry

data class Authentication(
    val user: String,
    val password: String,
) {
    // Redact the password, which the data class's generated toString() would otherwise print.
    override fun toString(): String = "Authentication(user=$user, password=***)"
}

const val AUTHENTICATION_CONFIG = "no.entur.kafka.authentication"

/** SCRAM auth for the broker and the schema registry, both from the same authentication object. */
object KafkaSasl {
    private const val SASL_MECHANISM = "SCRAM-SHA-512"
    private const val USER_INFO = "USER_INFO"
    private const val JAAS_CONFIG = "org.apache.kafka.common.security.scram.ScramLoginModule required;"

    fun brokerProperties(auth: Authentication): Map<String, Any> =
        mapOf(
            SaslConfigs.SASL_MECHANISM to SASL_MECHANISM,
            SaslConfigs.SASL_JAAS_CONFIG to JAAS_CONFIG,
            SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS to AuthenticationCallbackHandler::class.java,
            AUTHENTICATION_CONFIG to auth,
        )

    fun schemaRegistryProperties(auth: Authentication): Map<String, Any> =
        mapOf(
            SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE to USER_INFO,
            SchemaRegistryClientConfig.USER_INFO_CONFIG to "${auth.user}:${auth.password}",
        )
}

/** Hands [Authentication] to the SASL handshake, so `sasl.jaas.config` never contains the credentials. */
class AuthenticationCallbackHandler : AuthenticateCallbackHandler {
    private lateinit var auth: Authentication

    override fun configure(
        configs: Map<String, *>,
        saslMechanism: String,
        jaasConfigEntries: List<AppConfigurationEntry>,
    ) {
        auth = configs[AUTHENTICATION_CONFIG] as Authentication
    }

    override fun handle(callbacks: Array<Callback>) {
        callbacks.forEach { callback ->
            when (callback) {
                is NameCallback -> callback.name = auth.user
                is PasswordCallback -> callback.setPassword(auth.password.toCharArray())
                else -> throw UnsupportedCallbackException(callback)
            }
        }
    }

    override fun close() = Unit
}
