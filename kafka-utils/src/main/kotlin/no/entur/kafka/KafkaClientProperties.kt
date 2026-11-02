package no.entur.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import org.apache.kafka.clients.CommonClientConfigs

/**
 * The broker/registry endpoints and security protocol every Kafka client in a service shares, plus
 * [KafkaSasl] credentials when [user] is set. A blank [user] is a local, unauthenticated run - never
 * attempted as an empty-username SASL handshake.
 */
object KafkaClientProperties {
    fun common(
        bootstrapServers: String,
        securityProtocol: String,
        schemaRegistry: String,
        user: String,
        password: String,
    ): Map<String, Any> {
        val base =
            mapOf(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to securityProtocol,
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistry,
            )
        return if (user.isNotBlank()) {
            base + KafkaSasl.brokerProperties(user, password) + KafkaSasl.schemaRegistryProperties(user, password)
        } else {
            base
        }
    }
}
