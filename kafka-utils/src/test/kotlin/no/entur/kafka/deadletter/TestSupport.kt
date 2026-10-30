package no.entur.kafka.deadletter

import no.entur.validation.proto.v1.DeadLetter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

const val TOPIC = "validation-delivery-test"
const val DEAD_LETTER_TOPIC = "validation-dlt-test"
const val OFFSET = 42L
val RAW_VALUE = byteArrayOf(0, 0, 0, 0, 7, 0, 10, 3, 82, 85, 84)
val PARTITION = TopicPartition(TOPIC, 0)

class RecordingTemplate(
    var result: CompletableFuture<SendResult<String, DeadLetter>> = sent(),
) : KafkaTemplate<String, DeadLetter>(DefaultKafkaProducerFactory(emptyMap())) {
    val sent = mutableListOf<ProducerRecord<String, DeadLetter>>()

    override fun send(record: ProducerRecord<String, DeadLetter>): CompletableFuture<SendResult<String, DeadLetter>> {
        sent += record
        return result
    }
}

fun sent(): CompletableFuture<SendResult<String, DeadLetter>> =
    CompletableFuture.completedFuture(
        SendResult(
            ProducerRecord(DEAD_LETTER_TOPIC, "RUT", DeadLetter.getDefaultInstance()),
            RecordMetadata(TopicPartition(DEAD_LETTER_TOPIC, 0), 0, 0, 0, 0, 0),
        ),
    )

/** As [RawValueRetainingDeserializer] leaves it: decoded, with its bytes on a header. */
fun rawRecord(): ConsumerRecord<String, Any?> =
    ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT", "decoded").apply {
        headers().add(RAW_VALUE_HEADER, RAW_VALUE)
    }
