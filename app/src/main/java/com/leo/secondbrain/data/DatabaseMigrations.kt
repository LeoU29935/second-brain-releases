package com.leo.secondbrain.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val migration8To9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN durationMinutes INTEGER")
        db.execSQL("ALTER TABLE entries ADD COLUMN recurrenceRule TEXT")
        db.execSQL("ALTER TABLE entries ADD COLUMN inferredSchedule TEXT")
    }
}

internal val migration7To8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN hardDeadline INTEGER")
        db.execSQL("""CREATE TABLE IF NOT EXISTS assistant_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, role TEXT NOT NULL,
            text TEXT NOT NULL, targetId TEXT, createdAt INTEGER NOT NULL)""")
    }
}

internal val migration6To7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN timeOfDay TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN timeOfDay TEXT")
    }
}

internal val migration4To5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS capture_runs (
            dumpId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, message TEXT,
            results TEXT NOT NULL, attempts INTEGER NOT NULL, zone TEXT,
            FOREIGN KEY(dumpId) REFERENCES brain_dumps(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}

internal val migration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS recurrence_skips (
               seriesId TEXT NOT NULL, day INTEGER NOT NULL, PRIMARY KEY(seriesId, day),
               FOREIGN KEY(seriesId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)"""
        )
    }
}

internal val migration2To3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE collections ADD COLUMN icon TEXT NOT NULL DEFAULT 'FOLDER'")
        listOf("collections", "entries", "events").forEach { table ->
            db.execSQL("ALTER TABLE $table ADD COLUMN trashId TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_trashId ON $table(trashId)")
        }
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS trash (
               id TEXT NOT NULL PRIMARY KEY, rootId TEXT NOT NULL, kind TEXT NOT NULL,
               title TEXT NOT NULL, deletedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trash_deletedAt ON trash(deletedAt)")
    }
}

internal val migration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN dueMinute INTEGER")
        db.execSQL("ALTER TABLE entries ADD COLUMN reminder INTEGER")
        db.execSQL("ALTER TABLE entries ADD COLUMN recurrenceAnchor INTEGER")
        db.execSQL("ALTER TABLE entries ADD COLUMN repeat TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS events (
               id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL,
               startDay INTEGER NOT NULL, startMinute INTEGER, endDay INTEGER NOT NULL, endMinute INTEGER,
               allDay INTEGER NOT NULL, location TEXT NOT NULL, repeat TEXT NOT NULL, reminder INTEGER,
               collectionId TEXT, updatedAt INTEGER NOT NULL,
               FOREIGN KEY(collectionId) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE SET NULL
               )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_collectionId ON events(collectionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_startDay ON events(startDay)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS event_tags (
               eventId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(eventId, tagId),
               FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE,
               FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
               )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_event_tags_tagId ON event_tags(tagId)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS brain_dumps (
               id TEXT NOT NULL PRIMARY KEY, original TEXT NOT NULL, recording TEXT, transcript TEXT,
               status TEXT NOT NULL, createdAt INTEGER NOT NULL
               )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS drafts (
               id TEXT NOT NULL PRIMARY KEY, dumpId TEXT NOT NULL, original TEXT NOT NULL,
               proposal TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
               FOREIGN KEY(dumpId) REFERENCES brain_dumps(id) ON UPDATE NO ACTION ON DELETE CASCADE
               )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drafts_dumpId ON drafts(dumpId)")
    }
}
internal val migration5To6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("collections", "entries", "events").forEach { table ->
            db.execSQL("ALTER TABLE $table ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE entries ADD COLUMN flexibleSchedule TEXT")
        db.execSQL("ALTER TABLE entries ADD COLUMN scheduleChange TEXT")
        db.execSQL("ALTER TABLE brain_dumps ADD COLUMN trashId TEXT")
    }
}
