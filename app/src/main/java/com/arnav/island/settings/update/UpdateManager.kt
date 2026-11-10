package com.arnav.island.settings.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arnav.island.BuildConfig
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    const val GITHUB_OWNER = "thanhphong28"
    const val GITHUB_REPO = "BreezeIsland"
    const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val RELEASES_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val API_LATEST_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(
            val version: String,
            val downloadUrl: String?,
            val htmlUrl: String,
        ) : State()
        data class UpdateAvailable(
            val latestVersion: String,
            val currentVersion: String,
            val releaseNotes: String,
            val downloadUrl: String,
            val htmlUrl: String,
        ) : State()
        data class Downloading(
            val version: String,
            val progress: Float,
        ) : State()
        data class ReadyToInstall(
            val version: String,
            val apkFile: File,
        ) : State()
        data class Error(
            val messageEn: String,
            val messageVi: String,
        ) : State()
    }

    suspend fun checkLatest(): State = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "BreezeIsland/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext State.Error(
                    "GitHub API returned HTTP $code",
                    "Không thể kết nối GitHub Releases (Mã lỗi HTTP $code)",
                )
            }
            val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(bodyText)
            val tagName = json.optString("tag_name", "").removePrefix("v").removePrefix("V").trim()
            val htmlUrl = json.optString("html_url", RELEASES_URL)
            val notes = json.optString("body", "").trim()

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val dl = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                        apkUrl = dl
                        if (name.contains("BreezeIsland", ignoreCase = true)) break
                    }
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME.substringBefore('-')
            val latestVersion = tagName.ifEmpty { currentVersion }
            if (isNewerVersion(latestVersion, currentVersion)) {
                State.UpdateAvailable(
                    latestVersion = latestVersion,
                    currentVersion = currentVersion,
                    releaseNotes = notes,
                    downloadUrl = apkUrl ?: htmlUrl,
                    htmlUrl = htmlUrl,
                )
            } else {
                State.UpToDate(
                    version = latestVersion,
                    downloadUrl = apkUrl,
                    htmlUrl = htmlUrl,
                )
            }
        } catch (e: Exception) {
            State.Error(
                "Could not check updates: ${e.localizedMessage ?: "Network error"}",
                "Không thể kiểm tra cập nhật: ${e.localizedMessage ?: "Lỗi kết nối mạng"}",
            )
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "BreezeIsland-update.apk")
            if (outFile.exists()) outFile.delete()

            var currentUrl = downloadUrl
            var redirects = 0
            var conn: HttpURLConnection
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "BreezeIsland/${BuildConfig.VERSION_NAME}")
                }
                val status = conn.responseCode
                if (status in 301..308 && redirects < 5) {
                    val next = conn.getHeaderField("Location") ?: break
                    currentUrl = next
                    redirects++
                } else {
                    break
                }
            }

            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
            outFile
        }
    }

    fun installApk(context: Context, apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(installIntent) }
    }

    fun openBrowser(context: Context, url: String = RELEASES_URL) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val lParts = latest.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val cParts = current.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(lParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}

@Composable
fun UpdateCheckerCard(autoCheck: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateManager.State>(UpdateManager.State.Idle) }

    fun triggerCheck() {
        scope.launch {
            state = UpdateManager.State.Checking
            state = UpdateManager.checkLatest()
        }
    }

    LaunchedEffect(autoCheck) {
        if (autoCheck && state == UpdateManager.State.Idle) {
            triggerCheck()
        }
    }

    fun triggerDownload(version: String, url: String) {
        if (!url.endsWith(".apk", ignoreCase = true)) {
            UpdateManager.openBrowser(context, url)
            return
        }
        scope.launch {
            state = UpdateManager.State.Downloading(version, 0f)
            val result = UpdateManager.downloadApk(context, url) { progress ->
                state = UpdateManager.State.Downloading(version, progress)
            }
            result.fold(
                onSuccess = { file ->
                    state = UpdateManager.State.ReadyToInstall(version, file)
                    UpdateManager.installApk(context, file)
                },
                onFailure = { e ->
                    state = UpdateManager.State.Error(
                        "Download failed: ${e.localizedMessage ?: "Error"}",
                        "Tải bản cập nhật thất bại: ${e.localizedMessage ?: "Lỗi mạng"}",
                    )
                },
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = LocalIslandColors.current.card,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = BadgeColors.Blue,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Software update", "Cập nhật ứng dụng (OTA)"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        tr(
                            "Current version: v${BuildConfig.VERSION_NAME} · GitHub Releases",
                            "Phiên bản hiện tại: v${BuildConfig.VERSION_NAME} · Tự động cập nhật từ GitHub",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when (val s = state) {
                is UpdateManager.State.Idle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = ::triggerCheck,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Check for updates", "Kiểm tra cập nhật"))
                        }
                        FilledTonalButton(
                            onClick = { UpdateManager.openBrowser(context) },
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
                        }
                    }
                }

                is UpdateManager.State.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tr("Checking GitHub Releases for new version…", "Đang kiểm tra phiên bản mới trên GitHub…"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is UpdateManager.State.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = BadgeColors.Green, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr(
                                "You're using the latest version (v${s.version}).",
                                "Bạn đang sử dụng phiên bản mới nhất (v${s.version}).",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = ::triggerCheck, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Check again", "Kiểm tra lại"))
                        }
                        if (s.downloadUrl != null) {
                            TextButton(onClick = { triggerDownload(s.version, s.downloadUrl) }) {
                                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Reinstall APK", "Tải & cài lại APK"))
                            }
                        }
                    }
                }

                is UpdateManager.State.UpdateAvailable -> {
                    Text(
                        tr(
                            "New version available: v${s.latestVersion} (Current: v${s.currentVersion})",
                            "Có bản cập nhật mới: v${s.latestVersion} (Hiện tại: v${s.currentVersion})",
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (s.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.releaseNotes.take(400),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { triggerDownload(s.latestVersion, s.downloadUrl) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Download & Install v${s.latestVersion}", "Tải & Cập nhật ngay v${s.latestVersion}"))
                        }
                        FilledTonalButton(
                            onClick = { UpdateManager.openBrowser(context, s.htmlUrl) },
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
                        }
                    }
                }

                is UpdateManager.State.Downloading -> {
                    Text(
                        tr(
                            "Downloading BreezeIsland v${s.version}… ${(s.progress * 100).toInt()}%",
                            "Đang tải BreezeIsland v${s.version}… ${(s.progress * 100).toInt()}%",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }

                is UpdateManager.State.ReadyToInstall -> {
                    Text(
                        tr(
                            "Downloaded v${s.version}! Tap Install below to update (allow 'Install unknown apps' if prompted).",
                            "Đã tải xong bản v${s.version}! Bấm Cài đặt ngay bên dưới (nếu máy hỏi quyền Cài đặt ứng dụng không xác định, hãy bật cho phép).",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { UpdateManager.installApk(context, s.apkFile) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Install update now", "Cài đặt bản cập nhật ngay"))
                        }
                        FilledTonalButton(onClick = ::triggerCheck, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        }
                    }
                }

                is UpdateManager.State.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = BadgeColors.Red, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr(s.messageEn, s.messageVi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = ::triggerCheck, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Try again", "Thử lại"))
                        }
                        TextButton(onClick = { UpdateManager.openBrowser(context) }) {
                            Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Open GitHub Releases", "Mở trang GitHub Releases"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Check for updates", "Kiểm tra cập nhật BreezeIsland")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UpdateCheckerCard(autoCheck = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Close", "Đóng"))
            }
        },
    )
}
