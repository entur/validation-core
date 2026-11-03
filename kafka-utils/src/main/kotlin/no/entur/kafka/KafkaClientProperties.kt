package no.entur.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import org.apache.kafka.clients.CommonClientConfigs

/**
 * The bootstrap-server, security-protocol and schema-registry properties every Kafka client in a service shares, plus
 * [KafkaSasl] credentials when `auth` is set. A null `auth` is a local, unauthenticated run.
 */
object KafkaClientProperties {
    fun common(
        bootstrapServers: String,
        securityProtocol: String,
        schemaRegistry: String,
        auth: Authentication?,
    ): Map<String, Any> {
        val base =
            mapOf(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to securityProtocol,
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistry,
            )
        return if (auth != null) {
            base + KafkaSasl.brokerProperties(auth) + KafkaSasl.schemaRegistryProperties(auth)
        } else {
            base
        }
    }
}
