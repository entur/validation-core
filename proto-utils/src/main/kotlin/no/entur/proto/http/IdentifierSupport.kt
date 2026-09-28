package no.entur.proto.http

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import no.entur.proto.reflect.ResourceFields

/**
 * Validates a resource body's own `IDENTIFIER` field against the URL path id it was addressed by,
 * for `Update*` endpoints.
 */
object IdentifierSupport {
    /**
     * If [resource]'s `IDENTIFIER` field is set to a non-default value that differs from [pathId],
     * rejects the request (400) - the concrete "id checked vs url" behavior the proto docs promise
     * for `Update*`. A zero/empty value is fine, and a message with no `IDENTIFIER` field is a no-op. Which
     * field that is - resolved via [ResourceFields.forDescriptor] - decides whether [pathId] is compared as a number
     * or a `String`.
     */
    fun <T : Message> requireIdentifierMatchesPath(
        resource: T,
        pathId: Any,
    ) {
        val identifierField = ResourceFields.forDescriptor(resource.descriptorForType)?.idField ?: return
        val value = resource.getField(identifierField)
        val matches =
            if (identifierField.javaType == FieldDescriptor.JavaType.STRING) {
                (value as String).isEmpty() || value == pathId
            } else {
                (value as Number).toInt().let { it == 0 || it == pathId }
            }
        require(matches) {
            "'${identifierField.name}' ($value) does not match the path id ($pathId)"
        }
    }
}
