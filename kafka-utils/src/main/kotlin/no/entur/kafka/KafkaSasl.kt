package no.entur.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import org.apache.kafka.common.config.SaslConfigs

data class Authentication(
    val user: String,
    val password: String,
) {
    override fun toString(): String = "%s:%s".format(user, password)
}

/** SCRAM auth for the broker and the schema registry, both from the same authentication object. */
object KafkaSasl {
    private const val SASL_MECHANISM = "SCRAM-SHA-512"
    private const val USER_INFO = "USER_INFO"
    private const val JAAS_TEMPLATE =
        "org.apache.kafka.common.security.scram.ScramLoginModule required " +
            "username=\"%s\" password=\"%s\";"

    fun brokerProperties(auth: Authentication): Map<String, Any> =
        mapOf(
            SaslConfigs.SASL_MECHANISM to SASL_MECHANISM,
            SaslConfigs.SASL_JAAS_CONFIG to JAAS_TEMPLATE.format(auth.user, auth.password),
        )

    fun schemaRegistryProperties(auth: Authentication): Map<String, Any> =
        mapOf(
            SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE to USER_INFO,
            SchemaRegistryClientConfig.USER_INFO_CONFIG to auth.toString(),
        )
}
