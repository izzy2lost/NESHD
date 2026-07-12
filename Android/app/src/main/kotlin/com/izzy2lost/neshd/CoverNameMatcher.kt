package com.izzy2lost.neshd

import java.text.Normalizer
import java.util.Locale

/** Title normalization and guarded fallback matching for cover manifests. */
object CoverNameMatcher {
    private val romExtensions = setOf(
        ".nes", ".fds", ".unif", ".unf", ".nsf", ".nsfe", ".studybox", ".qd"
    )

    fun indexCandidates(rawName: String): LinkedHashSet<String> {
        val base = nameWithoutExtension(rawName)
        val stripped = stripDecorations(base)
        return normalizedCandidates(
            linkedSetOf(base, stripped, moveTrailingArticle(base), moveTrailingArticle(stripped))
        )
    }

    fun lookupCandidates(rawName: String): LinkedHashSet<String> {
        val base = nameWithoutExtension(rawName)
        val stripped = stripDecorations(base)
        return normalizedCandidates(
            linkedSetOf(
                base,
                stripped,
                moveTrailingArticle(base),
                moveTrailingArticle(stripped),
                stripped.substringBefore(" - ").trim(),
                stripped.substringBefore(":").trim()
            )
        )
    }

    /**
     * Handles cover-set branding such as "Disney's" without making short or generic titles fuzzy.
     * Exact matching should always be attempted before this fallback.
     */
    fun findContainedTitleMatch(
        candidates: Iterable<String>,
        index: Map<String, String>
    ): String? {
        var bestPath: String? = null
        var bestScore = 0.0
        var ambiguous = false

        for (candidate in candidates) {
            if (candidate.length < MIN_FUZZY_LENGTH) continue
            for ((indexedTitle, path) in index) {
                if (indexedTitle.length < MIN_FUZZY_LENGTH) continue
                val shorterLength: Int
                val longerLength: Int
                val contains = if (candidate.length <= indexedTitle.length) {
                    shorterLength = candidate.length
                    longerLength = indexedTitle.length
                    indexedTitle.contains(candidate)
                } else {
                    shorterLength = indexedTitle.length
                    longerLength = candidate.length
                    candidate.contains(indexedTitle)
                }
                if (!contains) continue

                val score = shorterLength.toDouble() / longerLength
                if (score < MIN_CONTAINMENT_SCORE) continue
                when {
                    score > bestScore -> {
                        bestScore = score
                        bestPath = path
                        ambiguous = false
                    }
                    score == bestScore && path != bestPath -> ambiguous = true
                }
            }
        }
        return bestPath?.takeUnless { ambiguous }
    }

    fun normalizeKey(input: String): String {
        val ascii = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return ascii
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace("+", " ")
            .replace("'", "")
            .replace(Regex("\\b(usa|world|rev|revision|prototype|proto|beta)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), "")
    }

    private fun nameWithoutExtension(name: String): String {
        return romExtensions.fold(name) { acc, ext ->
            if (acc.endsWith(ext, ignoreCase = true)) acc.dropLast(ext.length) else acc
        }
    }

    private fun normalizedCandidates(candidates: Iterable<String>): LinkedHashSet<String> {
        return candidates
            .asSequence()
            .map(::normalizeKey)
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    private fun stripDecorations(name: String): String {
        return name
            .replace('_', ' ')
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^\\)]*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun moveTrailingArticle(name: String): String {
        val match = Regex("""^(.+),\s*(the|a|an)$""", RegexOption.IGNORE_CASE)
            .matchEntire(name.trim()) ?: return name
        return "${match.groupValues[2]} ${match.groupValues[1]}"
    }

    private const val MIN_FUZZY_LENGTH = 8
    private const val MIN_CONTAINMENT_SCORE = 0.72
}
