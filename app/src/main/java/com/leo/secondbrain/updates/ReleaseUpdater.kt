package com.leo.secondbrain.updates

import android.content.Context
import com.leo.secondbrain.BuildConfig
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.backup.digest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AvailableRelease(
    val tag: String, val notes: String, val url: String, val digest: String, val size: Long
)

internal class ReleaseUpdater(private val context: Context) {
    private val cache = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "verified-updates")
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || url.host !in hosts) throw IOException("Invalid release download host.")
            chain.proceed(chain.request())
        }.build()

    private val repository = BuildConfig.RELEASE_REPOSITORY
    val configured: Boolean get() = validRepository(repository)

    @Suppress("DEPRECATION")
    val installed: PackageInfo get() = context.packageManager.getPackageInfo(context.packageName, 0)

    suspend fun check(): AvailableRelease? = withContext(Dispatchers.IO) {
        val source = repository
        if (!configured) return@withContext null
        if (cache.getString("source", null) != source) {
            cache.edit().putString("source", source).remove("etag").remove("body").apply()
        }
        val request = Request.Builder().url("https://api.github.com/repos/$source/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "SecondBrain/${BuildConfig.VERSION_NAME}")
            .apply { cache.getString("etag", null)?.let { header("If-None-Match", it) } }.build()
        val release = client.newCall(request).execute().use { result ->
            when {
                result.code == 304 -> cache.getString("body", null)
                    ?: throw IOException("Release metadata cache is missing. Try again.")
                result.code == 404 -> {
                    cache.edit().remove("etag").remove("body").apply()
                    return@withContext null
                }
                result.code == 403 || result.code == 429 -> throw IOException("GitHub’s request limit was reached. Try later.")
                !result.isSuccessful -> throw IOException("GitHub replied with HTTP ${result.code}.")
                else -> {
                    val body = result.body?.source() ?: throw IOException("GitHub returned no release data.")
                    if (body.request(512_001)) throw IOException("Release metadata is too large.")
                    val text = body.readUtf8()
                    if (source == repository) cache.edit().putString("body", text)
                        .putString("etag", result.header("ETag")).apply()
                    text
                }
            }
        }.let(::JSONObject)
        val tag = release.getString("tag_name")
        if (!isNewRelease(tag, installed.versionName.orEmpty())) return@withContext null
        val prefix = "https://github.com/$source/releases/download/"
        val asset = release.optJSONArray("assets").objects()
            .filter { !it.optBoolean("draft") && it.optString("name").endsWith(".apk", ignoreCase = true) }
            .let { options ->
                options.singleOrNull { it.optString("name").startsWith("second-brain-") } ?: options.singleOrNull()
            } ?: throw IOException("The release must include one installable APK.")
        val url = asset.getString("browser_download_url")
        val digest = asset.optString("digest").lowercase().removePrefix("sha256:")
        val size = asset.getLong("size")
        require(url.startsWith(prefix, ignoreCase = true) && digest.matches(Regex("[a-f0-9]{64}")) &&
            size in 1..MAX_APK) { "The release APK is missing trusted SHA-256 or size metadata." }
        AvailableRelease(tag, release.optString("body").take(8_000), url, digest, size)
    }

    suspend fun download(release: AvailableRelease): File = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "second-brain-" + release.digest + ".apk")
        if (target.exists()) {
            val good = runCatching { validate(target, release); true }.getOrDefault(false)
            if (good) return@withContext target
            target.delete()
        }
        val pending = File.createTempFile("download-", ".partial.apk", directory)
        try {
            val request = Request.Builder().url(release.url).header("User-Agent", "SecondBrain/${BuildConfig.VERSION_NAME}").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub APK download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("Empty APK download.")
                val (hash, size) = body.byteStream().use { source ->
                    pending.outputStream().use { destination -> digest(source, destination, release.size) }
                }
                require(hash == release.digest && size == release.size) { "The APK download failed SHA-256 verification." }
            }
            validateArchive(pending)
            check(pending.renameTo(target)) { "Couldn’t finish the downloaded file." }
            directory.listFiles()?.filter { it != target && it.name.endsWith(".apk") }?.forEach(File::delete)
            target
        } finally {
            pending.delete()
        }
    }

    suspend fun installIntent(file: File, release: AvailableRelease): Intent = withContext(Dispatchers.IO) {
        require(file.parentFile == directory)
        validate(file, release)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".update-files", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun allowInstallsIntent() = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))

    private suspend fun validate(file: File, release: AvailableRelease) {
        val (hash, size) = file.inputStream().use { digest(it, limit = release.size) }
        require(hash == release.digest && size == release.size) { "The APK failed SHA-256 verification." }
        validateArchive(file)
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(file: File) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = requireNotNull(manager.getPackageArchiveInfo(file.absolutePath, flags)) { "The archive is not a valid signed APK." }
        val current = manager.getPackageInfo(context.packageName, flags)
        require(apk.packageName == current.packageName) { "The APK has a different application ID." }
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(version(apk) > version(current)) { "The APK version code is not newer than the installed build." }
        fun signers(pkg: PackageInfo): Set<List<Byte>> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) pkg.signingInfo?.apkContentsSigners else pkg.signatures
            return signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList()
            }.toSet()
        }
        val expected = signers(current)
        require(expected.isNotEmpty() && signers(apk) == expected) {
            "The APK signer differs from this installation. Use a release with the same signing key."
        }
    }

    private companion object {
        const val MAX_APK = 160_000_000L
        val hosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    }
}

internal fun validRepository(value: String): Boolean =
    value.trim().trim('/').let {
        it.matches(Regex("[A-Za-z0-9_-]{1,100}/[A-Za-z0-9_.-]{1,100}")) &&
            it.substringAfter('/') !in setOf(".", "..")
    }

internal fun isNewRelease(tag: String, installed: String): Boolean {
    fun parts(value: String): List<Int>? {
        val digits = value.removePrefix("v").split('.')
        return digits.map { it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: return null }
            .takeIf { it.size in 2..4 }
    }
    val proposed = parts(tag) ?: return false
    val current = parts(installed) ?: return false
    for (index in 0 until maxOf(proposed.size, current.size)) {
        val difference = proposed.getOrElse(index) { 0 }.compareTo(current.getOrElse(index) { 0 })
        if (difference != 0) return difference > 0
    }
    return false
}
