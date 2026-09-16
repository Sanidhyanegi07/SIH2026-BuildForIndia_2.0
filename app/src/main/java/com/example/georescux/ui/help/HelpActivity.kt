package com.example.georescux.ui.help

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.georescux.R
import com.example.georescux.ui.common.BottomNav

/**
 * Global Help Center (spec §31). Explains the complete GeoRescuX system:
 * search plus a category filter over the FAQ items in [HelpContent].
 */
class HelpActivity : AppCompatActivity() {

    private val categoryFilter = mutableListOf<String>()
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        setupFilters()
        renderFaqs()

        BottomNav.bind(this, R.id.nav_home)
    }

    private fun setupFilters() {
        val categories = listOf(ALL_CATEGORIES) + HelpContent.Category.values().map { it.title }
        categoryFilter.clear()
        categoryFilter.addAll(categories)

        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerHelpCategory)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories,
        )
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                renderFaqs()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<android.widget.EditText>(R.id.editHelpSearch).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                    renderFaqs()
                }
            },
        )
    }

    private fun selectedCategoryTitle(): String? {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerHelpCategory)
        val position = spinner.selectedItemPosition
        if (position == 0 || position !in categoryFilter.indices) return null
        return categoryFilter[position]
    }

    private fun renderFaqs() {
        val container = findViewById<android.widget.LinearLayout>(R.id.helpListContainer)
        val empty = findViewById<TextView>(R.id.textHelpEmpty)
        container.removeAllViews()

        val categoryTitle = selectedCategoryTitle()
        val visible = HelpContent.items.filter { faq ->
            val matchesCategory = categoryTitle == null || faq.category.title == categoryTitle
            val matchesSearch = searchQuery.isEmpty() ||
                faq.question.lowercase().contains(searchQuery) ||
                faq.answer.lowercase().contains(searchQuery)
            matchesCategory && matchesSearch
        }

        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE

        visible.forEach { faq ->
            val item = LayoutInflater.from(this)
                .inflate(R.layout.item_help, container, false) as android.widget.LinearLayout

            item.findViewById<TextView>(R.id.textFaqCategory).text = faq.category.title
            item.findViewById<TextView>(R.id.textFaqQuestion).text = faq.question
            item.findViewById<TextView>(R.id.textFaqAnswer).text = faq.answer

            item.setOnClickListener {
                val answer = item.findViewById<TextView>(R.id.textFaqAnswer)
                val toggle = item.findViewById<TextView>(R.id.textFaqToggle)
                val expanded = answer.visibility == View.VISIBLE
                answer.visibility = if (expanded) View.GONE else View.VISIBLE
                toggle.text = if (expanded) "▸" else "▾"
            }

            container.addView(item)
        }
    }

    private companion object {
        const val ALL_CATEGORIES = "All categories"
    }
}
