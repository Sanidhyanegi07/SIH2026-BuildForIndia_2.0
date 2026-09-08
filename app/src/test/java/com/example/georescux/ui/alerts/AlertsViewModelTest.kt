package com.example.georescux.ui.alerts

import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the read-only alerts history: repository order is preserved,
 * the newest alert is selected, empty history is handled, and the
 * ViewModel never mutates SOS records.
 */
class AlertsViewModelTest {

    private class FakeSosRepository(seed: List<SosEmergency>) : SosRepository {
        val historyRecords: MutableList<SosEmergency> = seed.toMutableList()
        var startCount = 0
        var completeCount = 0
        var locationUpdateCount = 0

        override fun getActiveEmergency(): SosEmergency? = null

        override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
            startCount++
            return null
        }

        override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
            completeCount++
            return null
        }

        override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
            locationUpdateCount++
        }

        override fun getHistory(): List<SosEmergency> = historyRecords.toList()
    }

    private fun alert(
        id: String,
        startedAtMs: Long,
        withLocation: Boolean = false,
    ): SosEmergency = SosEmergency(
        id = id,
        startedAtMs = startedAtMs,
        stoppedAtMs = startedAtMs + 120_000L,
        location = if (withLocation) {
            SosLocation(
                latitude = 52.5200,
                longitude = 13.4050,
                accuracyMeters = 12f,
                timestampMs = startedAtMs + 5_000L,
                provider = "gps",
            )
        } else {
            null
        },
        locationStatus = if (withLocation) SosLocationStatus.ACQUIRED else null,
    )

    @Test
    fun `history is exposed in repository order newest first`() {
        val newest = alert("alert-2", startedAtMs = 2000)
        val oldest = alert("alert-1", startedAtMs = 1000)
        val viewModel = AlertsViewModel(FakeSosRepository(seed = listOf(newest, oldest)))

        assertEquals(listOf(newest, oldest), viewModel.alerts.value)
    }

    @Test
    fun `empty history produces an empty state`() {
        val viewModel = AlertsViewModel(FakeSosRepository(seed = emptyList()))

        assertTrue(viewModel.alerts.value.isEmpty())
        assertNull(viewModel.latestAlert.value)
    }

    @Test
    fun `latest alert is the newest completed emergency`() {
        val newest = alert("alert-2", startedAtMs = 2000)
        val oldest = alert("alert-1", startedAtMs = 1000)
        val viewModel = AlertsViewModel(FakeSosRepository(seed = listOf(newest, oldest)))

        assertEquals(newest, viewModel.latestAlert.value)
    }

    @Test
    fun `alerts without location data are handled safely`() {
        val withoutLocation = alert("alert-1", startedAtMs = 1000, withLocation = false)
        val withLocation = alert("alert-2", startedAtMs = 2000, withLocation = true)
        val viewModel = AlertsViewModel(FakeSosRepository(seed = listOf(withoutLocation, withLocation)))

        val alerts = viewModel.alerts.value
        assertEquals(2, alerts.size)
        assertNull(alerts[0].location)
        assertEquals(SosLocationStatus.ACQUIRED, alerts[1].locationStatus)
    }

    @Test
    fun `the view model never mutates sos records`() {
        val newest = alert("alert-2", startedAtMs = 2000, withLocation = true)
        val oldest = alert("alert-1", startedAtMs = 1000)
        val repository = FakeSosRepository(seed = listOf(newest, oldest))

        AlertsViewModel(repository)

        assertEquals(0, repository.startCount)
        assertEquals(0, repository.completeCount)
        assertEquals(0, repository.locationUpdateCount)
        assertEquals(listOf(newest, oldest), repository.historyRecords)
    }
}
