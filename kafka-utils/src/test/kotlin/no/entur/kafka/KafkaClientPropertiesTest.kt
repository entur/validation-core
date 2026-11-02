package no.entur.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KafkaClientPropertiesTest {
    @Test
    fun `carries the broker, security protocol and schema registry url`() {
        val props =
            KafkaClientProperties.common(
                bootstrapServers = "broker:9092",
                securityProtocol = "PLAINTEXT",
                schemaRegistry = "http://registry:8081",
                null,
            )

        assertEquals("broker:9092", props[CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG])
        assertEquals("PLAINTEXT", props[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG])
        assertEquals("http://registry:8081", props[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG])
    }

    @Test
    fun `a null auth adds no SASL credentials`() {
        val props = KafkaClientProperties.common("broker:9092", "PLAINTEXT", "http://registry:8081", null)

        assertFalse(props.containsKey(SaslConfigs.SASL_JAAS_CONFIG))
    }

    @Test
    fun `an auth adds both broker and schema registry SASL credentials`() {
        val auth = Authentication("alice", "s3cret")
        val props =
            KafkaClientProperties.common("broker:9092", "SASL_SSL", "http://registry:8081", auth)

        val expected =
            mapOf(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to "broker:9092",
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to "SASL_SSL",
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to "http://registry:8081",
            ) + KafkaSasl.brokerProperties(auth) + KafkaSasl.schemaRegistryProperties(auth)
        assertEquals(expected, props)
    }
}
