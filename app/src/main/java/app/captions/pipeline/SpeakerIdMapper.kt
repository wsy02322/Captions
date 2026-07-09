package app.captions.pipeline

import app.captions.transcription.TranscriptionWord

/**
 * Maps provider-local speaker IDs to session-stable IDs using overlap word alignment
 * between consecutive chunks. UI still shows colors only — never text labels.
 */
class SpeakerIdMapper {
    private var nextSessionId = 0
    private val localToSession = mutableMapOf<Int, Int>()
    private var previousWords: List<TranscriptionWord> = emptyList()

    fun mapWords(words: List<TranscriptionWord>): List<TranscriptionWord> {
        if (words.isEmpty()) return words
        alignFromOverlap(words)
        val mapped = words.map { word ->
            val sessionId = localToSession.getOrPut(word.speaker) { allocate() }
            word.copy(speaker = sessionId)
        }
        previousWords = mapped
        return mapped
    }

    fun mapSpeaker(localSpeaker: Int): Int =
        localToSession.getOrPut(localSpeaker) { allocate() }

    fun reset() {
        nextSessionId = 0
        localToSession.clear()
        previousWords = emptyList()
    }

    private fun allocate(): Int = nextSessionId++

    private fun alignFromOverlap(current: List<TranscriptionWord>) {
        if (previousWords.isEmpty()) return
        val prevTail = previousWords.takeLast(12)
        val currHead = current.take(12)
        val votes = mutableMapOf<Int, MutableMap<Int, Int>>() // local -> (session -> count)
        for (prev in prevTail) {
            for (curr in currHead) {
                if (normalize(prev.text) == normalize(curr.text) && normalize(prev.text).isNotEmpty()) {
                    val bucket = votes.getOrPut(curr.speaker) { mutableMapOf() }
                    bucket[prev.speaker] = (bucket[prev.speaker] ?: 0) + 1
                }
            }
        }
        for ((local, sessionVotes) in votes) {
            val best = sessionVotes.maxByOrNull { it.value } ?: continue
            if (best.value >= 1) {
                localToSession[local] = best.key
            }
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().trim().trim(',', '.', '!', '?', ':', ';', '"', '\'')
}
