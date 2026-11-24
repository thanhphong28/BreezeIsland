package com.arnav.island.dex

import android.content.Context
import android.os.Build
import com.arnav.island.IslandApp
import com.arnav.island.storage.AppLanguage
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream

/**
 * Built-in loopback Wireless ADB plumbing (ported from sam1am/localdex).
 * Speaks ADB over TLS directly to `adbd` on 127.0.0.1 with zero PC and zero root.
 */
object DexAdb {
    private const val SHELL_READ_TIMEOUT_MS = 5_000L
    private const val PAIRING_TIMEOUT_MS = 20_000L
    private const val CONNECTION_CHECK_CACHE_MS = 2_000L
    private const val PUSH_MARKER = "BREEZE_DEX_PUSH_DONE"
    private val PAIRING_CODE_PATTERN = Regex("\\d{6}")

    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class ConnectionStatus {
        CONNECTED,
        NEEDS_PAIRING,
    }

    @Volatile
    private var lastConnectionCheck: Long = 0L

    @Volatile
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING

    private fun isVietnamese(context: Context): Boolean {
        val lang = (context.applicationContext as? IslandApp)?.graph?.settings?.state?.value?.language
        return lang != AppLanguage.ENGLISH
    }

    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {
        val now = System.currentTimeMillis()
        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        var stream: AdbStream? = null
        val status = try {
            val manager = DexAdbConnectionManager.getInstance(context)
            if (!manager.autoConnect(context, 3_000)) {
                ConnectionStatus.NEEDS_PAIRING
            } else {
                try {
                    stream = manager.openStream("shell:echo ok")
                    val buffer = ByteArray(128)
                    val bytesRead = stream.openInputStream().read(buffer)
                    stream.close()
                    if (bytesRead > 0) ConnectionStatus.CONNECTED else ConnectionStatus.NEEDS_PAIRING
                } catch (_: Exception) {
                    runCatching { stream?.close() }
                    ConnectionStatus.NEEDS_PAIRING
                }
            }
        } catch (_: Throwable) {
            ConnectionStatus.NEEDS_PAIRING
        }

        lastConnectionCheck = now
        lastConnectionStatus = status
        return status
    }

    fun invalidateStatusCache() {
        lastConnectionCheck = 0L
    }

    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> {
        val vi = isVietnamese(context)
        val retryHint = if (vi) {
            "Hãy bấm lại \"Ghép nối thiết bị bằng mã ghép nối\" để lấy mã mới rồi nhập lại."
        } else {
            "Tap \"Pair device with pairing code\" again for a fresh code, then reply with the new one."
        }

        val code = pairingCode.trim()
        if (!PAIRING_CODE_PATTERN.matches(code)) {
            val msg = if (vi) {
                "Mã ghép nối phải gồm đúng 6 chữ số hiển thị trong hộp thoại Ghép nối."
            } else {
                "Enter the exact 6-digit code shown in the \"Pair device with pairing code\" dialog."
            }
            return Result.failure(Exception(msg))
        }
        if (pairingPort !in 1..65535) {
            val msg = if (vi) {
                "Chưa tìm thấy cổng ghép nối. Hãy mở Gỡ lỗi không dây → Ghép nối thiết bị bằng mã ghép nối trước."
            } else {
                "No pairing port found yet. Open Wireless debugging → Pair device with pairing code first."
            }
            return Result.failure(Exception(msg))
        }

        val attempt = pairingScope.async {
            DexAdbConnectionManager.getInstance(context).pair("127.0.0.1", pairingPort, code)
        }

        val paired = try {
            withTimeoutOrNull(PAIRING_TIMEOUT_MS) { attempt.await() }
        } catch (e: CancellationException) {
            attempt.cancel()
            throw e
        } catch (e: Exception) {
            attempt.cancel()
            val msg = if (vi) "Ghép nối thất bại. $retryHint" else "Pairing failed. $retryHint"
            return Result.failure(Exception(msg, e))
        }

        return when (paired) {
            true -> Result.success(true)
            false -> Result.failure(
                Exception(
                    if (vi) "Thiết bị đã từ chối mã này. $retryHint"
                    else "The device rejected that code. $retryHint"
                )
            )
            null -> {
                attempt.cancel()
                Result.failure(
                    Exception(
                        if (vi) "Hết thời gian ghép nối. $retryHint"
                        else "Pairing timed out. $retryHint"
                    )
                )
            }
        }
    }

    fun createManager(context: Context): AbsAdbConnectionManager {
        val manager = object : AbsAdbConnectionManager() {
            private val delegate = DexAdbConnectionManager.getInstance(context)
            override fun getPrivateKey() = delegate.getPrivateKey()
            override fun getCertificate() = delegate.getCertificate()
            override fun getDeviceName() = delegate.getDeviceName()
        }
        manager.setApi(Build.VERSION.SDK_INT)
        return manager
    }

    suspend fun runShellCommand(context: Context, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val manager = DexAdbConnectionManager.getInstance(context)
                if (!manager.autoConnect(context, 10_000)) {
                    return@withContext Result.failure(Exception("Could not connect to ADB."))
                }
                Result.success(runShell(manager, command))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun runShell(
        manager: AbsAdbConnectionManager,
        command: String,
        timeoutMs: Long = SHELL_READ_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        manager.openStream("exec:$command").use { stream ->
            readAll(stream, timeoutMs)
        }
    }

    private suspend fun readAll(stream: AdbStream, timeoutMs: Long): String {
        val output = StringBuilder()
        val input = stream.openInputStream()
        val buffer = ByteArray(4096)
        withTimeoutOrNull(timeoutMs) {
            runInterruptible {
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: IOException) {
                        -1
                    }
                    if (read <= 0) break
                    output.append(String(buffer, 0, read))
                }
            }
        }
        return output.toString().trim()
    }

    suspend fun pushFile(
        manager: AbsAdbConnectionManager,
        input: InputStream,
        size: Long,
        remotePath: String,
    ): Unit = withContext(Dispatchers.IO) {
        manager.openStream("exec:head -c $size > $remotePath && echo $PUSH_MARKER").use { stream ->
            val output = stream.openOutputStream()
            input.use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    currentCoroutineContext().ensureActive()
                }
            }
            output.flush()
            val response = readAll(stream, 30_000L)
            if (!response.contains(PUSH_MARKER)) {
                throw IOException("Push to $remotePath was not confirmed: $response")
            }
        }
    }
}
