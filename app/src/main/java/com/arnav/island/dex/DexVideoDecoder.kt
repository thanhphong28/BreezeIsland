package com.arnav.island.dex

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * Decodes the scrcpy v4.1 H.264 video stream straight onto the viewer's [Surface] with minimal
 * latency (ported from sam1am/localdex).
 */
class DexVideoDecoder(
    private val input: InputStream,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DexVideoDecoder"
        private const val CODEC_ID_H264 = 0x68323634
        private const val FLAG_CONFIG = 1L shl 62
        private const val PTS_MASK = (1L shl 61) - 1
    }

    @Volatile
    private var surface: Surface? = null
    private val surfaceLatch = CountDownLatch(1)

    @Volatile
    private var renderEnabled = false

    @Volatile
    private var running = true

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null

    private var videoWidth = 0
    private var videoHeight = 0
    private var needsReconfigure = false

    private val readerThread = Thread({ runReader() }, "breeze-dex-video")

    fun start() {
        readerThread.start()
    }

    fun setSurface(surface: Surface) {
        this.surface = surface
        val currentCodec = codec
        if (currentCodec != null) {
            try {
                currentCodec.setOutputSurface(surface)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not switch output surface", e)
            }
        }
        renderEnabled = true
        surfaceLatch.countDown()
    }

    fun clearSurface() {
        renderEnabled = false
    }

    fun stop() {
        running = false
        surfaceLatch.countDown()
        readerThread.interrupt()
        runCatching { input.close() }
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
                    if (videoWidth != 0 && (width != videoWidth || height != videoHeight)) {
                        needsReconfigure = true
                    }
                    videoWidth = width
                    videoHeight = height
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
                if (isConfig) {
                    surfaceLatch.await()
                    if (!running || surface == null) break
                    if (codec == null || needsReconfigure) {
                        recreateCodec()
                        needsReconfigure = false
                    }
                    submit(payload, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                } else if (codec != null) {
                    submit(payload, ptsAndFlags and PTS_MASK, 0)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Video stream ended", e)
                onError("Video stream ended: ${e.message}")
            }
        } finally {
            releaseCodec()
        }
    }

    private fun recreateCodec() {
        releaseCodec()
        val targetSurface = surface ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
        val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, targetSurface, null, 0)
        newCodec.start()
        codec = newCodec
        outputThread = Thread({ drainOutput(newCodec) }, "breeze-dex-video-out").also { it.start() }
    }

    private fun submit(data: ByteArray, ptsUs: Long, flags: Int) {
        val currentCodec = codec ?: return
        while (running) {
            val index = currentCodec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = currentCodec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(data)
                currentCodec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                return
            }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 50_000)
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, renderEnabled)
                }
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun releaseCodec() {
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
