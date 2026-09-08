package com.example.georescux.domain.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the centralized BLE permission logic (pure, no Android). */
class GeoRescueBlePermissionsTest {

    private fun grantedSet(vararg granted: String): (String) -> Boolean = { it in granted.toSet() }

    @Test
    fun `sdk 31+ requires the three Bluetooth runtime permissions`() {
        val required = GeoRescueBlePermissions.requiredRuntimePermissions(33)
        assertTrue(GeoRescueBlePermissions.BLUETOOTH_SCAN in required)
        assertTrue(GeoRescueBlePermissions.BLUETOOTH_ADVERTISE in required)
        assertTrue(GeoRescueBlePermissions.BLUETOOTH_CONNECT in required)
    }

    @Test
    fun `sdk below 31 requires location instead of Bluetooth runtime permissions`() {
        val required = GeoRescueBlePermissions.requiredRuntimePermissions(29)
        assertTrue(GeoRescueBlePermissions.ACCESS_FINE_LOCATION in required)
        assertFalse(required.contains(GeoRescueBlePermissions.BLUETOOTH_SCAN))
        val critical = GeoRescueBlePermissions.criticalPermissions(29)
        assertEquals(listOf(GeoRescueBlePermissions.ACCESS_FINE_LOCATION), critical)
    }

    @Test
    fun `report shows DENIED for each missing S+ permission`() {
        val report = GeoRescueBlePermissions.report(33) { false }
        assertEquals(GeoRescueBlePermissions.BlePermissionState.DENIED, report.scan)
        assertEquals(GeoRescueBlePermissions.BlePermissionState.DENIED, report.connect)
        assertEquals(GeoRescueBlePermissions.BlePermissionState.DENIED, report.advertise)
        assertFalse(report.allRequiredGranted)
        assertEquals(3, report.missingRuntimePermissions.size)
    }

    @Test
    fun `report shows GRANTED when everything is granted on S+`() {
        val report = GeoRescueBlePermissions.report(33) {
            it == GeoRescueBlePermissions.BLUETOOTH_SCAN ||
                it == GeoRescueBlePermissions.BLUETOOTH_ADVERTISE ||
                it == GeoRescueBlePermissions.BLUETOOTH_CONNECT
        }
        assertTrue(report.allRequiredGranted)
        assertTrue(report.missingRuntimePermissions.isEmpty())
    }

    @Test
    fun `coarse location alone does not satisfy the pre-S critical requirement`() {
        val report = GeoRescueBlePermissions.report(29, grantedSet(GeoRescueBlePermissions.ACCESS_COARSE_LOCATION))
        assertEquals(GeoRescueBlePermissions.BlePermissionState.DENIED, report.location)
        assertFalse(report.allRequiredGranted)
    }

    @Test
    fun `fine location satisfies the pre-S requirement`() {
        val report = GeoRescueBlePermissions.report(29, grantedSet(GeoRescueBlePermissions.ACCESS_FINE_LOCATION))
        assertEquals(GeoRescueBlePermissions.BlePermissionState.GRANTED, report.location)
        assertTrue(report.allRequiredGranted)
    }
}

/** Unit tests for the explicit BLE connection-flow state machine. */
class GeoRescueBleConnectionStateMachineTest {

    @Test
    fun `documented happy path transitions are accepted`() {
        val machine = GeoRescueBleConnectionStateMachine()
        assertTrue(machine.transitionTo(GeoRescueBleState.CONNECTING))
        assertTrue(machine.transitionTo(GeoRescueBleState.CONNECTED))
        assertTrue(machine.transitionTo(GeoRescueBleState.DISCOVERING_SERVICES))
        assertTrue(machine.transitionTo(GeoRescueBleState.READY))
        assertTrue(machine.transitionTo(GeoRescueBleState.TRANSFERRING))
        assertTrue(machine.transitionTo(GeoRescueBleState.READY))
        assertTrue(machine.transitionTo(GeoRescueBleState.DISCONNECTING))
        assertTrue(machine.transitionTo(GeoRescueBleState.DISCONNECTED))
        assertTrue(machine.transitionTo(GeoRescueBleState.IDLE))
        assertEquals(GeoRescueBleState.IDLE, machine.state)
    }

    @Test
    fun `impossible jumps are rejected and leave the state unchanged`() {
        val machine = GeoRescueBleConnectionStateMachine()
        assertFalse(machine.transitionTo(GeoRescueBleState.READY))
        assertEquals(GeoRescueBleState.IDLE, machine.state)

        machine.transitionTo(GeoRescueBleState.CONNECTING)
        assertFalse(machine.transitionTo(GeoRescueBleState.READY)) // must discover services first
        assertEquals(GeoRescueBleState.CONNECTING, machine.state)
    }

    @Test
    fun `error is reachable from connection phases and recovers via CONNECTING`() {
        val machine = GeoRescueBleConnectionStateMachine()
        machine.transitionTo(GeoRescueBleState.CONNECTING)
        assertTrue(machine.transitionTo(GeoRescueBleState.ERROR))
        assertTrue(machine.transitionTo(GeoRescueBleState.CONNECTING))
        assertTrue(machine.transitionTo(GeoRescueBleState.CONNECTED))
        assertTrue(machine.transitionTo(GeoRescueBleState.DISCOVERING_SERVICES))
        assertTrue(machine.transitionTo(GeoRescueBleState.READY))
        // From READY, an unexpected drop is an ERROR.
        assertTrue(machine.transitionTo(GeoRescueBleState.ERROR))
    }

    @Test
    fun `same-state transition is a no-op that returns true`() {
        val machine = GeoRescueBleConnectionStateMachine()
        machine.transitionTo(GeoRescueBleState.CONNECTING)
        assertTrue(machine.transitionTo(GeoRescueBleState.CONNECTING))
        assertEquals(GeoRescueBleState.CONNECTING, machine.state)
    }

    @Test
    fun `illegal transition records the reason for diagnostics`() {
        val machine = GeoRescueBleConnectionStateMachine()
        machine.transitionTo(GeoRescueBleState.CONNECTING)
        machine.transitionTo(GeoRescueBleState.DISCONNECTING)
        assertTrue(machine.lastError?.contains("Illegal") == true)
    }
}
