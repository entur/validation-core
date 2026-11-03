package no.entur.proto.http

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import no.entur.proto.reflect.ResourceFields

/** Checks a resource body's `IDENTIFIER` against the path id it was addressed by, for `Update*` endpoints. */
object IdentifierSupport {
    /**
     * Checks that a body `IDENTIFIER` matches [pathId]. An unset id passes, and so does any message that
     * isn't a [ResourceFields] resource.
     *
     * @throws IllegalArgumentException (a 400) if the id is set and differs from [pathId].
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
                (value as Number).toLong().let { it == 0L || it == (pathId as Number).toLong() }
            }
        require(matches) {
            "'${identifierField.name}' ($value) does not match the path id ($pathId)"
        }
    }
}
