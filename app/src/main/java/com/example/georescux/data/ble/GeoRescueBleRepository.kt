package com.example.georescux.data.ble

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.georescux.domain.ble.GeoRescueBleOutboundPacketQueue
import com.example.georescux.domain.ble.GeoRescueBleSeenPacketStore
import com.example.georescux.domain.ble.QueuedPacket

/**
 * Phase 6/7/8 — the durable BLE packet persistence:
 *
 * - seen-packet table: deduplication state that survives process death
 *   (same pattern as the relay engine's SQLiteSeenEventStore);
 * - outbound-queue table: store-and-forward rows for packets no peer could
 *   take yet, flushed when a connection becomes READY.
 *
 * Both live in one SQLite database owned by the BLE subsystem.
 */
class GeoRescueBleRepository(context: Context) : GeoRescueBleSeenPacketStore, GeoRescueBleOutboundPacketQueue {

    private val helper: SQLiteOpenHelper

    init {
        helper = DbHelper(context.applicationContext)
    }

    private inner class DbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_SEEN (
                    $COL_SEEN_PACKET_ID TEXT PRIMARY KEY,
                    $COL_SEEN_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE $TABLE_OUTBOUND (
                    $COL_OUT_PACKET_ID TEXT PRIMARY KEY,
                    $COL_OUT_JSON TEXT NOT NULL,
                    $COL_OUT_QUEUED_AT INTEGER NOT NULL,
                    $COL_OUT_ATTEMPTS INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SEEN")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_OUTBOUND")
            onCreate(db)
        }
    }

    // --- GeoRescueBleSeenPacketStore ---

    override fun loadAllSeenPacketIds(): Set<String> {
        val db = helper.readableDatabase
        val ids = mutableSetOf<String>()
        db.query(TABLE_SEEN, arrayOf(COL_SEEN_PACKET_ID), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    override fun saveSeenPacketId(packetId: String, timestampMs: Long) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_SEEN,
            null,
            ContentValues().apply {
                put(COL_SEEN_PACKET_ID, packetId)
                put(COL_SEEN_AT, timestampMs)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    override fun clearExpiredBefore(cutoffMs: Long) {
        helper.writableDatabase.delete(TABLE_SEEN, "$COL_SEEN_AT < ?", arrayOf(cutoffMs.toString()))
    }

    // --- GeoRescueBleOutboundPacketQueue ---

    override fun loadQueued(): List<QueuedPacket> {
        val db = helper.readableDatabase
        val rows = mutableListOf<QueuedPacket>()
        db.query(
            TABLE_OUTBOUND,
            arrayOf(COL_OUT_PACKET_ID, COL_OUT_JSON, COL_OUT_QUEUED_AT, COL_OUT_ATTEMPTS),
            null, null, null, null, "$COL_OUT_QUEUED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    QueuedPacket(
                        packetId = cursor.getString(0),
                        packetJson = cursor.getString(1),
                        queuedAtMs = cursor.getLong(2),
                        attempts = cursor.getInt(3),
                    )
                )
            }
        }
        return rows
    }

    override fun enqueue(packetId: String, packetJson: String, queuedAtMs: Long) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_OUTBOUND,
            null,
            ContentValues().apply {
                put(COL_OUT_PACKET_ID, packetId)
                put(COL_OUT_JSON, packetJson)
                put(COL_OUT_QUEUED_AT, queuedAtMs)
                put(COL_OUT_ATTEMPTS, 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun remove(packetId: String) {
        helper.writableDatabase.delete(TABLE_OUTBOUND, "$COL_OUT_PACKET_ID = ?", arrayOf(packetId))
    }

    companion object {
        const val DB_NAME = "georescux_ble_packets.db"
        const val DB_VERSION = 1

        const val TABLE_SEEN = "ble_seen_packets"
        const val COL_SEEN_PACKET_ID = "packet_id"
        const val COL_SEEN_AT = "seen_at_ms"

        const val TABLE_OUTBOUND = "ble_outbound_queue"
        const val COL_OUT_PACKET_ID = "packet_id"
        const val COL_OUT_JSON = "packet_json"
        const val COL_OUT_QUEUED_AT = "queued_at_ms"
        const val COL_OUT_ATTEMPTS = "attempts"
    }
}
