package no.entur.proto.json

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import org.reactivestreams.Publisher
import org.springframework.core.ResolvableType
import org.springframework.core.codec.AbstractDecoder
import org.springframework.core.codec.AbstractEncoder
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.util.MimeType
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

private fun isProtoMessage(elementType: ResolvableType): Boolean = Message::class.java.isAssignableFrom(elementType.toClass())

/**
 * WebFlux [org.springframework.core.codec.Encoder] for generated protobuf [Message]s. Every operation writes exactly
 * one [Message]. [encode] fails fast instead rather than emit that. Also declares `application/problem+json` support
 * for exception serialization to a [no.entur.http.proto.v1.ProblemDetail].
 *
 * Empty `repeated` field are omitted (no key at all, rather than `[]`).
 */
class ProtoJsonEncoder : AbstractEncoder<Message>(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON) {
    private val printer: JsonFormat.Printer = JsonFormat.printer()

    override fun canEncode(
        elementType: ResolvableType,
        mimeType: MimeType?,
    ): Boolean = isProtoMessage(elementType) && super.canEncode(elementType, mimeType)

    override fun encode(
        inputStream: Publisher<out Message>,
        bufferFactory: DataBufferFactory,
        elementType: ResolvableType,
        mimeType: MimeType?,
        hints: MutableMap<String, Any>?,
    ): Flux<DataBuffer> =
        Flux
            .from(inputStream)
            .single()
            .map(printer::print)
            .map { json -> json.toByteArray(StandardCharsets.UTF_8) }
            .map(bufferFactory::wrap)
            .flux()
}

/**
 * WebFlux [org.springframework.core.codec.Decoder] for generated protobuf [Message]s.
 * Reads the whole request body before parsing (via [DataBufferUtils.join]).
 *
 * Rejects unknown fields: A client sending a field this message type doesn't have is treated as a malformed
 * request (400) and not silently ignored. We might want to revisit this in the future.
 */
class ProtoJsonDecoder : AbstractDecoder<Message>(MediaType.APPLICATION_JSON) {
    private val parser: JsonFormat.Parser = JsonFormat.parser()

    override fun canDecode(
        elementType: ResolvableType,
        mimeType: MimeType?,
    ): Boolean = isProtoMessage(elementType) && super.canDecode(elementType, mimeType)

    override fun decode(
        inputStream: Publisher<DataBuffer>,
        elementType: ResolvableType,
        mimeType: MimeType?,
        hints: MutableMap<String, Any>?,
    ): Flux<Message> = decodeToMono(inputStream, elementType, mimeType, hints).flux()

    override fun decodeToMono(
        inputStream: Publisher<DataBuffer>,
        elementType: ResolvableType,
        mimeType: MimeType?,
        hints: MutableMap<String, Any>?,
    ): Mono<Message> =
        DataBufferUtils.join(inputStream).map { buffer ->
            try {
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                @Suppress("UNCHECKED_CAST") // type is already checked in [canDecode]
                val builder =
                    (elementType.toClass() as Class<out Message>)
                        .getMethod("newBuilder")
                        .invoke(null) as Message.Builder
                parser.merge(String(bytes, StandardCharsets.UTF_8), builder)
                builder.build()
            } catch (e: InvalidProtocolBufferException) {
                throw DecodingException("Malformed JSON for ${elementType.toClass().simpleName}: ${e.message}", e)
            } finally {
                DataBufferUtils.release(buffer)
            }
        }
}
