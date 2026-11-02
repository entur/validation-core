package no.entur.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import org.apache.kafka.common.config.SaslConfigs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaSaslTest {
    private val auth = Authentication("alice", "s3cret")

    @Test
    fun `broker properties carry the SCRAM mechanism and a JAAS config with the given credentials`() {
        val props = KafkaSasl.brokerProperties(auth)

        assertEquals("SCRAM-SHA-512", props[SaslConfigs.SASL_MECHANISM])
        assertEquals(
            "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"alice\" password=\"s3cret\";",
            props[SaslConfigs.SASL_JAAS_CONFIG],
        )
    }

    @Test
    fun `schema registry properties carry basic auth with the colon-joined credentials`() {
        val props = KafkaSasl.schemaRegistryProperties(auth)

        assertEquals("USER_INFO", props[SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE])
        assertEquals("alice:s3cret", props[SchemaRegistryClientConfig.USER_INFO_CONFIG])
    }
}
