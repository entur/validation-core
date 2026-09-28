package no.entur.exposed.http

import com.google.protobuf.Message
import com.google.protobuf.Timestamp
import no.entur.proto.problem.PreconditionRequiredException
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

/**
 * Wraps a single-resource response with its `ETag` header. Controllers usually set this explicitly on each
 * single-resource `Get`/`Create`/`Update` response.
 */
fun <T : Message> T.withETag(status: HttpStatus = HttpStatus.OK): ResponseEntity<T> =
    ResponseEntity
        .status(status)
        .eTag(requireNotNull(ConcurrencySupport.etag(this)) { "${javaClass.simpleName} has no ETag-eligible id/updated_at fields" })
        .body(this)

/**
 * Throws a [PreconditionRequiredException] if the argument is null. Otherwise, returns it. Controllers can use this
 * to verify that the required header `If-Match` on `Update`/`Delete` (RFC 7232 §3) is present.
 * See [PreconditionRequiredException]'s KDoc for why `@RequestHeader("If-Match", required = true)` is insufficient.
 */
fun requireIfMatch(ifMatch: String?): String = ifMatch ?: throw PreconditionRequiredException("The If-Match header is required")

/**
 * Computes the one canonical `ETag` hash for any resource shaped like `{ IDENTIFIER id, ..., OUTPUT_ONLY
 * updated_at }`, both as a Java String and as an SQL expression usable inside a SQL `WHERE` clause. Both forms must
 * agree bit-for-bit: a GET's `ETag` has to be valid `If-Match` input for a later write, and the
 * SQL form has to be the same check the JVM would make, so the precondition test and the write
 * itself can be one atomic statement (no read-then-write race).
 *
 * The hash itself is MD5 (built into vanilla Postgres as `md5()`) of `"<id>:<epoch-micros>"`,
 * hex-encoded and quoted as a strong ETag. Microsecond precision matches Postgres `timestamptz`'s
 * own resolution. This relies on `updated_at` being stored as `TIMESTAMPTZ` (with a time zone) so that
 * both `updated_at`'s epoch value and Postgres's `date_part('epoch', ...)` read back the same
 * absolute instant, independent of the JVM's or the database session's default time zone.
 */
object ConcurrencySupport {
    private val HEX_FORMAT: HexFormat = HexFormat.of()

    /** A strong, quoted `ETag` value for a resource with this [id]/[updatedAt]. */
    fun etag(
        id: Any,
        updatedAt: Instant,
    ): String {
        val micros = updatedAt.epochSecond * 1_000_000L + updatedAt.nano / 1000
        val digest = MessageDigest.getInstance("MD5").digest("$id:$micros".toByteArray(Charsets.UTF_8))
        return "\"${HEX_FORMAT.formatHex(digest)}\""
    }

    /**
     * [etag], reading `id`/`updated_at` off [message] reflectively via their `IDENTIFIER`/
     * `OUTPUT_ONLY` field behavior (not by field name) - `null` if [message] doesn't declare both.
     * Which fields those are is resolved once per message [com.google.protobuf.Descriptors.Descriptor]
     * and cached in [no.entur.proto.reflect.ResourceFields], rather than re-checked on every call.
     */
    fun etag(message: Message): String? {
        val fields = ResourceFields.forDescriptor(message.descriptorForType) ?: return null
        val updatedAt = message.getField(fields.updatedAtField) as Timestamp
        val instant = Instant.ofEpochSecond(updatedAt.seconds, updatedAt.nanos.toLong())
        return etag(message.getField(fields.idField), instant)
    }

    /** [etag] expressed as a SQL fragment, for a repository's conditional `UPDATE`/`DELETE` `WHERE` clause. */
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
