package app.captions.pipeline

/**
 * Merges consecutive finals from the same speaker so Deepgram's short
 * utterance finals don't become a spray of tiny bubbles.
 */
object CaptionLineCoalescer {
    const val DEFAULT_GAP_MS = 2_800L
    const val MAX_MERGED_CHARS = 280

    fun shouldMerge(
        previousSpeaker: Int,
        previousText: String,
        previousUpdatedAtMs: Long,
        nextSpeaker: Int,
        nextText: String,
        nextAtMs: Long,
        maxGapMs: Long = DEFAULT_GAP_MS,
    ): Boolean {
        if (previousSpeaker != nextSpeaker) return false
        if (nextAtMs - previousUpdatedAtMs > maxGapMs) return false
        val mergedLen = previousText.length + 1 + nextText.length
        if (mergedLen > MAX_MERGED_CHARS) return false
        // Always allow merge when previous is a short fragment.
        if (previousText.trim().split(Regex("\\s+")).size <= 4) return true
        // Soft sentence end → still merge if gap is small.
        val trimmed = previousText.trimEnd()
        if (trimmed.endsWith('.') || trimmed.endsWith('!') || trimmed.endsWith('?') ||
            trimmed.endsWith('。') || trimmed.endsWith('！') || trimmed.endsWith('？')
        ) {
            return nextAtMs - previousUpdatedAtMs <= maxGapMs / 2
        }
        return true
    }

    fun join(previous: String, next: String): String {
        val left = previous.trimEnd()
        val right = next.trimStart()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val needsSpace = left.last().isLetterOrDigit() && right.first().isLetterOrDigit()
        return if (needsSpace) "$left $right" else "$left$right"
    }
}
