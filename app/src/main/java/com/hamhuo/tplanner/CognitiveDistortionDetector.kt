package com.hamhuo.tplanner

// Local keyword rule engine: pure local computation, zero latency, zero network.
// Based on SocialCD-3K dataset + Burns 12 cognitive distortion types with Chinese feature words.
// Used for real-time chip highlight hints in AnxietyInputSheet, not as the final classification result.
object CognitiveDistortionDetector {

    data class DistortionCandidate(
        val type: DistortionType,
        val matchCount: Int,          // Number of matched keywords
        val matchedKeywords: List<String>,
    )

    fun detect(text: String): List<DistortionCandidate> {
        if (text.isBlank()) return emptyList()
        val results = DistortionType.entries.map { type ->
            val matched = type.keywords.filter { keyword ->
                text.contains(keyword)
            }
            DistortionCandidate(
                type = type,
                matchCount = matched.size,
                matchedKeywords = matched,
            )
        }
        // Sort by match count descending, only return if at least one keyword matched
        return results.filter { it.matchCount > 0 }
            .sortedByDescending { it.matchCount }
    }
}
