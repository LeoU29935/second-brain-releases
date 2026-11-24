package com.leo.secondbrain.ui

import android.os.SystemClock
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.ai.AiUnavailable
import com.leo.secondbrain.updates.AvailableRelease
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.Preferences
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.EventTag
import com.leo.secondbrain.data.TrashKind
import com.leo.secondbrain.data.WorkspaceUndo
import com.leo.secondbrain.data.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LibraryState(
    val collections: List<CollectionEntity> = emptyList(),
    val entries: List<TaggedEntry> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val events: List<Event> = emptyList(),
    val eventTags: List<EventTag> = emptyList(),
    val recurrenceSkips: Map<String, Set<Long>> = emptyMap()
)

internal class BrainViewModel(val app: BrainApplication) : ViewModel() {
    data class Credential(val configured: Boolean, val busy: Boolean = false, val message: String? = null)
    data class UpdateState(val checked: Boolean = false, val checking: Boolean = false,
        val release: AvailableRelease? = null, val failed: Boolean = false)
    private val mutableUpdate = MutableStateFlow(UpdateState())
    val update = mutableUpdate.asStateFlow()
    private var lastUpdateCheck: Long? = null

    fun checkUpdates(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val recentlyChecked = lastUpdateCheck?.let { now - it < 60_000L } == true
        if (!app.updates.configured || mutableUpdate.value.checking || (recentlyChecked && !force)) return
        lastUpdateCheck = now
        mutableUpdate.value = mutableUpdate.value.copy(checking = true, failed = false)
        viewModelScope.launch {
            try {
                mutableUpdate.value = UpdateState(checked = true, release = app.updates.check())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUpdate.value = mutableUpdate.value.copy(checked = true, checking = false, failed = true)
            }
        }
    }

    val repository = app.repository
    fun purgeExpiredItems() {
        viewModelScope.launch {
            try {
                repository.purgeExpired()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SecondBrain", "Could not purge expired deleted items", error)
            }
        }
    }
    private val mutableUndo = MutableStateFlow<WorkspaceUndo?>(null)
    val undo = mutableUndo.asStateFlow()

    fun offerUndo(action: WorkspaceUndo?) {
        if (action != null) mutableUndo.value = action
    }

    fun finishUndo(action: WorkspaceUndo, perform: Boolean) {
        if (mutableUndo.value !== action) return
        mutableUndo.value = null
        if (perform) viewModelScope.launch {
            try {
                app.edits.flush()
                action.run()
                refreshReminders()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportWriteError(error)
            }
        }
    }

