package com.arnav.island.dex

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Shizuku UserService (runs in UID 2000 shell process — ZERO root, ZERO AccessibilityService,
 * ZERO Wi-Fi required once Shizuku is active).
 *
 * Launches the bundled scrcpy-server v4.1 with `new_display=<WxH/DPI>`, forces freeform windowing
 * on One UI 8.5/9 via `wm set-display-windowing-mode -d <id> 5`, and bridges the shell-owned
 * `localabstract:scrcpy_<scid>` video & control sockets to BreezeIsland over an authenticated
 * `127.0.0.1` loopback pair.
 */
class DexShizukuUserService : IDexUserService.Stub() {

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var callback: IDexUserCallback? = null

    @Volatile
    private var currentDisplayId: Int = -1

    private var uploadStream: FileOutputStream? = null
    private var serverProcess: Process? = null
    private var videoLocal: LocalSocket? = null
    private var controlLocal: LocalSocket? = null
    private var videoServer: ServerSocket? = null
    private var controlServer: ServerSocket? = null
    private var videoClient: Socket? = null
    private var controlClient: Socket? = null

    private val serverLog = StringBuilder()
    private val displayIdPattern = Regex("New display: .*\\(id=(\\d+)\\)")

    override fun destroy() {
        stopSession()
        Thread {
            runCatching { Thread.sleep(80L) }
            exitProcess(0)
        }.start()
    }

    override fun setCallback(cb: IDexUserCallback?) {
        callback = cb
    }

    override fun beginServerUpload(): Boolean = synchronized(lock) {
        runCatching {
            uploadStream?.close()
            val target = File(SERVER_REMOTE_PATH)
            target.parentFile?.mkdirs()
            uploadStream = FileOutputStream(target, false)
            true
        }.getOrDefault(false)
    }

    override fun writeServerChunk(chunk: ByteArray?): Boolean = synchronized(lock) {
        if (chunk == null) return false
        runCatching {
            uploadStream?.write(chunk) ?: return false
            true
        }.getOrDefault(false)
    }

    override fun finishServerUpload(): Boolean = synchronized(lock) {
        runCatching {
            uploadStream?.flush()
            uploadStream?.close()
            uploadStream = null
            File(SERVER_REMOTE_PATH).setReadable(true, false)
            true
        }.getOrDefault(false)
    }

    override fun startSession(displaySpec: String?, scidHex: String?, authToken: String?) {
        val spec = displaySpec?.trim().orEmpty()
        val scid = scidHex?.trim().orEmpty()
        val token = authToken?.trim().orEmpty()
        if (spec.isEmpty() || scid.isEmpty() || token.isEmpty()) {
            runCatching { callback?.onError("Invalid session parameters") }
            return
        }

        stopSession()
        stopping.set(false)
        running.set(true)
        currentDisplayId = -1
        synchronized(serverLog) { serverLog.setLength(0) }

        Thread({ runSession(spec, scid, token) }, "breeze-dex-shizuku").start()
    }

