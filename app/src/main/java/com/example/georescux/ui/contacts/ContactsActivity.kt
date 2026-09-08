package com.example.georescux.ui.contacts

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.core.validation.ContactInputValidator
import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Emergency Contacts screen: list, add, and delete contacts for the
 * signed-in user. Everything goes through [ContactsViewModel]; the screen
 * contains no storage, Firebase, or primary-rule logic.
 */
class ContactsActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var viewModel: ContactsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        val container = (application as GeoRescuXApplication).appContainer
        viewModel = ViewModelProvider(this, ContactsViewModel.Factory(container.contactsRepository))
            .get(ContactsViewModel::class.java)

        val nameInput = findViewById<EditText>(R.id.editTextContactName)
        val phoneInput = findViewById<EditText>(R.id.editTextContactPhone)
        val relationshipInput = findViewById<EditText>(R.id.editTextContactRelationship)
        val primaryRadio = findViewById<RadioButton>(R.id.radioButtonPrimary)
        val errorText = findViewById<TextView>(R.id.textViewContactError)
        val contactsListContainer = findViewById<LinearLayout>(R.id.contactsListContainer)
        val emptyText = findViewById<TextView>(R.id.textViewEmptyContacts)

        findViewById<Button>(R.id.buttonAddContact).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val relationship = relationshipInput.text.toString().trim()

            val validationError = ContactInputValidator.validateContact(name, phone, relationship)
            if (validationError != null) {
                errorText.text = validationError
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorText.visibility = View.GONE

            val priority =
                if (primaryRadio.isChecked) ContactPriority.PRIMARY else ContactPriority.SECONDARY
            viewModel.addContact(
                EmergencyContact(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    phoneNumber = phone,
                    relationship = relationship,
                    priority = priority,
                )
            )

            nameInput.text.clear()
            phoneInput.text.clear()
            relationshipInput.text.clear()
            primaryRadio.isChecked = false
            findViewById<RadioButton>(R.id.radioButtonSecondary).isChecked = true
        }

        uiScope.launch {
            viewModel.contacts.collect { contacts ->
                emptyText.visibility =
                    if (contacts.isEmpty()) View.VISIBLE else View.GONE
                contactsListContainer.removeAllViews()
                contacts.forEach { contact ->
                    contactsListContainer.addView(contactRow(contact) { id ->
                        viewModel.deleteContact(id)
                    })
                }
            }
        }
    }

    private fun contactRow(
        contact: EmergencyContact,
        onDelete: (String) -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_contact, null, false)
        row.findViewById<TextView>(R.id.textContactName).text = contact.name
        row.findViewById<TextView>(R.id.textContactPriority).apply {
            text = contact.priority.name
            setTextColor(
                if (contact.priority == ContactPriority.PRIMARY) 0xFFB71C1C.toInt() else 0xFF757575.toInt()
            )
        }
        row.findViewById<TextView>(R.id.textContactPhone).text = contact.phoneNumber
        row.findViewById<TextView>(R.id.textContactRelationship).text = contact.relationship
        row.findViewById<Button>(R.id.buttonDeleteContact).setOnClickListener {
            onDelete(contact.id)
        }
        return row
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
