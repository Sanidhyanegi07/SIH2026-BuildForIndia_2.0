package com.example.georescux.data.contacts

import android.content.Context
import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed contact store. Uses a dedicated
 * "contacts_store" preferences file and one JSON array per user, keyed by
 * "contacts_{uid}".
 */
class SharedPreferencesContactStore(context: Context) : ContactLocalStore {

    private val prefs = context.getSharedPreferences("contacts_store", Context.MODE_PRIVATE)

    override fun loadContacts(uid: String): List<EmergencyContact> {
        val array = JSONArray(prefs.getString(key(uid), "[]"))
        return (0 until array.length()).map { index -> fromJson(array.getJSONObject(index)) }
    }

    override fun saveContacts(uid: String, contacts: List<EmergencyContact>) {
        val array = JSONArray()
        contacts.forEach { array.put(toJson(it)) }
        prefs.edit().putString(key(uid), array.toString()).apply()
    }

    private fun key(uid: String) = "contacts_$uid"

    private fun toJson(contact: EmergencyContact): JSONObject = JSONObject().apply {
        put("id", contact.id)
        put("name", contact.name)
        put("phoneNumber", contact.phoneNumber)
        put("relationship", contact.relationship)
        put("priority", contact.priority.name)
    }

    private fun fromJson(json: JSONObject): EmergencyContact = EmergencyContact(
        id = json.getString("id"),
        name = json.getString("name"),
        phoneNumber = json.getString("phoneNumber"),
        relationship = json.optString("relationship", ""),
        priority = runCatching { ContactPriority.valueOf(json.getString("priority")) }
            .getOrDefault(ContactPriority.SECONDARY),
    )
}
