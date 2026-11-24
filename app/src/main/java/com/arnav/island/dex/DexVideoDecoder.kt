package com.arnav.island.dex

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the scrcpy v4.1 H.264 Annex-B video stream continuously, broadcasting encoded packets to
 * optional PC browser clients ([DexWebServer]) while lazily hardware-decoding onto whichever local
 * [Surface] is attached (External HDMI/Smart View monitor OR on-phone viewer).
 *
 * Crucially, the reader thread NEVER blocks waiting for a local Surface — so headless PC streaming
 * runs at full 60fps with zero local MediaCodec power draw, and hot-plugging an HDMI monitor
 * mid-session initializes MediaCodec immediately from the cached SPS/PPS config packet.
 */
class DexVideoDecoder(
    private val input: InputStream,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onEncodedPacket: (isConfig: Boolean, isKeyFrame: Boolean, ptsUs: Long, data: ByteArray) -> Unit = { _, _, _, _ -> },
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DexVideoDecoder"
        private const val CODEC_ID_H264 = 0x68323634
        private const val FLAG_CONFIG = 1L shl 62
        private const val FLAG_KEY_FRAME = 1L shl 61
        private const val PTS_MASK = (1L shl 61) - 1

        /**
         * Extracts the WebCodecs AVC codec string (`avc1.PPCCLL`) from an Annex-B SPS NAL unit
         * (`00 00 00 01 67 <profile> <compat> <level>`). Falls back to High Profile 4.2.
         */
        internal fun extractAvcCodecString(spsAnnexB: ByteArray): String {
            var i = 0
            while (i + 6 < spsAnnexB.size) {
                val startLen = when {
                    spsAnnexB[i] == 0.toByte() && spsAnnexB[i + 1] == 0.toByte() &&
                        spsAnnexB[i + 2] == 0.toByte() && spsAnnexB[i + 3] == 1.toByte() -> 4
                    spsAnnexB[i] == 0.toByte() && spsAnnexB[i + 1] == 0.toByte() &&
                        spsAnnexB[i + 2] == 1.toByte() -> 3
                    else -> 0
                }
                if (startLen > 0) {
                    val nalIndex = i + startLen
                    if (nalIndex + 3 < spsAnnexB.size && (spsAnnexB[nalIndex].toInt() and 0x1F) == 7) {
                        val profile = spsAnnexB[nalIndex + 1].toInt() and 0xFF
                        val compat = spsAnnexB[nalIndex + 2].toInt() and 0xFF
                        val level = spsAnnexB[nalIndex + 3].toInt() and 0xFF
                        return String.format("avc1.%02x%02x%02x", profile, compat, level)
                    }
                    i += startLen
                } else {
                    i++
                }
            }
            return "avc1.64002a"
        }
    }

    private val codecLock = Any()

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var renderEnabled = false

    @Volatile
    private var running = true

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    @Volatile
    var codecString: String = "avc1.64002a"
        private set

    @Volatile
    var lastConfigPacket: ByteArray? = null
        private set

    @Volatile
    var lastKeyFramePacket: ByteArray? = null
        private set

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var needsReconfigure = false
    private var codecAwaitingKeyFrame = false

    private val readerThread = Thread({ runReader() }, "breeze-dex-video")

    fun start() {
        readerThread.start()
    }

    /** Attaches an output [Surface] (e.g. external monitor or viewer). Safe to call mid-stream. */
    fun setSurface(newSurface: Surface) {
        synchronized(codecLock) {
            surface = newSurface
            renderEnabled = true
            val currentCodec = codec
            if (currentCodec != null) {
                try {
                    currentCodec.setOutputSurface(newSurface)
                } catch (e: Exception) {
                    Log.w(TAG, "setOutputSurface failed, scheduling recreate", e)
                    needsReconfigure = true
                }
            } else if (videoWidth > 0 && videoHeight > 0 && lastConfigPacket != null) {
                needsReconfigure = true
            }
        }
    }

    /** Detaches rendering while keeping the H.264 stream alive for PC clients or future surfaces. */
    fun clearSurface(targetSurface: Surface? = null) {
        synchronized(codecLock) {
            if (targetSurface != null && surface !== targetSurface) return
            renderEnabled = false
            surface = null
            releaseCodecLocked()
        }
    }

    fun stop() {
        running = false
        readerThread.interrupt()
        runCatching { input.close() }
        synchronized(codecLock) {
            renderEnabled = false
            surface = null
            releaseCodecLocked()
        }
    }

    private fun runReader() {
        try {
            val dis = DataInputStream(input)
            val codecId = dis.readInt()
            if (codecId != CODEC_ID_H264) {
                throw IOException("Unexpected codec id 0x${Integer.toHexString(codecId)}")
            }

            val header = ByteArray(12)
            while (running) {
                dis.readFully(header)

                if ((header[0].toInt() and 0x80) != 0) {
                    val width = readInt(header, 4)
                    val height = readInt(header, 8)
                    synchronized(codecLock) {
                        if (videoWidth != 0 && (width != videoWidth || height != videoHeight)) {
                            needsReconfigure = true
                        }
                        videoWidth = width
                        videoHeight = height
                    }
                    onVideoSize(width, height)
                    continue
                }

                val ptsAndFlags = readLong(header, 0)
                val size = readInt(header, 8)
                if (size <= 0 || size > 16 * 1024 * 1024) {
                    throw IOException("Implausible video packet size $size")
                }
                val payload = ByteArray(size)
                dis.readFully(payload)

                val isConfig = (ptsAndFlags and FLAG_CONFIG) != 0L
                val isKeyFrame = (ptsAndFlags and FLAG_KEY_FRAME) != 0L
                val ptsUs = ptsAndFlags and PTS_MASK

                if (isConfig) {
                    lastConfigPacket = payload
                    codecString = extractAvcCodecString(payload)
                } else if (isKeyFrame) {
                    lastKeyFramePacket = payload
                }

                // Forward immediately to WebSocket/PC clients without touching local MediaCodec.
                onEncodedPacket(isConfig, isKeyFrame, ptsUs, payload)

                // Decode locally ONLY when an output Surface (external monitor or phone viewer) is attached.
                decodeIfSurfaceAttached(isConfig, isKeyFrame, ptsUs, payload)
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Video stream ended", e)
                onError("Video stream ended: ${e.message}")
            }
        } finally {
            synchronized(codecLock) { releaseCodecLocked() }
        }
    }

    private fun decodeIfSurfaceAttached(
        isConfig: Boolean,
        isKeyFrame: Boolean,
        ptsUs: Long,
        payload: ByteArray,
    ) {
        synchronized(codecLock) {
            val activeSurface = surface
            if (activeSurface == null || !activeSurface.isValid || videoWidth <= 0 || videoHeight <= 0) {
                return
            }

            if (codec == null || needsReconfigure) {
                val cfg = if (isConfig) payload else lastConfigPacket
                if (cfg == null) return
                recreateCodecLocked(activeSurface)
                needsReconfigure = false
                submitLocked(cfg, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                if (isConfig) {
                    codecAwaitingKeyFrame = false
                    return
                }
                // Attached mid-stream: feed cached keyframe if current frame is a delta frame.
                if (!isKeyFrame) {
                    val kf = lastKeyFramePacket
                    if (kf != null) {
                        submitLocked(kf, ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        codecAwaitingKeyFrame = false
                    } else {
                        codecAwaitingKeyFrame = true
                        return
                    }
                }
            }

            if (isConfig) {
                submitLocked(payload, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                return
            }

            if (codecAwaitingKeyFrame) {
                if (!isKeyFrame) return
                codecAwaitingKeyFrame = false
            }

            submitLocked(
                payload,
                ptsUs,
                if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
        }
    }

    private fun recreateCodecLocked(targetSurface: Surface) {
        releaseCodecLocked()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.configure(format, targetSurface, null, 0)
            newCodec.start()
            codec = newCodec
            outputThread = Thread({ drainOutput(newCodec) }, "breeze-dex-video-out").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not create local MediaCodec for surface", e)
            codec = null
        }
    }

    private fun submitLocked(data: ByteArray, ptsUs: Long, flags: Int) {
        val currentCodec = codec ?: return
        try {
            var attempts = 0
            while (running && attempts < 20) {
                val index = currentCodec.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val buffer = currentCodec.getInputBuffer(index) ?: return
                    buffer.clear()
                    buffer.put(data)
                    currentCodec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                    return
                }
                attempts++
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaCodec submit failed, will reconfigure", e)
            needsReconfigure = true
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && this.codec === codec) {
                val index = codec.dequeueOutputBuffer(info, 50_000)
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, renderEnabled)
                }
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun releaseCodecLocked() {
        val oldCodec = codec
        codec = null
        outputThread?.interrupt()
        outputThread = null
        if (oldCodec != null) {
            runCatching { oldCodec.stop() }
            runCatching { oldCodec.release() }
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun readLong(data: ByteArray, offset: Int): Long =
        (readInt(data, offset).toLong() shl 32) or
            (readInt(data, offset + 4).toLong() and 0xffffffffL)
}
