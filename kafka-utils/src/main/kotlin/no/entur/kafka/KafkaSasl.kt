package no.entur.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import org.apache.kafka.common.config.SaslConfigs

/** SCRAM auth for the broker and the schema registry, both from the same user/password pair. */
object KafkaSasl {
    private const val SASL_MECHANISM = "SCRAM-SHA-512"
    private const val USER_INFO = "USER_INFO"
    private const val JAAS_TEMPLATE =
        "org.apache.kafka.common.security.scram.ScramLoginModule required " +
            "username=\"%s\" password=\"%s\";"

    fun brokerProperties(
        user: String,
        password: String,
    ): Map<String, Any> =
        mapOf(
            SaslConfigs.SASL_MECHANISM to SASL_MECHANISM,
            SaslConfigs.SASL_JAAS_CONFIG to JAAS_TEMPLATE.format(user, password),
        )

    fun schemaRegistryProperties(
        user: String,
        password: String,
    ): Map<String, Any> =
        mapOf(
            SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE to USER_INFO,
            SchemaRegistryClientConfig.USER_INFO_CONFIG to "%s:%s".format(user, password),
        )
}
