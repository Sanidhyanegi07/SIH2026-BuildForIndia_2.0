package com.example.georescux.ui.profile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.data.profile.ProfileStore
import com.example.georescux.ui.auth.LoginActivity
import com.example.georescux.ui.common.ActiveSosBanner
import com.example.georescux.ui.common.BottomNav
import com.example.georescux.ui.common.HelpLauncher
import com.example.georescux.ui.contacts.ContactsActivity
import com.example.georescux.ui.region.RegionSelectionActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.io.FileOutputStream

/**
 * Profile screen (spec §30): photo, name, phone and email, stored
 * device-locally, plus the active offline region, identity verification and
 * quick links to Emergency Contacts and Log Out.
 *
 * Verification status syncs back here: when an administrator approves or
 * rejects the submitted ID in the admin portal, this screen reflects it
 * without the user having to reinstall (spec §29).
 */
class ProfileActivity : AppCompatActivity() {

    private val profileStore by lazy { ProfileStore(this) }
    private var selectedImageUri: Uri? = null
    private var currentVerificationDocPath: String? = null
    private var currentVerificationStatus: String = "UNVERIFIED"
    private var verificationListener: ValueEventListener? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            showImage(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        ActiveSosBanner.refresh(this)
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

        val verificationStatusText = findViewById<TextView>(R.id.textVerificationStatus)
        verificationStatusText.text = "Status: ${saved.verificationStatus}"

        currentVerificationDocPath = saved.verificationDocumentPath
        currentVerificationStatus = saved.verificationStatus

        // Top bar, help, navigation and the region/contacts/logout rows.
        findViewById<TextView>(R.id.topBarTitle).text = getString(R.string.nav_profile)
        HelpLauncher.bind(this)
        showActiveRegion()
        findViewById<LinearLayout>(R.id.rowRegion).setOnClickListener {
            startActivity(Intent(this, RegionSelectionActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowEmergencyContacts).setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
        findViewById<View>(R.id.buttonLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
        }
        BottomNav.bind(this, R.id.nav_profile)
        observeVerificationStatus(verificationStatusText)

        val documentPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                currentVerificationDocPath = copyImageToPrivateStorage(uri) // Reusing the copy method for simplicity
                currentVerificationStatus = "PENDING"
                verificationStatusText.text = "Status: PENDING"
                Toast.makeText(this, "Document uploaded for verification", Toast.LENGTH_SHORT).show()
                
                // Sync verification request to Firebase
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null) {
                    com.google.firebase.database.FirebaseDatabase.getInstance().getReference("verification_requests")
                        .child(uid)
                        .setValue(mapOf(
                            "uid" to uid, 
                            "status" to "PENDING", 
                            "timestampMs" to System.currentTimeMillis(),
                            "name" to nameField.text.toString().trim()
                        ))
                }
            }
        }

        findViewById<TextView>(R.id.textUploadDocument).setOnClickListener {
            documentPicker.launch("image/*") // Mocking document upload with image picker
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
                    verificationStatus = currentVerificationStatus,
                    verificationDocumentPath = currentVerificationDocPath,
                )
            )
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Reflects the currently active offline region (spec §30). */
    private fun showActiveRegion() {
        val container = (application as GeoRescuXApplication).appContainer
        val region = MapRegionCatalog.byId(container.activeRegionId)
        findViewById<TextView>(R.id.textProfileRegion).text = region?.displayName ?: "Not selected"
    }

    /**
     * Listens for the administrator's decision on this account's ID (spec
     * §29): only an admin can move a request out of Pending, and the outcome
     * is reflected here and persisted locally as soon as it lands.
     */
    private fun observeVerificationStatus(verificationStatusText: TextView) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("verification_requests").child(uid)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: return
                if (status == currentVerificationStatus) return
                currentVerificationStatus = status
                verificationStatusText.text = "Status: $status"
                verificationStatusText.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@ProfileActivity,
                        when (status) {
                            "VERIFIED" -> R.color.safe_green
                            "REJECTED" -> R.color.text_error
                            else -> R.color.warn_amber
                        },
                    ),
                )
                // Persist so the outcome survives a restart.
                val savedProfile = profileStore.load()
                profileStore.save(
                    savedProfile.copy(
                        verificationStatus = status,
                        verificationDocumentPath = currentVerificationDocPath,
                    ),
                )
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        verificationListener = listener
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

    override fun onDestroy() {
        verificationListener?.let { listener ->
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                try {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("verification_requests").child(uid)
                        .removeEventListener(listener)
                } catch (_: Exception) {
                }
            }
        }
        verificationListener = null
        super.onDestroy()
    }
}
