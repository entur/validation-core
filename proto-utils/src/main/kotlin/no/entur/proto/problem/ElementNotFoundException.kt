package no.entur.proto.problem

/**
 * An exception which is thrown if a requested element is not found. It will be converted to 404 if encountered during
 * a REST call.
 */
class ElementNotFoundException(
    message: String?,
) : RuntimeException(message)
