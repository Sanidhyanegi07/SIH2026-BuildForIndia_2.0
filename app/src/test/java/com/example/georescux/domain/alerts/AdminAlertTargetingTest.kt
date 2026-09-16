package com.example.georescux.domain.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Admin alert delivery scoping (spec §11.5/§63): an alert reaches a device
 * only when its declared scope covers that device's region — and, for a
 * district-scoped alert, the device's own district when it is known.
 */
class AdminAlertTargetingTest {

    private fun alert(scope: String, regionId: String? = null, district: String? = null) =
        AdminAlert(id = "a1", title = "T", body = "B", timestampMs = 0L,
            scopeType = scope, regionId = regionId, district = district)

    @Test
    fun `India-wide alert targets every region`() {
        val alert = alert(AdminAlert.SCOPE_INDIA)
        assertTrue(alert.targets("uttarakhand"))
        assertTrue(alert.targets("sample-region"))
        assertTrue(alert.targets("anything"))
    }

    @Test
    fun `state alert targets only its own region`() {
        val alert = alert(AdminAlert.SCOPE_STATE, regionId = "uttarakhand")
        assertTrue(alert.targets("uttarakhand"))
        assertFalse(alert.targets("haryana"))
    }

    @Test
    fun `district alert targets the matching district when the device district is known`() {
        val alert = alert(AdminAlert.SCOPE_DISTRICT, regionId = "uttarakhand", district = "Nainital")

        assertTrue(alert.targets("uttarakhand", deviceDistrict = "Nainital"))
        assertFalse(alert.targets("uttarakhand", deviceDistrict = "Dehradun"))
    }

    @Test
    fun `district alert still reaches a device whose district is unknown`() {
        // Emergency alerts err toward over-delivery: a device with no GPS fix
        // yet should not silently miss a district alert in its own state.
        val alert = alert(AdminAlert.SCOPE_DISTRICT, regionId = "uttarakhand", district = "Nainital")
        assertTrue(alert.targets("uttarakhand", deviceDistrict = null))
    }

    @Test
    fun `district alert never crosses into another state`() {
        val alert = alert(AdminAlert.SCOPE_DISTRICT, regionId = "uttarakhand", district = "Nainital")
        assertFalse(alert.targets("haryana", deviceDistrict = "Nainital"))
    }

    @Test
    fun `district alert with a blank district falls back to state scope`() {
        val alert = alert(AdminAlert.SCOPE_DISTRICT, regionId = "uttarakhand", district = "")
        assertTrue(alert.targets("uttarakhand", deviceDistrict = "Dehradun"))
    }
}
