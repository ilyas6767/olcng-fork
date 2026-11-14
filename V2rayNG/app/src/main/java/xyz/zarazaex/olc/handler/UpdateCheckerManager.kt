package xyz.zarazaex.olc.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.BuildConfig
import xyz.zarazaex.olc.dto.CheckUpdateResult
import xyz.zarazaex.olc.dto.GitHubRelease
import xyz.zarazaex.olc.extension.concatUrl
import xyz.zarazaex.olc.util.HttpUtil
import xyz.zarazaex.olc.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object UpdateCheckerManager {

    /** Результат попытки запустить установку. */
    enum class InstallResult {
        /** Системный установщик открыт. */
        STARTED,

        /** Нужно разрешить установку из этого источника; открыт экран настроек. */
        NEED_PERMISSION,

        /** Файл повреждён, от другого приложения или старее установленной версии. */
        INVALID_APK,

        /** Другая ошибка. */
        ERROR
    }

    private const val UPDATE_DIR = "updates"
    private const val APK_NAME = "update.apk"

    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = if (includePreRelease) {
                AppConfig.APP_API_URL
            } else {
                AppConfig.APP_API_URL.concatUrl("latest")
            }

            var response = HttpUtil.getUrlContent(url, 5000)
            if (response.isNullOrEmpty()) {
                val httpPort = SettingsManager.getHttpPort()
                response = HttpUtil.getUrlContent(url, 5000, httpPort)
                    ?: throw IllegalStateException("Failed to get response")
            }

            val latestRelease = if (includePreRelease) {
                JsonUtil.fromJson(response, Array<GitHubRelease>::class.java)
                    ?.firstOrNull()
                    ?: throw IllegalStateException("No pre-release found")
            } else {
                JsonUtil.fromJson(response, GitHubRelease::class.java)
            }
            if (latestRelease == null) {
                return@withContext CheckUpdateResult(hasUpdate = false)
            }

            val latestVersion = latestRelease.tagName.removePrefix("v")
            Log.i(
                AppConfig.TAG,
                "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
            )

            return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
                val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
                CheckUpdateResult(
                    hasUpdate = true,
                    latestVersion = latestVersion,
                    releaseNotes = latestRelease.body,
                    downloadUrl = downloadUrl,
                    isPreRelease = latestRelease.prerelease
                )
            } else {
                CheckUpdateResult(hasUpdate = false)
            }
        } catch (e: NumberFormatException) {
            Log.e(AppConfig.TAG, "Failed to parse version: ${e.message}")
            return@withContext CheckUpdateResult(hasUpdate = false)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to check for update: ${e.message}")
            throw e
        }
    }

    /**
     * Скачивает APK внутри приложения.
     * Сначала пробует прямое соединение, затем локальный прокси (как и checkForUpdate):
     * прокси слушает только при включённом VPN, поэтому прямая попытка нужна,
     * чтобы обновление работало и при выключенном VPN.
     *
     * @param onProgress Вызывается во время загрузки: (скачано байт, всего байт либо -1, если размер неизвестен).
     *   Вызывается не из главного потока.
     * @return Файл APK или null при ошибке.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        downloadApkVia(context, downloadUrl, 0, onProgress)
            ?: downloadApkVia(context, downloadUrl, SettingsManager.getHttpPort(), onProgress)
    }

    private suspend fun downloadApkVia(
        context: Context,
        downloadUrl: String,
        httpPort: Int,
        onProgress: ((Long, Long) -> Unit)?
    ): File? {
        val connection = HttpUtil.createProxyConnection(downloadUrl, httpPort, 10000, 10000, true)
            ?: return null

        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val apkFile = File(dir, APK_NAME)
        val tmpFile = File(dir, "$APK_NAME.part")
        tmpFile.delete()

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.e(AppConfig.TAG, "APK download failed, http $code (port=$httpPort)")
                return null
            }
            val total = connection.contentLengthLong
            Log.i(AppConfig.TAG, "Downloading APK to: ${apkFile.absolutePath} (port=$httpPort, size=$total)")

            var downloaded = 0L
            var lastReportAt = 0L
            FileOutputStream(tmpFile).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n

                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= 200) {
                            lastReportAt = now
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                }
            }
            onProgress?.invoke(downloaded, total)

            if (total > 0 && tmpFile.length() != total) {
                Log.e(AppConfig.TAG, "APK size mismatch: got ${tmpFile.length()}, expected $total")
                tmpFile.delete()
                return null
            }

            apkFile.delete()
            if (!tmpFile.renameTo(apkFile)) {
                tmpFile.delete()
                return null
            }
            Log.i(AppConfig.TAG, "APK download completed")
            apkFile
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to download APK (port=$httpPort): ${e.message}")
            tmpFile.delete()
            null
        } finally {
            try {
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error closing connection: ${e.message}")
            }
        }
    }

    /**
     * Запускает системную установку скачанного APK.
     * Перед этим проверяет, что файл — это наше приложение и не старее установленного,
     * и что разрешена установка из этого источника.
     * После запуска установщика приложение будет перезапущено системой; VPN придётся включить заново.
     */
    fun installApk(context: Context, apk: File): InstallResult {
        return try {
            if (!isValidUpdateApk(context, apk)) {
                apk.delete()
                return InstallResult.INVALID_APK
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.packageManager.canRequestPackageInstalls()
            ) {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                return InstallResult.NEED_PERMISSION
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.STARTED
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start APK install: ${e.message}")
            InstallResult.ERROR
        }
    }

    /** Ранее скачанный APK, если он есть и подходит для установки. */
    fun getDownloadedApk(context: Context): File? {
        val apk = File(File(context.cacheDir, UPDATE_DIR), APK_NAME)
        return if (apk.exists() && isValidUpdateApk(context, apk)) apk else null
    }

    private fun isValidUpdateApk(context: Context, apk: File): Boolean {
        return try {
            if (!apk.exists() || apk.length() == 0L) return false
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
            if (info.packageName != context.packageName) return false

            val current = pm.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info) >= PackageInfoCompat.getLongVersionCode(current)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "APK validation failed: ${e.message}")
            false
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val cleanVersion1 = version1.split("-")[0]
        val cleanVersion2 = version2.split("-")[0]

        val v1 = cleanVersion1.split(".")
        val v2 = cleanVersion2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].toIntOrNull() ?: 0 else 0
            val num2 = if (i < v2.size) v2[i].toIntOrNull() ?: 0 else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"

        val assetsByAbi = release.assets.filter {
            (it.name.contains(abi, true))
        }

        val asset = if (BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)) {
            assetsByAbi.firstOrNull { it.name.contains(fDroid) }
        } else {
            assetsByAbi.firstOrNull { !it.name.contains(fDroid) }
        }

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