    override fun stopSession() {
        stopping.set(true)
        running.set(false)
        currentDisplayId = -1
        synchronized(lock) {
            runCatching { uploadStream?.close() }
            uploadStream = null
            runCatching { videoClient?.close() }
            videoClient = null
            runCatching { controlClient?.close() }
            controlClient = null
            runCatching { videoServer?.close() }
            videoServer = null
            runCatching { controlServer?.close() }
            controlServer = null
            runCatching { videoLocal?.close() }
            videoLocal = null
            runCatching { controlLocal?.close() }
            controlLocal = null
            runCatching { serverProcess?.destroyForcibly() }
            serverProcess = null
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun getDisplayId(): Int = currentDisplayId

    private fun runSession(displaySpec: String, scidHex: String, authToken: String) {
        try {
            if (!File(SERVER_REMOTE_PATH).exists()) {
                throw IOException("scrcpy-server was not uploaded")
            }

            // Enable freeform windows globally so apps on the virtual display get window controls.
            runShellSync("settings put global enable_freeform_support 1")

            val cmd = "CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
                "$SERVER_VERSION scid=$scidHex log_level=info " +
                "video=true audio=false control=true video_codec=h264 " +
                "video_codec_options=i-frame-interval:int=2 " +
                "tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
                "new_display=$displaySpec"

            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            synchronized(lock) {
                if (stopping.get()) {
                    proc.destroyForcibly()
                    return
                }
                serverProcess = proc
            }

            startServerLogReader(proc)

            val socketName = "scrcpy_$scidHex"
            val vLocal = connectLocalWithRetry(socketName, retries = 40)
            synchronized(lock) {
                if (stopping.get()) {
                    runCatching { vLocal.close() }
                    return
                }
                videoLocal = vLocal
            }

            val cLocal = connectLocalWithRetry(socketName, retries = 10)
            synchronized(lock) {
                if (stopping.get()) {
                    runCatching { cLocal.close() }
                    return
                }
                controlLocal = cLocal
            }

            val loopback = InetAddress.getByName("127.0.0.1")
            val vSrv = ServerSocket(0, 1, loopback).apply { soTimeout = ACCEPT_TIMEOUT_MS }
            val cSrv = ServerSocket(0, 1, loopback).apply { soTimeout = ACCEPT_TIMEOUT_MS }
            synchronized(lock) {
                if (stopping.get()) {
                    runCatching { vSrv.close() }
                    runCatching { cSrv.close() }
                    return
                }
                videoServer = vSrv
                controlServer = cSrv
            }

            val tokenBytes = authToken.toByteArray(Charsets.UTF_8)
            startVideoRelay(vSrv, vLocal, tokenBytes)
            startControlRelay(cSrv, cLocal, tokenBytes)

            runCatching { callback?.onSocketsReady(vSrv.localPort, cSrv.localPort) }

            proc.waitFor()
            if (!stopping.get()) {
                val tail = logTail()
                running.set(false)
                runCatching {
                    if (tail.isNotEmpty() && tail != "(no output)") {
                        callback?.onError("DeX display stopped:\n$tail")
                    } else {
                        callback?.onEnded()
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopping.get()) {
                val msg = t.message ?: t.javaClass.simpleName
                runCatching { callback?.onError(msg) }
            }
            stopSession()
        }
    }

    private fun startServerLogReader(proc: Process) {
        Thread({
            try {
                val input = proc.inputStream
                val buf = ByteArray(4096)
                while (!stopping.get()) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = String(buf, 0, n)
                    synchronized(serverLog) {
                        serverLog.append(chunk)
                        if (serverLog.length > 8192) serverLog.delete(0, serverLog.length - 8192)
                    }
                    parseDisplayId(chunk)
                }
            } catch (_: Throwable) {
            }
        }, "breeze-dex-log").start()
    }

    private fun parseDisplayId(chunk: String) {
        if (currentDisplayId != -1) return
        val id = displayIdPattern.find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: return
        currentDisplayId = id
        Thread({
            // Force freeform windowing on One UI 8.5 / 9 (scrcpy #6143 fix).
            runShellSync("wm set-display-windowing-mode -d $id 5")
            runCatching { callback?.onDisplayId(id) }
        }, "breeze-dex-wm").start()
    }

    private fun connectLocalWithRetry(abstractName: String, retries: Int): LocalSocket {
        var lastErr: Throwable? = null
        repeat(retries) {
            if (stopping.get()) throw IOException("Stopped")
            try {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT))
                return socket
            } catch (t: Throwable) {
                lastErr = t
            }
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                throw IOException("Interrupted")
            }
        }
        throw IOException("The DeX virtual display did not start. Server log:\n${logTail()}", lastErr)
    }

    private fun startVideoRelay(server: ServerSocket, local: LocalSocket, expectedToken: ByteArray) {
        Thread({
            var client: Socket? = null
            try {
                client = server.accept()
                runCatching { server.close() }
                if (!verifyToken(client, expectedToken)) {
                    client.close()
                    return@Thread
                }
                client.tcpNoDelay = true
                synchronized(lock) { videoClient = client }
                pipe(local.inputStream, client.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                runCatching { client?.close() }
                runCatching { server.close() }
            }
        }, "breeze-dex-vrelay").start()
    }

    private fun startControlRelay(server: ServerSocket, local: LocalSocket, expectedToken: ByteArray) {
        Thread({
            var client: Socket? = null
            try {
                client = server.accept()
                runCatching { server.close() }
                if (!verifyToken(client, expectedToken)) {
                    client.close()
                    return@Thread
                }
                client.tcpNoDelay = true
                synchronized(lock) { controlClient = client }

                val cSocket = client
                val inThread = Thread({
                    runCatching { pipe(cSocket.getInputStream(), local.outputStream) }
                }, "breeze-dex-crelay-in")
                inThread.start()

                runCatching { pipe(local.inputStream, cSocket.getOutputStream()) }
                inThread.interrupt()
            } catch (_: Throwable) {
            } finally {
                runCatching { client?.close() }
                runCatching { server.close() }
            }
        }, "breeze-dex-crelay").start()
    }

    private fun verifyToken(client: Socket, expected: ByteArray): Boolean {
        val oldTimeout = client.soTimeout
        return try {
            client.soTimeout = 5_000
            val received = ByteArray(expected.size)
            val input = client.getInputStream()
            var offset = 0
            while (offset < received.size) {
                val n = input.read(received, offset, received.size - offset)
                if (n <= 0) return false
                offset += n
            }
            client.soTimeout = oldTimeout
            received.contentEquals(expected)
        } catch (_: Throwable) {
            false
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (!stopping.get()) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun runShellSync(command: String) {
        runCatching {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            p.waitFor()
        }
    }

    private fun logTail(): String = synchronized(serverLog) {
        serverLog.toString().trim().takeLast(500).ifEmpty { "(no output)" }
    }

    companion object {
        private const val SERVER_REMOTE_PATH = "/data/local/tmp/breeze-scrcpy-server.jar"
        private const val SERVER_VERSION = "4.1"
        private const val ACCEPT_TIMEOUT_MS = 15_000
    }
}
