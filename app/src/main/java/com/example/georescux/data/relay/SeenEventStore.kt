package com.example.georescux.data.relay

/**
 * Interface for durable seen-event persistence surviving process restarts.
 */
interface SeenEventStore {
    fun loadAllSeenEventIds(): Set<String>
    fun saveSeenEventId(eventId: String, timestampMs: Long = System.currentTimeMillis())
    fun clearExpiredBefore(cutoffMs: Long)
}
