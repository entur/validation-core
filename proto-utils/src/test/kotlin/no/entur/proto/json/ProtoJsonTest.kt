package no.entur.proto.json

import com.google.protobuf.Message
import no.entur.proto.testfixtures.v1.Part
import no.entur.proto.testfixtures.v1.Widget
import no.entur.proto.testfixtures.v1.WidgetStatus
import no.entur.proto.testfixtures.v1.part
import no.entur.proto.testfixtures.v1.widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.ResolvableType
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.MediaType
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

internal class ProtoJsonTest {
    private val encoder = ProtoJsonEncoder()
    private val decoder = ProtoJsonDecoder()
    private val bufferFactory = DefaultDataBufferFactory()

    @Test
    fun `canEncode-canDecode are true for a protobuf message type, including problem+json, and false for a plain class`() {
        assertTrue(encoder.canEncode(ResolvableType.forClass(Widget::class.java), MediaType.APPLICATION_JSON))
        assertTrue(encoder.canEncode(ResolvableType.forClass(Widget::class.java), MediaType.APPLICATION_PROBLEM_JSON))
        assertFalse(encoder.canEncode(ResolvableType.forClass(String::class.java), MediaType.APPLICATION_JSON))

        assertTrue(decoder.canDecode(ResolvableType.forClass(Widget::class.java), MediaType.APPLICATION_JSON))
        assertFalse(decoder.canDecode(ResolvableType.forClass(String::class.java), MediaType.APPLICATION_JSON))
    }

    @Test
    fun `a message with no enum fields round-trips through encode and decode unchanged`() {
        val original = part { label = "widget-part" }

        assertEquals(original, roundTrip(original, Part::class.java))
    }

    @Test
    fun `a message with enum fields round-trips through encode and decode unchanged`() {
        val original =
            widget {
                id = 1
                name = "d"
                status = WidgetStatus.ACTIVE
                parts += part { label = "p" }
            }

        assertEquals(original, roundTrip(original, Widget::class.java))
    }

    @Test
    fun `a lower-case enum literal is rejected`() {
        val json = """{"name": "d", "status": "active"}"""

        assertThrows(DecodingException::class.java) { decode(json, Widget::class.java) }
    }

    @Test
    fun `an unknown field is rejected on decode rather than silently ignored`() {
        assertThrows(DecodingException::class.java) { decode("""{"label": "x", "bogusField": 1}""", Part::class.java) }
    }

    @Test
    fun `malformed JSON raises a DecodingException naming the target type`() {
        val exception = assertThrows(DecodingException::class.java) { decode("{not json", Part::class.java) }
        assertTrue(exception.message!!.contains("Part"))
    }

    private fun <T : Message> roundTrip(
        message: T,
        type: Class<T>,
    ): Message = decode(encode(message, type), type)

    private fun encode(
        message: Message,
        type: Class<out Message>,
    ): String {
        val buffer =
            encoder
                .encode(Mono.just(message), bufferFactory, ResolvableType.forClass(type), MediaType.APPLICATION_JSON, null)
                .blockFirst()!!
        val bytes = ByteArray(buffer.readableByteCount())
        buffer.read(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun decode(
        json: String,
        type: Class<out Message>,
    ): Message =
        decoder
            .decodeToMono(
                Flux.just(bufferFactory.wrap(json.toByteArray(StandardCharsets.UTF_8))),
                ResolvableType.forClass(type),
                MediaType.APPLICATION_JSON,
                null,
            ).block()!!
}
