package no.entur.api.http

import org.springframework.http.MediaType

/**
 * Picks which of several representations to serve for a raw `Accept` header value.
 */
object ContentNegotiationSupport {
    /**
     * Returns the first of [candidates] with the highest effective quality from parsing [accept] as an "Accept" header.
     * A candidate's effective quality is read off the *most specific* range in [accept] that matches it (per RFC 7231
     * section 5.3.2). A specific `;q=0` exclusion (e.g. `application/yaml;q=0`) wins over a broader
     * "any type" wildcard (e.g. `application/\*;q=1`).
     *
     * If [candidates] is empty, throws [IllegalArgumentException].
     *
     * If [accept] is null, blank or unparseable, returns the first of the candidates.
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

    /** The quality of the most specific of [acceptedRanges] compatible with [candidate] - `0` if none is. */
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
            }
            ?.qualityValue
            ?: 0.0
}
