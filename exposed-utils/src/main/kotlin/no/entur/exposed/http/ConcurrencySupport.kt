package no.entur.exposed.http

import com.google.protobuf.Message
import com.google.protobuf.Timestamp
import no.entur.proto.reflect.ResourceFields
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.append
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** Wraps a single-resource response with its `ETag` header. */
fun <T : Message> T.withETag(status: HttpStatus = HttpStatus.OK): ResponseEntity<T> =
    ResponseEntity
        .status(status)
        .eTag(requireNotNull(ConcurrencySupport.etag(this)) { "${javaClass.simpleName} has no ETag-eligible id/updated_at fields" })
        .body(this)

/**
 * The `ETag` of a resource with an `IDENTIFIER` id and `OUTPUT_ONLY` `updated_at`: quoted MD5 of
 * `"<id>:<epoch-micros>"`. The JVM and SQL forms must agree bit-for-bit, so the `If-Match` check and
 * the write can be one atomic statement. Needs `updated_at` as `TIMESTAMPTZ` (microsecond precision).
 */
object ConcurrencySupport {
    private val HEX_FORMAT: HexFormat = HexFormat.of()

    /** Returns a strong, quoted `ETag` for [id] and [updatedAt]. */
    fun etag(
        id: Any,
        updatedAt: Instant,
    ): String {
        val micros = updatedAt.epochSecond * 1_000_000L + updatedAt.nano / 1000
        val digest = MessageDigest.getInstance("MD5").digest("$id:$micros".toByteArray(Charsets.UTF_8))
        return "\"${HEX_FORMAT.formatHex(digest)}\""
    }

    /** [etag] with the fields found by [ResourceFields]; `null` if [message] has no such fields. */
    fun etag(message: Message): String? {
        val fields = ResourceFields.forDescriptor(message.descriptorForType) ?: return null
        val updatedAt = message.getField(fields.updatedAtField) as Timestamp
        val instant = Instant.ofEpochSecond(updatedAt.seconds, updatedAt.nanos.toLong())
        return etag(message.getField(fields.idField), instant)
    }

    /** [etag] as SQL, for a conditional `UPDATE`/`DELETE`'s `WHERE` clause. */
    fun etagSqlExpression(
        idColumn: Expression<*>,
        updatedAtColumn: Expression<*>,
    ): ExpressionWithColumnType<String> = EtagHashExpression(idColumn, updatedAtColumn)

    /** `('"' || md5(id::text || ':' || (date_part('epoch', updated_at) * 1000000)::bigint::text) || '"'). */
    private class EtagHashExpression(
        private val id: Expression<*>,
        private val updatedAt: Expression<*>,
    ) : Function<String>(TextColumnType()) {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append(
                "('\"' || md5(",
                id,
                "::text || ':' || (date_part('epoch', ",
                updatedAt,
                ") * 1000000)::bigint::text) || '\"')",
            )
        }
    }
}
