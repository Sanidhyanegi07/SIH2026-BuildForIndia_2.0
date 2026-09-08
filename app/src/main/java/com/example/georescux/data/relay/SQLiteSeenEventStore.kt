package com.example.georescux.data.relay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite single-entity database implementation of [SeenEventStore].
 * Persists seen event IDs durably to disk so deduplication state survives process death.
 */
class SQLiteSeenEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION),
    SeenEventStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SEEN_EVENTS (
                $COL_EVENT_ID TEXT PRIMARY KEY,
                $COL_SEEN_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SEEN_EVENTS")
        onCreate(db)
    }

    override fun loadAllSeenEventIds(): Set<String> {
        val db = readableDatabase
        val ids = mutableSetOf<String>()
        val cursor = db.query(
            TABLE_SEEN_EVENTS,
            arrayOf(COL_EVENT_ID),
            null,
            null,
            null,
            null,
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(0))
            }
        }
        return ids
    }

    override fun saveSeenEventId(eventId: String, timestampMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_EVENT_ID, eventId)
            put(COL_SEEN_AT, timestampMs)
        }
        db.insertWithOnConflict(TABLE_SEEN_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun clearExpiredBefore(cutoffMs: Long) {
        val db = writableDatabase
        db.delete(TABLE_SEEN_EVENTS, "$COL_SEEN_AT < ?", arrayOf(cutoffMs.toString()))
    }

    companion object {
        const val DB_NAME = "georescux_relay_seen_events.db"
        const val DB_VERSION = 1

        const val TABLE_SEEN_EVENTS = "seen_events"
        const val COL_EVENT_ID = "event_id"
        const val COL_SEEN_AT = "seen_at_ms"
    }
}
