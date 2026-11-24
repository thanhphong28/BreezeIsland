package com.arnav.island.dex

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.arnav.island.BuildConfig
import com.arnav.island.IslandApp
import com.arnav.island.storage.AppLanguage
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Active transport backing a live Samsung DeX session. */
enum class DexTransport {
    SHIZUKU,
    WIRELESS_ADB,
}

/**
 * Owns one live Samsung DeX virtual-display session via either:
 *  - [DexTransport.SHIZUKU]: UID 2000 Shizuku UserService (instant 1-tap, works even without Wi-Fi)
 *  - [DexTransport.WIRELESS_ADB]: Built-in loopback Wireless ADB (full sam1am/localdex engine)
 *
 * Both transports start the bundled scrcpy-server v4.1 (`new_display=<WxH/DPI>`), force freeform
 * windowing (`wm set-display-windowing-mode -d <id> 5`) for One UI 8.5/9, and feed into
 * [DexVideoDecoder] and [DexInputController].
 */
class DexSession(
    private val context: Context,
    val displaySpec: String,
    val transport: DexTransport,
    val outputMode: DexOutputMode = DexOutputMode.AUTO_EXTERNAL_OR_PC,
) {
    sealed class State {
        data class Starting(val message: String) : State()
        data class Running(
            val videoWidth: Int,
            val videoHeight: Int,
            val displayId: Int,
            val transport: DexTransport,
        ) : State()
        data class Stopped(val error: String?) : State()
    }

    companion object {
        private const val TAG = "DexSession"
        private const val SERVER_ASSET = "scrcpy-server"
        private const val SERVER_REMOTE_PATH = "/data/local/tmp/breeze-scrcpy-server.jar"
        private const val SERVER_VERSION = "4.1"
        private const val CONNECT_RETRIES = 40
        private const val CONNECT_RETRY_DELAY_MS = 250L
        private const val UPLOAD_CHUNK_BYTES = 128 * 1024

        @Volatile
        var current: DexSession? = null
    }

    private val isVi: Boolean
        get() = (context.applicationContext as? IslandApp)?.graph?.settings?.state?.value?.language != AppLanguage.ENGLISH

    private val _state = MutableStateFlow<State>(
        State.Starting(if (isVi) "Đang kết nối Samsung DeX…" else "Connecting to Samsung DeX…")
    )
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)

    // Wireless ADB handles
    private var manager: AbsAdbConnectionManager? = null
    private var shellStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    // Shizuku handles
    private var shizukuService: IDexUserService? = null
    private var shizukuConnection: ServiceConnection? = null
    private var shizukuArgs: Shizuku.UserServiceArgs? = null
    private var videoTcpSocket: Socket? = null
    private var controlTcpSocket: Socket? = null

    var videoDecoder: DexVideoDecoder? = null
        private set
    var controller: DexInputController? = null
        private set
    var webServer: DexWebServer? = null
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    @Volatile
    var displayId = -1
        private set

    private val serverLog = StringBuilder()

    fun start() {
        scope.launch {
            try {
                when (transport) {
                    DexTransport.SHIZUKU -> runShizukuSession()
                    DexTransport.WIRELESS_ADB -> runWirelessAdbSession()
                }
            } catch (_: CancellationException) {
                // Expected when user stops mid-startup.
            } catch (e: Exception) {
                Log.e(TAG, "DeX session failed", e)
                stop(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Transport 1: Shizuku UserService (UID 2000)
    // ---------------------------------------------------------------------------------------------

    private suspend fun runShizukuSession() {
        setStarting(if (isVi) "Đang kết nối cầu Shizuku…" else "Connecting Shizuku bridge…")

        val args = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, DexShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("local_dex")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        shizukuArgs = args

        val bound = CompletableDeferred<IDexUserService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = IDexUserService.Stub.asInterface(binder)
                if (svc != null && !bound.isCompleted) bound.complete(svc)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!bound.isCompleted) {
                    bound.completeExceptionally(IOException("Shizuku service disconnected"))
                } else if (!stopped.get()) {
                    stop(if (isVi) "Kết nối Shizuku bị ngắt." else "Shizuku bridge disconnected.")
                }
            }
        }
        shizukuConnection = conn
        Shizuku.bindUserService(args, conn)

        val svc = withTimeout(10_000L) { bound.await() }
        shizukuService = svc
        if (stopped.get()) throw CancellationException("Stopped")

        setStarting(if (isVi) "Đang chuẩn bị màn hình ảo DeX…" else "Preparing virtual DeX display…")
        val serverBytes = context.assets.open(SERVER_ASSET).use { it.readBytes() }
        if (!svc.beginServerUpload()) {
            throw IOException(if (isVi) "Không thể ghi tệp máy chủ DeX." else "Could not initialize DeX server file.")
        }
        var offset = 0
        while (offset < serverBytes.size) {
            if (stopped.get()) throw CancellationException("Stopped")
            val end = minOf(offset + UPLOAD_CHUNK_BYTES, serverBytes.size)
            val chunk = serverBytes.copyOfRange(offset, end)
            if (!svc.writeServerChunk(chunk)) {
                throw IOException(if (isVi) "Lỗi ghi dữ liệu máy chủ DeX." else "Failed uploading DeX server.")
            }
            offset = end
        }
        if (!svc.finishServerUpload()) {
            throw IOException(if (isVi) "Không thể hoàn tất tải máy chủ DeX." else "Could not finalize DeX server file.")
        }

        val socketsReady = CompletableDeferred<Pair<Int, Int>>()
        svc.setCallback(object : IDexUserCallback.Stub() {
            override fun onStatus(message: String?) {
                if (!message.isNullOrBlank()) setStarting(message)
            }

            override fun onDisplayId(id: Int) {
                displayId = id
                val cur = _state.value
                if (cur is State.Running) {
                    _state.value = cur.copy(displayId = id)
                }
            }

            override fun onSocketsReady(videoPort: Int, controlPort: Int) {
                if (!socketsReady.isCompleted) socketsReady.complete(videoPort to controlPort)
            }

            override fun onError(message: String?) {
                val msg = message ?: if (isVi) "Màn hình DeX gặp sự cố." else "DeX display error."
                if (!socketsReady.isCompleted) {
                    socketsReady.completeExceptionally(IOException(msg))
                } else {
                    stop(msg)
                }
            }

            override fun onEnded() {
                if (!socketsReady.isCompleted) {
                    socketsReady.completeExceptionally(
                        IOException(if (isVi) "Phiên DeX đã kết thúc sớm." else "DeX session ended unexpectedly.")
                    )
                } else {
                    stop(null)
                }
            }
        })

        val scidHex = String.format("%08x", Random.nextInt(1, Int.MAX_VALUE))
        val authToken = generateAuthToken()
        setStarting(if (isVi) "Đang khởi động Samsung DeX…" else "Starting Samsung DeX…")
        svc.startSession(displaySpec, scidHex, authToken)

        val (videoPort, controlPort) = withTimeout(20_000L) { socketsReady.await() }
        if (stopped.get()) throw CancellationException("Stopped")

        val tokenBytes = authToken.toByteArray(Charsets.UTF_8)
        val vSocket = connectLoopbackSocket(videoPort, tokenBytes)
        videoTcpSocket = vSocket
        if (stopped.get()) {
            runCatching { vSocket.close() }
            throw CancellationException("Stopped")
        }

        val cSocket = connectLoopbackSocket(controlPort, tokenBytes)
        controlTcpSocket = cSocket
        if (stopped.get()) {
            runCatching { cSocket.close() }
            throw CancellationException("Stopped")
        }

        startMediaAndControl(
            videoIn = vSocket.getInputStream(),
            ctrlOut = cSocket.getOutputStream(),
            ctrlIn = cSocket.getInputStream(),
        )
    }

    private fun connectLoopbackSocket(port: Int, tokenBytes: ByteArray): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        sock.tcpNoDelay = true
        sock.getOutputStream().apply {
            write(tokenBytes)
            flush()
        }
        return sock
    }

    private fun generateAuthToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // Transport 2: Built-in Wireless ADB (sam1am/localdex engine)
    // ---------------------------------------------------------------------------------------------

    private suspend fun runWirelessAdbSession() {
        val mgr = DexAdb.createManager(context)
        this.manager = mgr

        if (!mgr.autoConnect(context, 10_000)) {
            throw IOException(
                if (isVi) "Không thể kết nối ADB không dây. Hãy kiểm tra Wi-Fi và Gỡ lỗi không dây đã bật & ghép nối chưa."
                else "Could not connect to Wireless ADB. Check Wi-Fi and ensure Wireless debugging is enabled & paired."
            )
        }

        setStarting(if (isVi) "Đang chuẩn bị màn hình ảo DeX…" else "Preparing virtual DeX display…")
        val serverBytes = context.assets.open(SERVER_ASSET).use { it.readBytes() }
        DexAdb.pushFile(mgr, serverBytes.inputStream(), serverBytes.size.toLong(), SERVER_REMOTE_PATH)

        runCatching {
            DexAdb.runShell(mgr, "settings put global enable_freeform_support 1")
        }

        val scidHex = String.format("%08x", Random.nextInt(1, Int.MAX_VALUE))
        val command = "CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
            "$SERVER_VERSION scid=$scidHex log_level=info " +
            "video=true audio=false control=true video_codec=h264 " +
            "video_codec_options=i-frame-interval:int=2 " +
            "tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
            "new_display=$displaySpec"

        val shell = mgr.openStream("shell:$command")
        shellStream = shell
        checkNotStopped(shell)
        startServerLogReader(shell)

        setStarting(if (isVi) "Đang chờ màn hình DeX phản hồi…" else "Waiting for DeX display…")
        val socketName = "localabstract:scrcpy_$scidHex"
        val video = connectAdbWithRetry(mgr, socketName)
        videoStream = video
        checkNotStopped(video)
        val control = connectAdbWithRetry(mgr, socketName, retries = 8)
        controlStream = control
        checkNotStopped(control)

        startMediaAndControl(
            videoIn = video.openInputStream(),
            ctrlOut = control.openOutputStream(),
            ctrlIn = control.openInputStream(),
        )
    }

    private fun startMediaAndControl(
        videoIn: java.io.InputStream,
        ctrlOut: java.io.OutputStream,
        ctrlIn: java.io.InputStream,
    ) {
        val ctrl = DexInputController(ctrlOut, ctrlIn)
        controller = ctrl
        ctrl.start()

        val ws = DexWebServer(
            controllerProvider = { controller },
            videoInfoProvider = {
                val dec = videoDecoder
                Triple(
                    dec?.videoWidth ?: videoWidth,
                    dec?.videoHeight ?: videoHeight,
                    dec?.codecString ?: "avc1.64002a",
                )
            },
            cachedInitProvider = {
                val dec = videoDecoder
                dec?.lastConfigPacket to dec?.lastKeyFramePacket
            },
        )
        ws.start()
        webServer = ws

        val decoder = DexVideoDecoder(
            input = videoIn,
            onVideoSize = { w, h ->
                videoWidth = w
                videoHeight = h
                ws.onVideoSizeChanged(w, h, videoDecoder?.codecString ?: "avc1.64002a")
                _state.value = State.Running(w, h, displayId, transport)
            },
            onEncodedPacket = { isConfig, isKeyFrame, ptsUs, data ->
                ws.onEncodedPacket(isConfig, isKeyFrame, ptsUs, data)
            },
            onError = { msg -> stop(msg) },
        )
        videoDecoder = decoder
        decoder.start()

        setStarting(if (isVi) "Đang dựng hình màn hình DeX…" else "Rendering DeX display…")
    }

    private fun checkNotStopped(stream: AdbStream) {
        if (stopped.get()) {
            runCatching { stream.close() }
            throw CancellationException("Stopped during startup")
        }
    }

    private fun setStarting(message: String) {
        if (_state.value !is State.Stopped) {
            _state.value = State.Starting(message)
        }
    }

    private suspend fun connectAdbWithRetry(
        manager: AbsAdbConnectionManager,
        socketName: String,
        retries: Int = CONNECT_RETRIES,
    ): AdbStream {
        var lastError: Exception? = null
        repeat(retries) {
            if (stopped.get()) throw CancellationException("Stopped")
            try {
                val stream = withTimeoutOrNull(3_000L) {
                    runInterruptible { manager.openStream(socketName) }
                }
                if (stream != null) return stream
            } catch (e: Exception) {
                lastError = e
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        throw IOException(
            (if (isVi) "Không thể khởi động màn hình DeX. Nhật ký:\n" else "DeX display did not start. Log:\n") +
                serverLogTail(),
            lastError,
        )
    }

    private fun startServerLogReader(shell: AdbStream) {
        Thread({
            try {
                val input = shell.openInputStream()
                val buffer = ByteArray(4096)
                while (!stopped.get()) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val text = String(buffer, 0, read)
                    synchronized(serverLog) {
                        serverLog.append(text)
                        if (serverLog.length > 8192) serverLog.delete(0, serverLog.length - 8192)
                    }
                    parseDisplayIdFromAdbLog(text)
                }
            } catch (_: IOException) {
            }
        }, "breeze-dex-adb-log").start()
    }

    private fun parseDisplayIdFromAdbLog(chunk: String) {
        if (displayId != -1) return
        val id = DexDisplaySpec.parseDisplayId(chunk) ?: return
        displayId = id
        scope.launch {
            runCatching {
                val mgr = manager ?: return@launch
                DexAdb.runShell(mgr, "wm set-display-windowing-mode -d $id 5")
            }
        }
        val current = _state.value
        if (current is State.Running) {
            _state.value = current.copy(displayId = id)
        }
    }

    private fun serverLogTail(): String = synchronized(serverLog) {
        serverLog.toString().trim().takeLast(500).ifEmpty { "(no output)" }
    }

    fun stop(error: String? = null) {
        if (!stopped.compareAndSet(false, true)) return

        scope.launch {
            runCatching { webServer?.stop() }
            webServer = null
            runCatching { videoDecoder?.stop() }
            runCatching { controller?.stop() }

            listOf(videoStream, controlStream, shellStream).forEach { s -> runCatching { s?.close() } }
            runCatching { manager?.close() }

            runCatching { videoTcpSocket?.close() }
            runCatching { controlTcpSocket?.close() }
            runCatching { shizukuService?.stopSession() }
            val args = shizukuArgs
            val conn = shizukuConnection
            if (args != null && conn != null) {
                runCatching { Shizuku.unbindUserService(args, conn, true) }
            }
            shizukuService = null
            shizukuConnection = null
            shizukuArgs = null

            _state.value = State.Stopped(error)
            if (current === this@DexSession) current = null
            scope.cancel()
        }
    }
}
