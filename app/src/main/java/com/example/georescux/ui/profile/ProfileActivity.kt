package com.example.georescux.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.example.georescux.R
import com.example.georescux.data.profile.ProfileStore
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.io.FileOutputStream

/**
 * Profile screen: photo, phone number and email, stored device-locally.
 * The email is prefilled from the signed-in Firebase account when present.
 * The photo is picked from the gallery and copied into app-private storage
 * (no permissions needed, works offline).
 */
class ProfileActivity : AppCompatActivity() {

    private val profileStore by lazy { ProfileStore(this) }
    private var selectedImageUri: Uri? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            showImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val nameField = findViewById<EditText>(R.id.editProfileName)
        val phoneField = findViewById<EditText>(R.id.editProfilePhone)
        val emailField = findViewById<EditText>(R.id.editProfileEmail)
        val imageView = findViewById<ImageView>(R.id.imageProfile)

        val saved = profileStore.load()
        nameField.setText(saved.name)
        phoneField.setText(saved.phone)
        // Prefill from the signed-in account until the user sets their own.
        emailField.setText(
            saved.email.ifBlank { FirebaseAuth.getInstance().currentUser?.email.orEmpty() }
        )
        saved.imagePath?.let { path ->
            val file = File(path)
            if (file.exists()) showImage(Uri.fromFile(file))
        }

        findViewById<TextView>(R.id.textChangePhoto).setOnClickListener {
            imagePicker.launch("image/*")
        }

        findViewById<View>(R.id.buttonSaveProfile).setOnClickListener {
            val imagePath = selectedImageUri?.let { copyImageToPrivateStorage(it) }
                ?: saved.imagePath
            profileStore.save(
                ProfileStore.Profile(
                    name = nameField.text.toString().trim(),
                    phone = phoneField.text.toString().trim(),
                    email = emailField.text.toString().trim(),
                    imagePath = imagePath,
                )
            )
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showImage(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bitmap != null) {
                val circular = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                    isCircular = true
                }
                findViewById<ImageView>(R.id.imageProfile).setImageDrawable(circular)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies the picked image into app-private storage (survives restarts). */
    private fun copyImageToPrivateStorage(uri: Uri): String? = try {
        val target = File(filesDir, "profile_photo.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        // Downscale-decode for memory-friendliness on the display path.
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(target.absolutePath, options)?.let { bitmap ->
            FileOutputStream(target).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
        }
        target.absolutePath
    } catch (e: Exception) {
        null
    }
}
