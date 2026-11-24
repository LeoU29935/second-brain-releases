package com.leo.secondbrain.backup

import android.content.Context
import android.net.Uri
import com.leo.secondbrain.capture.RecordingStore
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.WorkspaceSnapshot
import com.leo.secondbrain.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

internal data class BackupPreview(
    val entries: Int, val events: Int, val collections: Int, val tags: Int, val captures: Int, val recordings: Int
)

internal class Backups(
    private val context: Context, private val repository: BrainRepository, private val recordings: RecordingStore
) {
    suspend fun export(uri: Uri, includeAudio: Boolean): String = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot(includeDumps = true)
        val files = if (includeAudio) snapshot.dumps.mapNotNull { it.recording }.distinct()
            .map(recordings::file).filter { it.isFile } else emptyList()
        val media = files.map { file ->
            BackupMedia(file.name, file.length(), file.inputStream().use { digest(it, limit = FILE_LIMIT).first })
        }
        require(media.sumOf { it.length } <= AUDIO_LIMIT) { "Recordings exceed 200 MB. Export workspace data without audio." }
        val metadata = BackupCodec.encode(snapshot, media).toString().toByteArray()
        require(metadata.size <= MANIFEST_LIMIT) { "The workspace is too large for one portable backup." }
        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Couldn’t open the backup destination." }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(metadata)
            zip.closeEntry()
            files.forEach { file ->
                coroutineContext.ensureActive()
                zip.putNextEntry(ZipEntry("recordings/${file.name}"))
                file.inputStream().use { digest(it, zip, FILE_LIMIT) }
                zip.closeEntry()
            }
        }
        "Backup exported with ${media.size} recordings."
    }

    suspend fun preview(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        open(uri).use { zip ->
            val (snapshot, media) = manifest(zip)
            BackupPreview(snapshot.entries.size, snapshot.events.size, snapshot.collections.size,
                snapshot.tags.size, snapshot.dumps.size, media.size)
        }
    }

    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore-" + newId())
        val installed = mutableListOf<File>()
        var committed = false
        check(temp.mkdir())
        try {
            val (snapshot, media) = open(uri).use { zip ->
                val (snapshot, media) = manifest(zip)
                val pending = media.associateBy { "recordings/${it.name}" }.toMutableMap()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val info = requireNotNull(pending.remove(entry.name)) { "Unknown or duplicate backup attachment." }
                    val file = File(temp, info.name)
                    val (sha, bytes) = file.outputStream().use { digest(zip, it, info.length) }
                    require(sha == info.sha256 && bytes == info.length) { "A recording failed integrity verification." }
                    zip.closeEntry()
                }
                require(pending.isEmpty()) { "The backup is missing a recording." }
                snapshot to media
            }
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
              val existing = repository.snapshot(includeDumps = true).dumps.map { it.id }.toSet()
              val needed = snapshot.dumps.filterNot { it.id in existing }.mapNotNull { it.recording }.toSet()
              val names = media.filter { it.name in needed }.associate { info ->
                val destination = recordings.file(info.name)
                val matching = destination.isFile && destination.length() == info.length &&
                    destination.inputStream().use { digest(it, limit = FILE_LIMIT).first } == info.sha256
                val chosen = if (destination.exists() && !matching) recordings.file(newId() + ".m4a") else destination
                if (!matching) {
                    installed += chosen
                    File(temp, info.name).copyTo(chosen)
                }
                info.name to chosen.name
              }
              repository.mergeBackup(snapshot.copy(dumps = snapshot.dumps.map { it.copy(recording = names[it.recording]) }))
                  .also { committed = true }
            }
        } catch (error: Exception) {
            if (!committed) installed.forEach(File::delete)
            throw error
        } finally {
            temp.listFiles()?.forEach(File::delete)
            temp.delete()
        }
    }

    private fun open(uri: Uri) = ZipInputStream(requireNotNull(context.contentResolver.openInputStream(uri)) {
        "Couldn’t read the selected backup."
    }.buffered())

    private suspend fun manifest(zip: ZipInputStream): Pair<WorkspaceSnapshot, List<BackupMedia>> {
        require(zip.nextEntry?.name == MANIFEST) { "Select a Second Brain backup ZIP." }
        val bytes = ByteArrayOutputStream()
        digest(zip, bytes, MANIFEST_LIMIT.toLong())
        zip.closeEntry()
        return BackupCodec.decode(JSONObject(bytes.toString(Charsets.UTF_8.name()))).also {
            require(it.second.sumOf(BackupMedia::length) <= AUDIO_LIMIT) { "Backup recordings exceed 200 MB." }
        }
    }

    private companion object {
        const val MANIFEST = "workspace.json"
        const val MANIFEST_LIMIT = 24_000_000
        const val FILE_LIMIT = 20_000_000L
        const val AUDIO_LIMIT = 200_000_000L
    }
}

internal suspend fun digest(input: InputStream, destination: OutputStream? = null, limit: Long): Pair<String, Long> {
    val hash = MessageDigest.getInstance("SHA-256")
    var total = 0L
    val bytes = ByteArray(32 * 1024)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(bytes)
        if (count < 0) break
        total += count
        require(total <= limit) { "The file exceeds the supported size." }
        hash.update(bytes, 0, count)
        destination?.write(bytes, 0, count)
    }
    return hash.digest().joinToString("") { "%02x".format(it) } to total
}