    fun deleteWithUndo(id: String, kind: TrashKind? = null, onDeleted: () -> Unit = {}) {
        write(onDeleted) {
            app.edits.delete(id) { offerUndo(repository.deleteWithUndo(id, kind)) }
        }
    }
    val dumps = repository.dumps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val drafts = repository.drafts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val captureRuns = repository.captureRuns
        .combine(repository.dumps) { runs, dumps ->
            val ids = dumps.map { it.id }.toSet()
            runs.filter { it.dumpId in ids }.associateBy { it.dumpId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private val mutableCredential = MutableStateFlow(Credential(app.credentials.exists()))
    val credential = mutableCredential.asStateFlow()
    val chatGptSettings = app.subscription.settings
    private val mutableChatGptAction = MutableStateFlow(Credential(false))
    val chatGptAction = mutableChatGptAction.asStateFlow()
    private var chatGptJob: Job? = null

    fun loadChatGpt() = changeChatGpt { app.subscription.load(); null }
    fun signInWithChatGpt(add: Boolean, openBrowser: (Uri) -> Unit) = changeChatGpt {
        app.subscription.signIn(add, awaitReturn = { app.mainActivityResumed.first { it } }) { uri ->
            mutableChatGptAction.value = Credential(false, busy = true,
                message = "Finish in your browser, then tap Open Second Brain to check the connection.")
            openBrowser(uri)
        }
        if (app.subscription.settings.value.active?.planAllowed == true) "ChatGPT is connected."
        else "Signed in. To organize memos, reconnect and allow ChatGPT plan usage."
    }
    fun cancelChatGptAction() { chatGptJob?.cancel() }
    fun selectChatGptAccount(id: String) = changeChatGpt { app.subscription.selectAccount(id); null }
    fun dismissChatGptNotice() = changeChatGpt { app.subscription.dismissNotice(); null }
    fun showChatGptModels() = changeChatGpt { app.subscription.refreshModels(); null }
    fun selectChatGptModel(slug: String) = changeChatGpt { app.subscription.selectModel(slug); null }
    fun useOpenAiKey() = changeChatGpt { app.subscription.useApiKey(); null }
    fun signOutOfChatGpt() = changeChatGpt {
        if (app.subscription.signOut()) "Signed out of ChatGPT."
        else "Signed out on this device. Open Manage usage to review the connection in ChatGPT."
    }

    private fun changeChatGpt(action: suspend () -> String?) {
        if (chatGptJob?.isActive == true) return
        chatGptJob = viewModelScope.launch {
            mutableChatGptAction.value = Credential(false, busy = true)
            var message: String? = null
            try {
                message = action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AiUnavailable) {
                message = error.message
            } catch (_: Exception) {
                message = "Couldn’t complete the ChatGPT action. Try again."
            } finally {
                mutableChatGptAction.value = Credential(false, message = message)
            }
        }
    }
    private val preferenceStore = app.preferences
    val preferences = preferenceStore.preferences
    val library = combine(repository.collections, repository.entries, repository.tags,
        repository.events, repository.eventTags) { collections, entries, tags, events, eventTags ->
        LibraryState(collections, entries, tags, loading = false, events = events, eventTags = eventTags)
    }.combine(repository.recurrenceSkips) { state, skips ->
        state.copy(recurrenceSkips = skips.groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() })
    }.distinctUntilChanged().catch { error ->
        Log.e("SecondBrain", "Could not load the library", error)
        emit(LibraryState(loading = false, loadFailed = true))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun editOccurrence(seriesId: String, day: Long, onReady: (String) -> Unit) {
        val id = newId()
        write({ onReady(id) }) {
            app.edits.flush()
            repository.separateOccurrence(seriesId, day, id)
        }
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun setPreferences(preferences: Preferences) {
        if (!preferenceStore.update(preferences)) mutableError.value = "Couldn’t save the preference. Try again."
    }

    fun rememberSelectedFolder(level: String, id: String) {
        val current = preferenceStore.preferences.value
        if (current.selectedFolders[level] != id) {
            setPreferences(current.copy(selectedFolders = current.selectedFolders + (level to id)))
        }
    }

    suspend fun deleteVoiceCaptures(ids: Set<String>) {
        withContext(Dispatchers.IO) {
            repository.deleteVoiceCaptures(ids).forEach { name ->
                val recording = app.recordings.file(name)
                if (recording.isFile && !recording.delete()) {
                    mutableError.value = "The capture was removed. Its audio file could not be cleaned up."
                }
            }
        }
    }

    fun retryCapture(id: String) {
        write {
            app.subscription.retryAfterUsageLimit()
            repository.retryCapture(id)
            app.automaticOrganization.kick()
        }
    }

    fun saveCredential(secret: String) = changeCredential("API key saved.") { app.credentials.save(secret) }

    fun removeCredential() {
        changeCredential("API key removed.") { app.credentials.remove() }
    }

    private fun changeCredential(success: String, change: () -> Unit) {
        if (mutableCredential.value.busy) return
        mutableCredential.value = mutableCredential.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { change(); success }.getOrElse { "Could not update the key. Check the API key format." }
            }
            mutableCredential.value = Credential(app.credentials.exists(), message = message)
        }
    }

    fun setCompleted(id: String, completed: Boolean, deadline: Long?) {
        viewModelScope.launch {
            try {
                app.edits.flush()
                offerUndo(repository.completeWithUndo(id, completed, deadline))
                refreshReminders()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportWriteError(error)
            }
        }
    }

    fun write(onSuccess: () -> Unit = {}, operation: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                operation()
                onSuccess()
                refreshReminders()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportWriteError(error)
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun refreshReminders() {
        app.reminders.requestRefresh()
    }

    private fun reportWriteError(error: Exception) {
        Log.e("SecondBrain", "Could not save changes", error)
        mutableError.value = if (error is IllegalArgumentException) {
            error.message?.takeUnless { it == "Failed requirement." } ?: "Check the item’s fields and try again."
        } else {
            "Couldn’t save changes. Try again."
        }
    }

}
