package no.entur.api.http

import org.springframework.http.MediaType

/** Picks which of several representations to serve for a raw `Accept` header value. */
object ContentNegotiationSupport {
    /**
     * The first of [candidates] with the highest quality in [accept], each quality taken from the most specific
     * matching range (RFC 7231 5.3.2), so `application/yaml;q=0` beats `application/\*`. A missing or
     * unparseable [accept] gets the first candidate.
     *
     * @throws IllegalArgumentException if [candidates] is empty.
     */
    fun bestMatch(
        accept: String?,
        vararg candidates: MediaType,
    ): MediaType {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        if (accept.isNullOrBlank()) return candidates.first()
        val acceptedRanges =
            try {
                MediaType.parseMediaTypes(accept)
            } catch (_: IllegalArgumentException) {
                return candidates.first()
            }
        return candidates
            .map { it to effectiveQuality(it, acceptedRanges) }
            .filter { (_, quality) -> quality > 0.0 }
            .maxByOrNull { (_, quality) -> quality }
            ?.first
            ?: candidates.first()
    }

    /** The quality of the most specific of [acceptedRanges] matching [candidate], or `0` if none matches. */
    private fun effectiveQuality(
        candidate: MediaType,
        acceptedRanges: List<MediaType>,
    ): Double =
        acceptedRanges
            .filter { candidate.isCompatibleWith(it) }
            .minByOrNull {
                when {
                    it.type == "*" -> 2
                    it.subtype == "*" -> 1
                    else -> 0
                }
            }?.qualityValue
            ?: 0.0
}
