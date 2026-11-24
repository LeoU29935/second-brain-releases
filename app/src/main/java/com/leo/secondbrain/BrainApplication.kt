package com.leo.secondbrain

import android.app.Application
import android.app.WallpaperManager
import android.content.res.Configuration
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.leo.secondbrain.data.BrainDatabase
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.EditorJournal
import com.leo.secondbrain.data.PreferenceStore
import com.leo.secondbrain.data.TrashCleanup
import com.leo.secondbrain.data.migration1To2
import com.leo.secondbrain.data.migration2To3
import com.leo.secondbrain.data.migration3To4
import com.leo.secondbrain.data.migration4To5
import com.leo.secondbrain.data.migration5To6
import com.leo.secondbrain.data.migration6To7
import com.leo.secondbrain.data.migration7To8
import com.leo.secondbrain.data.migration8To9
import com.leo.secondbrain.ai.AutomaticOrganization
import com.leo.secondbrain.reminders.Reminders
import com.leo.secondbrain.capture.RecordingStore
import com.leo.secondbrain.capture.CaptureController
import com.leo.secondbrain.capture.MicWidget
import com.leo.secondbrain.ai.CredentialStore
import com.leo.secondbrain.ai.ChatGptSubscription
import com.leo.secondbrain.ai.LocalTranscription
import com.leo.secondbrain.ai.OpenAiClient
import com.leo.secondbrain.ai.ClaudeClient
import com.leo.secondbrain.ai.Organizer
import com.leo.secondbrain.backup.Backups
import com.leo.secondbrain.updates.ReleaseUpdater
import kotlinx.coroutines.flow.MutableStateFlow

internal class BrainApplication : Application() {
    val mainActivityResumed = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        TrashCleanup.schedule(this)
        AutomaticOrganization.schedule(this)
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching {
                WallpaperManager.getInstance(this).addOnColorsChangedListener(
                    { _, which -> if (which and WallpaperManager.FLAG_SYSTEM != 0) MicWidget.refresh(this) },
                    android.os.Handler(android.os.Looper.getMainLooper()))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        MicWidget.refresh(this)
    }

    val edits by lazy { EditorJournal(this, repository) { reminders.refreshOrRetry() } }
    val recordings by lazy { RecordingStore(this) }
    val capture by lazy { CaptureController(this, recordings, repository, automaticOrganization::kick) }
    val credentials by lazy { CredentialStore(this) }
    val subscription by lazy { ChatGptSubscription(this) }
    val ai by lazy { OpenAiClient(credentials) }
    val claude by lazy { ClaudeClient(this) }
    val localTranscription by lazy { LocalTranscription(assets) }
    val organizer by lazy { Organizer(ai, subscription, claude, repository) }
    val automaticOrganization by lazy { AutomaticOrganization(this) }
    val backups by lazy { Backups(this, repository, recordings) }
    val updates by lazy { ReleaseUpdater(this) }
    val reminders by lazy { Reminders(this, repository) }
    val preferences by lazy { PreferenceStore(this) }
    val repository by lazy {
        val database = Room.databaseBuilder(this, BrainDatabase::class.java, "second-brain.db")
            .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6, migration6To7, migration7To8, migration8To9)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """INSERT INTO collections (id, name, description, color, parentId) VALUES
                           ('personal', 'Personal', 'Notes and tasks for everyday life', 1, NULL),
                           ('projects', 'Projects', 'Plans and work in progress', 0, NULL),
                           ('ideas', 'Ideas', 'A place for ideas worth keeping', 2, 'projects')"""
                    )
                }
            })
            .build()
        BrainRepository(database) { name ->
            runCatching { recordings.file(name).delete() }
                .onFailure { android.util.Log.w("SecondBrain", "Could not remove deleted recording", it) }
        }
    }
}
