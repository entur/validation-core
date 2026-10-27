package no.entur.api.http

import org.springframework.http.MediaType

/**
 * Picks which of several representations to serve for a raw `Accept` header value - domain-agnostic,
 * so any endpoint offering more than one representation of the same resource can reuse it.
 */
object ContentNegotiationSupport {
    /**
     * Returns the candidate with the highest effective quality (its preference order is the
     * default, and the tie-break, when [accept] is missing, blank, unparseable, or gives every
     * candidate the same quality) - `0` for a candidate no range in [accept] matches at all. A
     * candidate's effective quality is read off the *most specific* range in [accept] that matches
     * it, per RFC 7231 section 5.3.2, rather than its highest-quality match: a specific `;q=0` exclusion
     * (e.g. `application/yaml;q=0`) must win over a broader "any type" wildcard that would otherwise
     * also match and carry a higher quality (e.g. `q=1`) - collapsing to "any q=0 entry, anywhere in
     * the header, is simply removed" would let that wildcard resurrect the excluded candidate.
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
            .minByOrNull { specificityRank(it) }
            ?.qualityValue
            ?: 0.0

    // Lower ranks first: a concrete "type/subtype" is more specific than "type/*", which is more
    // specific than the "*/*" wildcard.
    private fun specificityRank(mediaType: MediaType): Int =
        when {
            mediaType.type == "*" -> 2
            mediaType.subtype == "*" -> 1
            else -> 0
        }
}
