package com.movsx.bandsync

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class BeatTrackGenerator(private val context: Context) {
    fun generateFrom(serverAudio: AudioSelection): GeneratedBeatTrack {
        val durationMs = serverAudio.durationMs.coerceAtLeast(1_000L)
        val envelope = decodeEnvelope(serverAudio.uri, min(durationMs, MAX_ANALYSIS_MS))
        val beat = estimateBeat(envelope)
        val output = File(
            context.cacheDir,
            "bandsync-generated-click-${System.currentTimeMillis()}-${beat.bpm}bpm.wav"
        )
        writeClickTrack(output, durationMs, beat)
        return GeneratedBeatTrack(
            file = output,
            bpm = beat.bpm,
            offsetMs = beat.offsetMs,
            durationMs = durationMs
        )
    }

    private fun decodeEnvelope(uri: Uri, analysisDurationMs: Long): FloatArray {
        val bucketCount = (analysisDurationMs / ENVELOPE_FRAME_MS).toInt() + 8
        val sums = FloatArray(bucketCount)
        val counts = IntArray(bucketCount)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r").use { afd ->
                requireNotNull(afd) { "无法打开 Server Output" }
                if (afd.length >= 0L) {
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } else {
                    extractor.setDataSource(afd.fileDescriptor)
                }

                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: error("Server Output 中没有可解码的音频轨道")

                val inputFormat = extractor.getTrackFormat(trackIndex)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: error("无法识别音频格式")
                extractor.selectTrack(trackIndex)

                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }

                val activeCodec = requireNotNull(codec)
                val info = MediaCodec.BufferInfo()
                var outputSampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                var outputChannels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                var sawInputEnd = false
                var sawOutputEnd = false

                while (!sawOutputEnd) {
                    if (!sawInputEnd) {
                        val inputIndex = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                            val sampleSize = if (inputBuffer == null) {
                                -1
                            } else {
                                extractor.readSampleData(inputBuffer, 0)
                            }
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || sampleTimeUs / 1_000L > analysisDurationMs) {
                                activeCodec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEnd = true
                            } else {
                                activeCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = activeCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = activeCodec.outputFormat
                            outputSampleRate = outputFormat.getIntegerOrDefault(
                                MediaFormat.KEY_SAMPLE_RATE,
                                outputSampleRate
                            )
                            outputChannels = outputFormat.getIntegerOrDefault(
                                MediaFormat.KEY_CHANNEL_COUNT,
                                outputChannels
                            )
                            pcmEncoding = outputFormat.getIntegerOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0 && info.presentationTimeUs / 1_000L <= analysisDurationMs) {
                                val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                                if (outputBuffer != null) {
                                    outputBuffer.position(info.offset)
                                    outputBuffer.limit(info.offset + info.size)
                                    addPcmToEnvelope(
                                        outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                        info.presentationTimeUs,
                                        outputSampleRate,
                                        outputChannels.coerceAtLeast(1),
                                        pcmEncoding,
                                        sums,
                                        counts
                                    )
                                }
                            }
                            sawOutputEnd = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            extractor.release()
        }

        return FloatArray(bucketCount) { index ->
            if (counts[index] > 0) sums[index] / counts[index] else 0f
        }
    }

    private fun addPcmToEnvelope(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
        sums: FloatArray,
        counts: IntArray
    ) {
        val bytesPerSample = bytesPerSample(pcmEncoding)
        val frameBytes = bytesPerSample * channelCount
        if (sampleRate <= 0 || frameBytes <= 0) return

        var frameIndex = 0L
        while (buffer.remaining() >= frameBytes) {
            var amplitude = 0f
            repeat(channelCount) {
                amplitude += abs(buffer.readPcmSample(pcmEncoding))
            }
            amplitude /= channelCount.toFloat()

            val timeMs = presentationTimeUs / 1_000L + (frameIndex * 1_000L / sampleRate.toLong())
            val bucket = (timeMs / ENVELOPE_FRAME_MS).toInt()
            if (bucket in sums.indices) {
                sums[bucket] += amplitude
                counts[bucket] += 1
            }
            frameIndex += 1L
        }
    }

    private fun estimateBeat(envelope: FloatArray): BeatEstimate {
        if (envelope.size < 16) return BeatEstimate(DEFAULT_BPM, 0L)

        val smoothed = FloatArray(envelope.size)
        for (index in envelope.indices) {
            var total = 0f
            var count = 0
            for (offset in -2..2) {
                val sampleIndex = index + offset
                if (sampleIndex in envelope.indices) {
                    total += envelope[sampleIndex]
                    count += 1
                }
            }
            smoothed[index] = if (count > 0) total / count else envelope[index]
        }

        val onset = FloatArray(smoothed.size)
        for (index in 1 until smoothed.size) {
            onset[index] = max(0f, smoothed[index] - smoothed[index - 1])
        }

        val onsetEnergy = onset.sum()
        if (onsetEnergy < 0.001f) return BeatEstimate(DEFAULT_BPM, 0L)

        var bestBpm = DEFAULT_BPM
        var bestScore = Double.NEGATIVE_INFINITY
        for (bpm in MIN_BPM..MAX_BPM) {
            val periodFrames = bpmToFrames(bpm)
            if (periodFrames < 2 || periodFrames >= onset.size / 2) continue
            var score = 0.0
            var index = periodFrames
            while (index < onset.size) {
                score += onset[index].toDouble() * onset[index - periodFrames].toDouble()
                index += 1
            }
            score /= onset.size.toDouble()
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpm
            }
        }

        val periodFrames = bpmToFrames(bestBpm).coerceAtLeast(1)
        var bestPhase = 0
        var bestPhaseScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until periodFrames) {
            var score = 0.0
            var index = phase
            while (index < onset.size) {
                score += onset[index].toDouble()
                index += periodFrames
            }
            if (score > bestPhaseScore) {
                bestPhaseScore = score
                bestPhase = phase
            }
        }

        return BeatEstimate(bestBpm, bestPhase * ENVELOPE_FRAME_MS)
    }

    private fun writeClickTrack(file: File, durationMs: Long, beat: BeatEstimate) {
        val sampleRate = CLICK_SAMPLE_RATE
        val totalSamples = ((durationMs * sampleRate) / 1_000L).coerceAtLeast(sampleRate.toLong())
        val dataBytes = totalSamples * BYTES_PER_PCM16_SAMPLE
        val beatIntervalMs = 60_000.0 / beat.bpm.toDouble()
        val maxAmplitude = Short.MAX_VALUE.toDouble()

        FileOutputStream(file).use { output ->
            writeWavHeader(output, sampleRate, dataBytes)
            val buffer = ByteArrayOutputStreamCompat(CLICK_BUFFER_SAMPLES * BYTES_PER_PCM16_SAMPLE)
            var sampleIndex = 0L
            while (sampleIndex < totalSamples) {
                buffer.reset()
                val blockEnd = min(totalSamples, sampleIndex + CLICK_BUFFER_SAMPLES)
                while (sampleIndex < blockEnd) {
                    val timeMs = sampleIndex * 1_000.0 / sampleRate.toDouble()
                    val value = clickSample(timeMs, beat.offsetMs.toDouble(), beatIntervalMs, maxAmplitude)
                    buffer.writeLittleEndianShort(value)
                    sampleIndex += 1L
                }
                output.write(buffer.array, 0, buffer.size)
            }
        }
    }

    private fun clickSample(timeMs: Double, offsetMs: Double, beatIntervalMs: Double, maxAmplitude: Double): Short {
        val beatPosition = (timeMs - offsetMs) / beatIntervalMs
        if (beatPosition < 0.0) return 0
        val beatIndex = beatPosition.toInt()
        val msSinceBeat = timeMs - offsetMs - beatIndex * beatIntervalMs
        val isAccent = beatIndex % 4 == 0
        val clickLengthMs = if (isAccent) 82.0 else 58.0
        if (msSinceBeat !in 0.0..clickLengthMs) return 0

        val seconds = msSinceBeat / 1_000.0
        val frequency = if (isAccent) 1_420.0 else 930.0
        val gain = if (isAccent) 0.78 else 0.52
        val envelope = exp(-seconds * 42.0)
        val sample = sin(2.0 * PI * frequency * seconds) * envelope * gain * maxAmplitude
        return sample.roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun writeWavHeader(output: FileOutputStream, sampleRate: Int, dataBytes: Long) {
        val riffSize = 36L + dataBytes
        output.writeAscii("RIFF")
        output.writeLittleEndianInt(riffSize.coerceAtMost(UInt.MAX_VALUE.toLong()).toInt())
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianInt(sampleRate)
        output.writeLittleEndianInt(sampleRate * BYTES_PER_PCM16_SAMPLE)
        output.writeLittleEndianShort(BYTES_PER_PCM16_SAMPLE)
        output.writeLittleEndianShort(16)
        output.writeAscii("data")
        output.writeLittleEndianInt(dataBytes.coerceAtMost(UInt.MAX_VALUE.toLong()).toInt())
    }

    private fun bpmToFrames(bpm: Int): Int =
        (60_000f / bpm.toFloat() / ENVELOPE_FRAME_MS.toFloat()).roundToInt()

    private fun bytesPerSample(pcmEncoding: Int): Int =
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> 2
        }

    private fun ByteBuffer.readPcmSample(pcmEncoding: Int): Float =
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ((get().toInt() and 0xff) - 128) / 128f
            AudioFormat.ENCODING_PCM_FLOAT -> getFloat().coerceIn(-1f, 1f)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val b0 = get().toInt() and 0xff
                val b1 = get().toInt() and 0xff
                val b2 = get().toInt() and 0xff
                var value = b0 or (b1 shl 8) or (b2 shl 16)
                if ((value and 0x800000) != 0) value = value or -0x1000000
                value / 8_388_608f
            }

            AudioFormat.ENCODING_PCM_32BIT -> getInt() / 2_147_483_648f
            else -> getShort() / 32_768f
        }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private fun FileOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun FileOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun FileOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStreamCompat.writeLittleEndianShort(value: Short) {
        val intValue = value.toInt()
        write(intValue and 0xff)
        write((intValue ushr 8) and 0xff)
    }

    data class GeneratedBeatTrack(
        val file: File,
        val bpm: Int,
        val offsetMs: Long,
        val durationMs: Long
    )

    private data class BeatEstimate(
        val bpm: Int,
        val offsetMs: Long
    )

    private class ByteArrayOutputStreamCompat(initialSize: Int) {
        var array = ByteArray(initialSize)
            private set
        var size = 0
            private set

        fun reset() {
            size = 0
        }

        fun write(value: Int) {
            if (size >= array.size) {
                array = array.copyOf(array.size * 2)
            }
            array[size] = value.toByte()
            size += 1
        }
    }

    private companion object {
        const val DEFAULT_BPM = 120
        const val MIN_BPM = 60
        const val MAX_BPM = 200
        const val MAX_ANALYSIS_MS = 180_000L
        const val ENVELOPE_FRAME_MS = 20L
        const val CLICK_SAMPLE_RATE = 44_100
        const val BYTES_PER_PCM16_SAMPLE = 2
        const val CLICK_BUFFER_SAMPLES = 4_096
        const val CODEC_TIMEOUT_US = 10_000L
    }
}
