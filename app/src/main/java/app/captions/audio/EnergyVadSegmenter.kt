package app.captions.audio

/**
 * Energy-threshold VAD that emits PCM chunks suitable for batch STT.
 * Target chunk length 15–30 s with optional trailing silence cut; hard cap 45 s.
 */
class EnergyVadSegmenter(
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
    private val targetMs: Int = 20_000,
    private val maxMs: Int = 45_000,
    private val silenceMsToCut: Int = 700,
    private val rmsThreshold: Double = 400.0,
    private val overlapMs: Int = 1_500,
) {
    private val buffer = ArrayList<Byte>()
    private var trailingSilenceMs = 0
    private var hasVoice = false

    fun push(frame: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (frame.isEmpty()) return out
        for (b in frame) buffer.add(b)
        val frameMs = (frame.size * 1000) / (sampleRate * 2)
        val level = rms(frame)
        if (level >= rmsThreshold) {
            hasVoice = true
            trailingSilenceMs = 0
        } else if (hasVoice) {
            trailingSilenceMs += frameMs
        }

        val durationMs = (buffer.size * 1000) / (sampleRate * 2)
        val shouldCut = hasVoice && (
            (trailingSilenceMs >= silenceMsToCut && durationMs >= targetMs / 2) ||
                durationMs >= maxMs ||
                (durationMs >= targetMs && trailingSilenceMs >= silenceMsToCut / 2)
            )
        if (shouldCut) {
            emitChunk()?.let(out::add)
        }
        return out
    }

    fun flush(): ByteArray? = emitChunk(force = true)

    private fun emitChunk(force: Boolean = false): ByteArray? {
        if (!hasVoice && !force) {
            buffer.clear()
            trailingSilenceMs = 0
            return null
        }
        if (buffer.isEmpty()) return null
        val pcm = bytesOf(buffer)
        val overlapBytes = (sampleRate * overlapMs / 1000) * 2
        buffer.clear()
        if (overlapBytes in 1 until pcm.size) {
            val start = pcm.size - overlapBytes
            for (i in start until pcm.size) buffer.add(pcm[i])
        }
        trailingSilenceMs = 0
        hasVoice = buffer.isNotEmpty() && rms(bytesOf(buffer)) >= rmsThreshold
        return pcm
    }

    companion object {
        fun rms(frame: ByteArray): Double {
            if (frame.size < 2) return 0.0
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < frame.size) {
                val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
                val signed = sample.toShort().toInt()
                sum += signed * signed
                count++
                i += 2
            }
            if (count == 0) return 0.0
            return kotlin.math.sqrt(sum / count)
        }

        private fun bytesOf(list: List<Byte>): ByteArray {
            val out = ByteArray(list.size)
            for (i in list.indices) out[i] = list[i]
            return out
        }
    }
}
