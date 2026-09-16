package com.example.georescux.ui.help

/**
 * The Help Center content (spec §31).
 *
 * Roughly 15–20 FAQ items across the categories the spec lists. Kept as a
 * pure, framework-free model so the wording can be reviewed in one place
 * and the answers stay consistent with what the app actually does.
 */
object HelpContent {

    enum class Category(val title: String) {
        GETTING_STARTED("Getting Started"),
        SOS("SOS"),
        BLE("BLE Emergency Network"),
        MAPS("Maps"),
        SAFE_ROUTE("Safe Route"),
        SAFE_ROUTE_PRO("Safe Route Pro"),
        ALERTS("Alerts"),
        EMERGENCY_CONTACTS("Emergency Contacts"),
        VERIFICATION("Profile Verification"),
        OFFLINE("Offline Mode"),
        BATTERY("Battery"),
        ADMIN("Administrative Alerts"),
    }

    data class Faq(val category: Category, val question: String, val answer: String)

    val items: List<Faq> = listOf(
        Faq(
            Category.GETTING_STARTED,
            "What is GeoRescuX?",
            "GeoRescuX is an offline-first emergency response, navigation and disaster monitoring platform. It combines offline maps, local GPS tracking, a safe-route engine, SOS, and a Bluetooth (BLE) emergency network that can relay an SOS phone-to-phone even when there is no internet. Firebase is used to synchronise data once connectivity returns — it is not required for emergency features to work."
        ),
        Faq(
            Category.GETTING_STARTED,
            "Why do I need to download a regional map?",
            "Offline mapping is region-based rather than whole-world. A regional package contains the road graph, boundary data, safe havens and the map rendering data for that state and its border area. Safe Route only operates where that data is installed, so the app never pretends to route you over roads it does not actually have."
        ),
        Faq(
            Category.SOS,
            "How does SOS work?",
            "Press and hold the SOS button for 2 seconds to arm it, then confirm the countdown. An emergency record is created and stored locally first — activation never waits for internet. Your GPS location is attached as soon as a fix is available, and the SOS is then published over BLE and queued for Firebase synchronisation."
        ),
        Faq(
            Category.SOS,
            "What happens after the 2-second SOS timer?",
            "Once active, the SOS stays active until you press “Stop Emergency”. It survives leaving the screen and even an app restart, because the emergency record is the source of truth on this device. Safe Route Pro also unlocks while the emergency is active."
        ),
        Faq(
            Category.SOS,
            "Can I add a note to my SOS?",
            "Yes — optionally, and it never delays activation. The note is capped at 120 characters, stored locally, carried in the BLE packet, shown in the receiving notification, listed in Alerts, and synchronised to Firebase when a connection is available."
        ),
        Faq(
            Category.BLE,
            "How does BLE emergency communication work?",
            "Every nearby GeoRescuX device acts as both a BLE advertiser and scanner. Your SOS is packaged into a packet with a hop count (TTL). Receiving devices validate it, check for duplicates, store it locally, show a notification, and relay it onward with a lower hop count — a store-and-forward mesh that does not need internet."
        ),
        Faq(
            Category.BLE,
            "How will another phone receive my SOS?",
            "A nearby GeoRescuX phone validates the packet, saves the emergency to its own local storage, raises a visible high-priority notification, and adds an entry to its Alerts screen. If you included a note, it is shown in that notification."
        ),
        Faq(
            Category.BLE,
            "What happens when BLE reaches a device with internet?",
            "That device keeps the emergency locally and immediately marks it pending for cloud synchronisation. When a connection is available the record — with your original timestamp, location and emergency ID — is uploaded to Firebase, where administrators can see it."
        ),
        Faq(
            Category.MAPS,
            "Can I use GeoRescuX without internet?",
            "Yes for the core features: offline maps, your GPS location, and Safe Route all work with no connection. SOS is created and stored locally, and BLE keeps working. Only cloud synchronisation waits for connectivity."
        ),
        Faq(
            Category.MAPS,
            "How does offline mapping work?",
            "The map renders from data bundled with the region package — either a vector map or a cached tile archive — with the network deliberately disabled for the tile source. If a region has a routing graph but no bundled map data, the app tells you that honestly instead of silently requiring a connection."
        ),
        Faq(
            Category.SAFE_ROUTE,
            "What is Safe Route?",
            "Safe Route is offline evacuation routing. It runs an A* pathfinding engine over the locally stored road graph and weighs hazards, blocked roads and safe havens — so the result is a feasible safe route, not simply the shortest road path."
        ),
        Faq(
            Category.SAFE_ROUTE,
            "How does rerouting work?",
            "Reroute recalculates the route from your current position over the current graph state — it is a genuine re-run of the engine, not a redraw. It happens when you press Reroute, when you drift off the route, or when a hazard appears on your path."
        ),
        Faq(
            Category.SAFE_ROUTE_PRO,
            "What is Safe Route Pro?",
            "Safe Route Pro is the emergency mode that unlocks automatically while an SOS is active. It monitors your location more frequently, keeps an eye on the route and the SOS/BLE/sync state, and offers faster recalculation. It stays within Android's legitimate background limits and degrades gracefully if the OS restricts it."
        ),
        Faq(
            Category.ALERTS,
            "What appears in Alerts?",
            "Alerts is the unified emergency information centre: SOS alerts received over BLE from nearby devices, cloud SOS alerts, official administrative alerts targeted at your region, and your own locally persisted emergency events. Everything stored locally stays readable offline."
        ),
        Faq(
            Category.EMERGENCY_CONTACTS,
            "What are Emergency Contacts for?",
            "They are the people reached in an emergency. You can add, edit and remove contacts, mark one as primary, and keep secondaries. They are stored locally first and synchronised when a connection is available."
        ),
        Faq(
            Category.VERIFICATION,
            "How does identity verification work?",
            "You can submit an official ID from your profile. The request enters a Pending state that only administrators can review. An administrator approves or rejects it, and only an approval changes your verified status. Identity documents are never displayed publicly — they are visible only to authorised administrators."
        ),
        Faq(
            Category.OFFLINE,
            "What happens to my data when I am offline?",
            "Everything emergency-critical is written to local storage first: your SOS, its location and note, received BLE alerts, contacts and the routing graph. A durable pending marker records what still needs the cloud, and a WorkManager job uploads it once connectivity returns — so nothing is lost because of a temporary outage."
        ),
        Faq(
            Category.BATTERY,
            "How does GeoRescuX handle battery?",
            "Location updates use a slower interval normally and a faster interval only while an emergency is active. BLE runs under a foreground service with a scan duty cycle, and background behaviour stays within what Android legitimately allows. If the OS restricts background execution, the app degrades gracefully rather than claiming it can bypass doze mode."
        ),
        Faq(
            Category.ADMIN,
            "How do administrative alerts work?",
            "Administrators scope an alert to India, a state, or a district and publish it through Firebase. Only devices in the targeted scope receive it, so a district-specific alert is not delivered to every user. It arrives as a notification, appears in Alerts, and is stored locally so it stays available offline."
        ),
    )
}
