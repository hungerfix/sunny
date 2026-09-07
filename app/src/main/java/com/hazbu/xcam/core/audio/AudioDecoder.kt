package com.hazbu.xcam.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.hazbu.xcam.data.isNetworkUrl
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the audio track of any media source (local file or network URL) into
 * raw PCM chunks on a background daemon thread and makes the data available via
 * [read].
 *
 * Lifecycle:
 *   start(path) → decoding runs continuously, looping at EOS
 *   read(...)   → pulls PCM from the queue; pads silence if queue is dry
 *   stop()      → terminates the decoder thread and clears state
 */
class AudioDecoder(private val logAction: (String) -> Unit) {

    // ──────────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────────
    private companion object {
        /** Max PCM chunks buffered (each chunk ≈ one codec output buffer ≈ 2–8 KB). */
        const val QUEUE_CAPACITY = 64
        /** How long to wait for the codec before giving up on one iteration. */
        const val CODEC_TIMEOUT_US = 10_000L
        /** Max time to block on queue poll before padding silence. */
        const val QUEUE_POLL_MS = 20L
        /** Max time to block on queue offer (backpressure). */
        const val QUEUE_OFFER_MS = 100L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────
    private val pcmQueue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    @Volatile var isActive = false
        private set
    @Volatile private var isRunning = false

    private var decoderThread: Thread? = null

    /** Partial chunk carried over from the previous [read] call. */
    private var leftover: ByteArray? = null
    private var leftoverPos = 0

    private fun log(msg: String) = logAction("[AUDIO-DECODER] $msg")

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * (Re)starts the decoder for the given [path].
     * Safe to call while already running — stops the previous session first.
     */
    fun start(path: String) {
        stop()
        isRunning = true
        isActive = true
        pcmQueue.clear()
        leftover = null
        leftoverPos = 0

        decoderThread = Thread({
            try {
                decodeLoop(path)
            } catch (e: Throwable) {
                log("Decoder thread fatal: ${e.message}")
            } finally {
                isActive = false
                log("Decoder thread finished")
            }
        }, "xcam-audio-decoder").apply {
            isDaemon = true
            start()
        }
        log("Started -> $path")
    }

    /** Stops the decoder and releases all resources. */
    fun stop() {
        isRunning = false
        isActive = false
        decoderThread?.interrupt()
        decoderThread = null
        pcmQueue.clear()
        leftover = null
        leftoverPos = 0
        log("Stopped")
    }

    /**
     * Fills [dest] from [offsetInBytes] with [sizeInBytes] bytes of decoded PCM.
     *
     * If the queue is temporarily empty (decoder hasn't caught up yet) the
     * remainder of the requested region is filled with silence so the caller
     * always gets exactly [sizeInBytes] bytes back.
     *
     * @return [sizeInBytes] — the caller may treat this like the real AudioRecord
     *         return value.
     */
    fun read(dest: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        var remaining = sizeInBytes
        var writePos = offsetInBytes

        while (remaining > 0) {
            // Refill leftover from queue if exhausted
            if (leftover == null) {
                val chunk = pcmQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                if (chunk == null) break  // queue dry — pad with silence below
                leftover = chunk
                leftoverPos = 0
            }

            val chunk = leftover!!
            val available = chunk.size - leftoverPos
            val toCopy = minOf(available, remaining)
            System.arraycopy(chunk, leftoverPos, dest, writePos, toCopy)
            writePos += toCopy
            remaining -= toCopy
            leftoverPos += toCopy

            if (leftoverPos >= chunk.size) {
                leftover = null
                leftoverPos = 0
            }
        }

        // Silence-pad any remainder (queue was temporarily dry)
        if (remaining > 0) {
            dest.fill(0, writePos, writePos + remaining)
        }
        return sizeInBytes
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal — decode loop
    // ──────────────────────────────────────────────────────────────────────────

    private fun decodeLoop(path: String) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            // ── 1. Open source ────────────────────────────────────────────────
            if (path.isNetworkUrl()) {
                extractor.setDataSource(path, emptyMap<String, String>())
            } else {
                extractor.setDataSource(path)
            }

            // ── 2. Find audio track ───────────────────────────────────────────
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }

            if (audioTrackIndex == null) {
                log("No audio track found in: $path — staying silent")
                isActive = false
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            val channels  = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            log("Audio track: $mime  SR=$sampleRate  CH=$channels")

            // ── 3. Create & start decoder ─────────────────────────────────────
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false

            // ── 4. Main decode loop ───────────────────────────────────────────
            while (isRunning && !Thread.interrupted()) {

                // Feed compressed input to the codec
                if (!inputDone) {
                    val inputIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)

                        if (sampleSize < 0) {
                            // End of stream — loop back to beginning
                            log("EOS reached — looping")
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val loopSize = extractor.readSampleData(inputBuf, 0)
                            if (loopSize < 0) {
                                // Truly empty source
                                decoder.queueInputBuffer(
                                    inputIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIdx, 0, loopSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        } else {
                            decoder.queueInputBuffer(
                                inputIdx, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Read decoded PCM output from the codec
                val outputIdx = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                if (outputIdx >= 0) {
                    val outputBuf = decoder.getOutputBuffer(outputIdx)!!
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (bufferInfo.size > 0 && !isConfig) {
                        val pcm = ByteArray(bufferInfo.size)
                        outputBuf.get(pcm)
                        // Apply backpressure — block if queue is full rather than dropping
                        pcmQueue.offer(pcm, QUEUE_OFFER_MS, TimeUnit.MILLISECONDS)
                    }
                    decoder.releaseOutputBuffer(outputIdx, false)

                    // EOS output flag
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (inputDone) break
                    }
                }
            }

        } catch (e: Throwable) {
            log("decodeLoop error: ${e.message}")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
