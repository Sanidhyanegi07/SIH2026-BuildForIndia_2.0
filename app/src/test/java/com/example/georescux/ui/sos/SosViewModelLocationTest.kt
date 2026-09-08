package com.example.georescux.ui.sos

import com.example.georescux.domain.repository.LocationRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.CancelSosUseCase
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import com.example.georescux.domain.sos.SosState
import com.example.georescux.domain.sos.StartSosUseCase
import com.example.georescux.domain.sos.StopSosUseCase
import com.example.georescux.domain.sos.UpdateSosLocationUseCase
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies how the SOS ViewModel handles location during an active
 * emergency: immediate first fix, throttled later fixes, permission-missing
 * and unavailable statuses — and that none of it ever stops the emergency.
 * Uses an Unconfined dispatcher because Dispatchers.Main is unavailable on
 * the JVM.
 */
class SosViewModelLocationTest {

    private class FakeSosRepository : SosRepository {
        var active: SosEmergency? = null
        var historyList = mutableListOf<SosEmergency>()
        var locationUpdateCount = 0
        var lastLocationUpdate: Pair<SosLocation?, SosLocationStatus>? = null

        override fun getActiveEmergency(): SosEmergency? = active

        override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
            if (active != null) return null
            val emergency = SosEmergency(id = id, startedAtMs = startedAtMs)
            active = emergency
            return emergency
        }

        override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
            val activeEmergency = active ?: return null
            val completed = activeEmergency.copy(stoppedAtMs = stoppedAtMs)
            active = null
            historyList.add(completed)
            return completed
        }

        override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
            val activeEmergency = active ?: return
            locationUpdateCount++
            lastLocationUpdate = location to status
            active = activeEmergency.copy(location = location, locationStatus = status)
        }

        override fun getHistory(): List<SosEmergency> = historyList.toList()
    }

    private class FakeLocationRepository : LocationRepository {
        var started = false
        var stopped = false
        var onLocation: ((SosLocation) -> Unit)? = null

        override fun startAcquisition(onLocation: (SosLocation) -> Unit) {
            started = true
            this.onLocation = onLocation
        }

        override fun stopAcquisition() {
            stopped = true
            onLocation = null
        }
    }

    private fun fix(id: Int) = SosLocation(
        latitude = 52.5 + id,
        longitude = 13.4 + id,
        accuracyMeters = 10f * id,
        timestampMs = id.toLong(),
        provider = "gps",
    )

    private fun activeViewModel(): Pair<SosViewModel, Pair<FakeSosRepository, FakeLocationRepository>> {
        val repository = FakeSosRepository()
        val locationRepository = FakeLocationRepository()
        repository.startEmergency("alert-1", startedAtMs = 1_000L)
        val viewModel = SosViewModel(
            startSos = StartSosUseCase(repository),
            cancelSos = CancelSosUseCase(repository),
            stopSos = StopSosUseCase(repository),
            sosRepository = repository,
            updateSosLocation = UpdateSosLocationUseCase(repository),
            locationRepository = locationRepository,
            initialState = SosState.ACTIVE,
            mainDispatcher = Dispatchers.Unconfined,
        )
        return viewModel to (repository to locationRepository)
    }

    @Test
    fun `acquisition starts and the first fix persists immediately`() {
        val (viewModel, repositories) = activeViewModel()
        val (repository, locationRepository) = repositories

        viewModel.beginLocationAcquisition()

        assertTrue(locationRepository.started)
        locationRepository.onLocation!!.invoke(fix(1))

        val active = repository.getActiveEmergency()!!
        assertEquals(fix(1), active.location)
        assertEquals(SosLocationStatus.ACQUIRED, active.locationStatus)
        assertEquals(fix(1), viewModel.uiState.value.emergency?.location)
    }

    @Test
    fun `later fixes are throttled to one per minute`() {
        val (viewModel, repositories) = activeViewModel()
        val (repository, locationRepository) = repositories
        viewModel.beginLocationAcquisition()
        locationRepository.onLocation!!.invoke(fix(1))

        locationRepository.onLocation!!.invoke(fix(2))

        // Still the first fix: the second one arrived inside the throttle window.
        assertEquals(1, repository.locationUpdateCount)
        assertEquals(fix(1), repository.getActiveEmergency()!!.location)
    }

    @Test
    fun `permission missing marks the status without a location`() {
        val (viewModel, repositories) = activeViewModel()
        val repository = repositories.first

        viewModel.markPermissionMissing()

        val active = repository.getActiveEmergency()!!
        assertNull(active.location)
        assertEquals(SosLocationStatus.PERMISSION_MISSING, active.locationStatus)
        assertTrue(active.isActive) // the SOS is NOT stopped by a missing permission
    }

    @Test
    fun `timeout marks unavailable without stopping the emergency`() {
        val (viewModel, repositories) = activeViewModel()
        val repository = repositories.first

        viewModel.onLocationTimeout()

        val active = repository.getActiveEmergency()!!
        assertNull(active.location)
        assertEquals(SosLocationStatus.UNAVAILABLE, active.locationStatus)
        assertTrue(active.isActive)
    }

    @Test
    fun `a later successful fix updates an emergency marked unavailable`() {
        val (viewModel, repositories) = activeViewModel()
        val (repository, locationRepository) = repositories
        viewModel.onLocationTimeout()

        viewModel.beginLocationAcquisition()
        locationRepository.onLocation!!.invoke(fix(1))

        val active = repository.getActiveEmergency()!!
        assertEquals(fix(1), active.location)
        assertEquals(SosLocationStatus.ACQUIRED, active.locationStatus)
        assertTrue(active.isActive)
    }

    @Test
    fun `stopping the emergency stops location acquisition`() {
        val (viewModel, repositories) = activeViewModel()
        val (repository, locationRepository) = repositories
        viewModel.beginLocationAcquisition()

        viewModel.stopEmergency()

        assertTrue(locationRepository.stopped)
        assertEquals(1, repository.historyList.size)
        assertNull(repository.getActiveEmergency())
    }

    @Test
    fun `acquisition does not start twice`() {
        val (viewModel, repositories) = activeViewModel()
        viewModel.beginLocationAcquisition()
        viewModel.beginLocationAcquisition()

        // One callback registration: a second one would duplicate fixes.
        assertEquals(1, repositories.second.onLocation?.let { 1 } ?: 0)
    }

    @Test
    fun `permission missing after a fix does not remove the location`() {
        val (viewModel, repositories) = activeViewModel()
        val repository = repositories.first
        viewModel.beginLocationAcquisition()
        repositories.second.onLocation!!.invoke(fix(1))

        viewModel.markPermissionMissing()

        // A missing permission must never erase an already acquired fix.
        val active = repository.getActiveEmergency()!!
        assertEquals(fix(1), active.location)
        assertEquals(SosLocationStatus.ACQUIRED, active.locationStatus)
        assertTrue(active.isActive)
    }
}
