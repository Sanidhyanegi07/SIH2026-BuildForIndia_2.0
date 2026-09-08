package com.example.georescux.data.profile

import android.content.Context

/**
 * Local profile store (name, phone, email, photo). SharedPreferences-backed
 * like every other local store in the app; the profile is device-local and
 * never synced.
 */
class ProfileStore(context: Context) {

    data class Profile(
        val name: String = "",
        val phone: String = "",
        val email: String = "",
        val imagePath: String? = null,
    )

    private val prefs = context.getSharedPreferences("profile_store", Context.MODE_PRIVATE)

    fun load(): Profile = Profile(
        name = prefs.getString(KEY_NAME, "").orEmpty(),
        phone = prefs.getString(KEY_PHONE, "").orEmpty(),
        email = prefs.getString(KEY_EMAIL, "").orEmpty(),
        imagePath = prefs.getString(KEY_IMAGE, null),
    )

    fun save(profile: Profile) {
        prefs.edit()
            .putString(KEY_NAME, profile.name)
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_IMAGE, profile.imagePath)
            .apply()
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_EMAIL = "email"
        const val KEY_IMAGE = "image_path"
    }
}
