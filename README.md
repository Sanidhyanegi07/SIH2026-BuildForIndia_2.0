# GeoRescueX

**Disaster Resilience. Offline-First.**

GeoRescueX is an offline-first emergency response and disaster-resilience Android application, built for Smart India Hackathon 2026 (Problem Statement: **SIH26206** — Student Innovation, Disaster Management).

> The system that works even when everything else stops working.

The application is built around a three-phase lifecycle:

**PREVENT** · **RESPOND** · **RECOVER**

---

## App Preview

| Login | Register | Dashboard |
|---|---|---|
| ![Login](images/login.jpg) | ![Register](images/register.jpg) | ![Dashboard](images/dashboard.jpg) |

| Safe Route (Uttarakhand) | Admin Panel |
|---|---|
| ![Safe Route](images/safe-route.jpg) | ![Admin](images/admin.jpg) |

---

## Core Features

### 🔐 Authentication
- Firebase Authentication-based Sign In / Register flow.
- User data and emergency records are scoped to the authenticated account, so one user's
  data never mixes with another's.
- **Emergency features remain active offline** even after login — the app does not gate
  core SOS/routing functionality behind a live connection.

### 🆘 SOS / Emergency Mode
- Dashboard → **Hold SOS (2 seconds)** → Countdown → Emergency ACTIVE.
- A deliberate hold-to-activate pattern prevents accidental triggers.
- A unique emergency ID is generated and stored **locally first** — activation does not
  wait on or depend on internet connectivity.
- GPS location is attached in the background once available; failure to acquire location
  does not cancel or delay the SOS flow.
- Quick Actions from the dashboard: **Emergency Contacts**, **Alerts**, **Profile**, and
  **Safe Route**.

### 🗺️ Safe Route — Offline Evacuation Routing (Uttarakhand)
- Offline-first navigation and evacuation routing, currently scoped to the **Uttarakhand**
  region as the first real regional dataset.
- Uses a cached OSM Mapnik map layer, so the map itself renders without a live connection.
- Tap-to-select **Start Location / Node** and **Destination (Safe Haven)** directly on the map.
- **Find Route** calculates a path using the local A* routing engine over the regional graph.
- **Reroute (Avoid Hazards)** recalculates around known blocked/hazardous road segments.
- Displays live sync status (e.g. "ONLINE — syncing via Firebase") while making clear that
  map rendering and routing themselves stay offline-first regardless of connection state.

### 🛠️ Admin Panel
- Custom-claim-gated administrator view (`CUSTOM CLAIM: ADMIN`) — administrative access is
  enforced via Firebase Custom Claims, not client-side flags.
- Live dashboard showing:
  - Active SOS count
  - Hazards / Road-block incident count
  - Total records synced
  - Realtime sync status
- Tabs for **SOS Events**, **Hazards & Roads**, and **Incidents**.
- Per-alert detail: user UID, activation timestamp, resolution status, and GPS coordinates
  with reported accuracy (e.g. `±23m, fused`).

---

## Architecture

GeoRescueX follows an **offline-first** design principle throughout:

```
Application → Local storage → Feature works
                    |
        (when network available)
                    v
        Synchronize local data → Firebase
```

Rather than:

```
Internet → Application → Feature
```

Key architectural components:

- **UI → ViewModel → Repository → Data/Domain** (MVVM, repository pattern)
- **Room / SQLite** for local persistence (SOS records, contacts, routing graph, hazard data)
- **Region-scoped routing graphs** (`route_graph_{regionId}`) — routing data is organized per
  region (e.g. Uttarakhand) rather than one monolithic nationwide graph, so new regions can be
  added independently
- **A\*** pathfinding engine operating on locally stored road-network graphs, with support for
  blocked-edge and hazard-cost penalties
- **Firebase** (Auth + Firestore) used strictly for authentication and cloud synchronization —
  never for map rendering or route calculation
- **WorkManager** for durable, retry-safe background synchronization when connectivity returns

---

## Tech Stack

- **Client:** Kotlin, Android SDK
- **Local storage:** Room (SQLite)
- **Maps:** OpenStreetMap (OSM Mapnik tiles, cached for offline use)
- **Routing:** Custom A* implementation over region-scoped graphs
- **Cloud:** Firebase Authentication, Cloud Firestore, Firebase Custom Claims (admin access)
- **Background sync:** WorkManager

---

## Project Status

This is an active hackathon prototype. Honest current status:

**Working:**
- Authentication and user-scoped data
- SOS activation with local-first persistence and background GPS
- Offline Safe Route calculation and rerouting for the Uttarakhand region
- Admin dashboard with real-time SOS/hazard monitoring
- Firebase sync with durable retry via WorkManager

**Not yet implemented:**
- Phone-to-phone peer relay (BLE / Nearby Connections) for zero-network SOS forwarding
- India-wide (multi-region) routing coverage
- Verified shelter / hospital / police / fire-station datasets
- India 112 emergency service integration

---

## Team

Built for Smart India Hackathon 2026 — Problem Statement **SIH26206** (Disaster Management,
Software Track) by **Sanidhya, Karan, Rudraksh, and Shobhit**.

---

## Getting Started

> Update this section to match your actual project structure/build steps.

1. Clone the repository.
2. Open in Android Studio.
3. Add your own `google-services.json` (Firebase project config) under `app/`.
4. Build and run on a physical Android device (recommended over emulator for GPS/offline
   map testing).

---

## License

Add a license of your choice (e.g. MIT) here.
