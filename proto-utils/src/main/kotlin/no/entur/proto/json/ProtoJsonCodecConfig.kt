package no.entur.proto.json

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.web.reactive.config.WebFluxConfigurer

/**
 * Registers [ProtoJsonEncoder]/[ProtoJsonDecoder] as custom codecs, so generated proto
 * message request/response bodies are (de)serialized via protobuf-java-util's `JsonFormat`.
 */
@AutoConfiguration
class ProtoJsonCodecConfig : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.customCodecs().register(ProtoJsonEncoder())
        configurer.customCodecs().register(ProtoJsonDecoder())
    }
}
