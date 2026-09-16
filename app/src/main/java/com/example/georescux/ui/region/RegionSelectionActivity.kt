package com.example.georescux.ui.region

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.domain.routing.MapRegion
import com.example.georescux.ui.common.HelpLauncher
import com.example.georescux.ui.home.HomeActivity

/**
 * First-login region selection (spec §6).
 *
 * Shown once, right after a normal user's first successful login, so the
 * user picks the offline package their maps and routing will run from. The
 * architecture stays region/package based: the India-wide dataset is a
 * separate larger package and is never silently bundled into every APK.
 *
 * Only regions whose routing data is actually installed on this device can be
 * activated — the rest are listed with an honest "not downloaded" status and
 * are not selectable, so the app never pretends offline routing exists where
 * the data is missing (spec §17).
 */
class RegionSelectionActivity : AppCompatActivity() {

    private val container by lazy { (application as GeoRescuXApplication).appContainer }
    private var selectedRegionId: String = container.activeRegionId
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_region_selection)

        findViewById<TextView>(R.id.topBarTitle).setText(R.string.region_select_title)
        HelpLauncher.bind(this)

        renderRegions()

        findViewById<android.widget.EditText>(R.id.editRegionSearch).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                    renderRegions()
                }
            },
        )

        findViewById<View>(R.id.buttonContinue).setOnClickListener { continueWithSelection() }
    }

    private fun renderRegions() {
        val container = findViewById<LinearLayout>(R.id.regionListContainer)
        container.removeAllViews()

        MapRegionCatalog.availableRegions
            .filter { it.displayName.lowercase().contains(searchQuery) }
            .forEach { region -> container.addView(buildRegionRow(region)) }
    }

    private fun buildRegionRow(region: MapRegion): LinearLayout {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_region, null, false) as LinearLayout

        val graphInstalled = MapRegionCatalog.isGraphInstalled(assets, region)
        val mapInstalled = MapRegionCatalog.isOfflineMapInstalled(assets, region)

        val name = row.findViewById<TextView>(R.id.regionName)
        val status = row.findViewById<TextView>(R.id.regionStatus)
        val dot = row.findViewById<View>(R.id.regionSelectionDot)

        name.text = region.displayName
        status.text = when {
            graphInstalled && mapInstalled -> getString(R.string.region_status_installed)
            graphInstalled -> getString(R.string.region_status_map_missing)
            else -> getString(R.string.region_status_not_installed)
        }
        status.setTextColor(
            getTextColor(
                if (graphInstalled) R.color.safe_green else R.color.text_muted,
            ),
        )

        val installed = graphInstalled
        row.isEnabled = installed
        row.alpha = if (installed) 1f else 0.55f

        if (installed && region.id == selectedRegionId) {
            dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                getTextColor(R.color.violet_light),
            )
        }

        row.setOnClickListener {
            if (!installed) {
                Toast.makeText(
                    this,
                    "${region.displayName} offline data is not downloaded yet.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            selectedRegionId = region.id
            renderRegions()
        }

        return row
    }

    private fun continueWithSelection() {
        val region = MapRegionCatalog.byId(selectedRegionId)
        if (region == null || !MapRegionCatalog.isGraphInstalled(assets, region)) {
            Toast.makeText(this, "Please select an installed region.", Toast.LENGTH_SHORT).show()
            return
        }
        container.setActiveRegion(region.id)
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun getTextColor(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)
}
