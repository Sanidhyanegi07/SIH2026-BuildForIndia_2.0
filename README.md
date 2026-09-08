# GeoRescuX

### Offline-First Emergency Response, Safe Routing & Disaster-Resilience Platform

> **“When connectivity fails, the rescue system shouldn't.”**

GeoRescuX is an **offline-first emergency response and rescue platform** designed to provide location-aware SOS services, safety-aware routing, hazard intelligence, and emergency coordination even when conventional internet connectivity is unavailable.

The core principle behind GeoRescuX is simple:

> **Emergency functionality should not stop just because the internet does.**

---

## Overview

During disasters such as floods, earthquakes, landslides and other emergencies, conventional navigation and cloud-dependent emergency systems may become unreliable due to:

* Internet or mobile network failure
* Damaged or blocked roads
* Rapidly changing hazard conditions
* Limited access to updated information
* Difficulty identifying feasible safe locations
* Limited smartphone availability in remote communities

GeoRescuX addresses this problem using an **offline-first architecture** where essential emergency and routing capabilities remain available locally, while Firebase is used for synchronization when connectivity is restored.

### Core Workflow

```text
SOS
 ↓
GPS Location
 ↓
Connectivity Check
 ↓
Local / Updated Data
 ↓
Hazard & Road Analysis
 ↓
Local Routing Engine
 ↓
Safe Route
 ↓
Safe Haven / Emergency Destination
 ↓
Synchronization when connectivity returns
```

---

# Key Features

## 1. Emergency SOS

GeoRescuX provides an emergency SOS workflow designed to remain functional under poor or unavailable network conditions.

The SOS architecture supports:

* SOS activation without requiring successful Firebase communication
* Local persistence of emergency information
* GPS-based location association
* Pending synchronization
* Synchronization when connectivity becomes available

### Emergency Flow

```text
User
 ↓
Activate SOS
 ↓
Emergency Created
 ↓
Local Persistence
 ↓
Location Acquisition
 ↓
Pending Synchronization
 ↓
Firebase when available
```

---

## 2. GPS & Location Intelligence

GeoRescuX integrates device location services into the emergency workflow.

Location information can be used for:

* Current user position
* SOS location
* Safe Route origin
* Emergency map visualization
* Geographic region identification

### Important Principle

```text
GPS ≠ Internet
```

GPS/location services can provide positioning even when internet connectivity is unavailable, although accuracy can vary depending on the device and environment.

Location acquisition is designed so that it does not unnecessarily prevent emergency activation.

---

## 3. OpenStreetMap-Based Mapping

GeoRescuX uses an **OpenStreetMap-based** geographic foundation.

The map provides geographic context for:

* Roads and streets
* User location
* SOS events
* Hazards
* Blocked roads
* Emergency resources
* Safe Routes

The map architecture is maintained specifically for GeoRescuX, with its own UI, data model and emergency-response functionality.

---

## 4. Offline Mapping

Offline operation is a core requirement of GeoRescuX.

The application can retain locally available map information so that geographic visualization can continue when connectivity disappears.

### Online

```text
Internet
 ↓
Map Data
 ↓
Local Cache
 ↓
Map
```

### Offline

```text
Local Map Data
 ↓
Map
```

Offline map availability depends on the required geographic data already being available on the device.

---

# 5. Safe Route

Safe Route is one of the central features of GeoRescuX.

Instead of blindly selecting the shortest path, the routing system considers available operational safety information.

### Safe Route Pipeline

```text
Current Location
       +
Destination
       +
Regional Route Graph
       +
Hazard Information
       +
Blocked Roads
       ↓
Local Routing Engine
       ↓
Safety Evaluation
       ↓
Safe Route
       ↓
Map Visualization
```

The primary route calculation is designed to operate locally and does not require a live online routing API.

---

# 6. Hazard & Blocked-Road Intelligence

Emergency routing must consider more than distance.

GeoRescuX can represent operational conditions such as:

* Hazards
* Blocked roads
* Emergency closures
* Unsafe road segments
* Region-specific restrictions

These conditions can influence route calculation.

Depending on the routing policy, affected road segments may either be excluded or assigned increased routing costs.

```text
Road Network
     ↓
Safety Evaluation
     ↓
Hazard / Blocked Road Analysis
     ↓
Routing Engine
     ↓
Safe Route
```

The system provides a **feasible route based on the hazard information available to it**, rather than claiming guaranteed real-time safety.

---

# 7. Region-Scoped Routing

GeoRescuX uses region-specific routing graphs instead of relying on one global routing graph.

The routing persistence architecture uses:

```text
route_graph_{regionId}
```

Example:

```text
route_graph_region_01
route_graph_region_02
route_graph_region_03
```

This provides:

* Geographic separation
* Better data organization
* Regional scalability
* Controlled storage
* Region-aware routing

This architecture also supports future expansion from a prototype region to multiple disaster-prone regions.

---

# 8. Offline-First Synchronization

Firebase acts as the **cloud synchronization layer**, rather than being the sole source of truth for immediate emergency operation.

### Online

```text
Local Data
    ↓
Synchronization Layer
    ↓
Firebase
```

### Offline

```text
User Action
    ↓
Local Persistence
    ↓
Pending Sync
    ↓
WorkManager / Retry
    ↓
Firebase when available
```

This architecture is especially important for:

* SOS events
* Emergency information
* Hazard updates
* Operational changes

The network enhances the system, but the network should not determine whether core emergency functionality can operate.

---

# 9. WorkManager

Android **WorkManager** supports background synchronization and retry operations.

```text
Data Created
     ↓
Stored Locally
     ↓
Sync Pending
     ↓
WorkManager
     ↓
Network Available?
    / \
   NO  YES
   ↓    ↓
 Retry Upload
        ↓
   Sync Complete
```

Temporary network failures should not permanently lose pending synchronization work.

---

# 10. Firebase Authentication

GeoRescuX uses **Firebase Authentication** for user identity.

Authentication provides the foundation for:

* User accounts
* Secure access
* User-specific data
* Administrative authorization

Firebase Authentication is part of the connected security and synchronization layer and is **not required for the core offline routing process**.

Production credentials must never be hardcoded into the Android application.

---

# 11. Admin Authorization

Administrative access uses **Firebase Custom Claims** rather than client-controlled database fields.

### Authorization Model

```text
Firebase Authentication
          ↓
Authenticated User
          ↓
Custom Claims
          ↓
admin == true?
       /       \
     YES        NO
      ↓          ↓
Admin Portal   User Portal
```

A normal user must not be able to become an administrator by changing client-side values such as:

```text
isAdmin = true
```

or:

```text
admin = true
```

Administrative authorization must be enforced through trusted backend infrastructure and Firebase security rules.

---

# 12. Security Principles

GeoRescuX follows the following security principles:

### No Hardcoded Admin Credentials

Administrative credentials must never be embedded in the Android application.

### No Service-Account Credentials in the APK

Firebase Admin SDK credentials belong only in trusted server-side infrastructure.

### Server-Enforced Authorization

Hiding an Admin button is not security. Database and backend authorization must independently enforce permissions.

### User Data Isolation

Authenticated users should only access information they are authorized to access.

---

# 13. Admin Portal

The Admin Portal provides authorized administrative users with operational management capabilities.

Depending on the enabled modules, administrators can manage:

* Active SOS events
* Emergency locations
* Hazards
* Blocked roads
* Incident information
* Emergency resources
* Synchronization state
* Regional operational data
* System/network status

Administrative capabilities are protected using Firebase Custom Claims and backend security rules.

---

# 14. System Architecture

GeoRescuX follows a layered architecture separating presentation, domain and data responsibilities.

```text
                         GEORESCUX
                            │
           ┌────────────────┼────────────────┐
           │                │                │
           ▼                ▼                ▼
     Presentation         Domain           Data
           │                │                │
           ▼                ▼         ┌──────┴──────┐
        Map UI        Routing Engine   │             │
           │                │          ▼             ▼
           │                ▼     Local Storage   Firebase
           │          Route Graph       │             │
           │                │            │             │
           └────────────────┼────────────┴─────────────┘
                            │
                            ▼
                     GeoRescuX Core
```

---

# 15. Major Components

| Component                  | Responsibility                       |
| -------------------------- | ------------------------------------ |
| Firebase Authentication    | User identity                        |
| Firebase Custom Claims     | Admin authorization                  |
| Firebase Realtime Database | Cloud data synchronization           |
| OpenStreetMap              | Geographic map foundation            |
| Map Engine                 | Map rendering and interaction        |
| GPS / Location Services    | Device positioning                   |
| Local Storage              | Offline application data             |
| Regional Route Graph       | Road-network persistence             |
| Routing Engine             | Local Safe Route calculation         |
| SOS System                 | Emergency activation                 |
| Hazard System              | Safety intelligence                  |
| WorkManager                | Background retry and synchronization |
| Admin Portal               | Authorized operational management    |

---

# 16. Online vs Offline

| Capability               | Online | Offline |
| ------------------------ | -----: | ------: |
| Cached map display       |    Yes |     Yes |
| GPS / Location           |    Yes |     Yes |
| Local routing            |    Yes |     Yes |
| Safe Route               |    Yes |     Yes |
| Local emergency data     |    Yes |     Yes |
| SOS creation             |    Yes |     Yes |
| Firebase synchronization |    Yes | Pending |
| Background retry         |    Yes |   Retry |
| Cloud updates            |    Yes |      No |
| Cached regional graph    |    Yes |     Yes |

> Offline capabilities depend on the required map, routing and application data already being available locally.

---

# 17. Network Failure Scenario

If internet connectivity disappears during an emergency:

```text
Internet Lost
     ↓
Offline Mode
     ↓
Cached Map Available
     ↓
GPS Continues
     ↓
Local Emergency Data
     ↓
Local Route Graph
     ↓
Local Routing Engine
     ↓
Safe Route
```

The application should not become unusable simply because Firebase or the internet is unavailable.

---

# 18. Community & Responder Access

GeoRescuX does not assume that every affected person will have a smartphone.

For communities with limited individual smartphone access, the system can operate through available access points such as:

* Community workers
* Volunteers
* Police personnel
* Disaster responders
* Relief-centre operators
* Other authorized response personnel

The architecture is designed to avoid depending on a single volunteer or access point.

```text
Affected Community
        ↓
Available Access Point
        ↓
GeoRescuX
        ↓
Safe Haven / Emergency Response
```

> **Our vision is not one smartphone for every victim; it is one resilient response network for every affected community.**

GeoRescuX is not a replacement for rescue teams. It acts as a **digital support layer for disaster response**.

---

# 19. End-to-End Emergency Workflow

```text
                         USER
                           │
                           ▼
                      Activate SOS
                           │
                           ▼
                    Emergency Created
                           │
                           ▼
                    Local Persistence
                           │
                           ▼
                     GPS Acquisition
                           │
                           ▼
                    Emergency Map Layer
                           │
                           ▼
                  Regional Routing Graph
                           │
                           ▼
                    Safety Evaluation
                           │
                           ▼
                    Local Route Engine
                           │
                           ▼
                       SAFE ROUTE
                           │
                           ▼
                       MAP DISPLAY
                           │
                           ▼
                 Firebase Synchronization
                       when available
```

---

# 20. Why GeoRescuX?

The central question behind GeoRescuX is:

> **“What happens when an emergency occurs and the network infrastructure is unavailable?”**

Conventional navigation often follows:

```text
Internet → Cloud → Application
```

GeoRescuX follows:

```text
Local Core ↔ Cloud Sync

Local Core
 ├── SOS
 ├── GPS
 ├── Routing
 ├── Regional Route Graph
 ├── Emergency Data
 └── Cached Map
```

This makes GeoRescuX suitable for scenarios involving:

* Weak connectivity
* Intermittent connectivity
* Network congestion
* Temporary network failure
* Unavailable internet connectivity

---

# 21. How GeoRescuX is Different

Many existing disaster-management platforms primarily focus on:

* Disaster information
* Alerts
* Do's and Don'ts
* Emergency contacts
* Chatbots
* General awareness

GeoRescuX focuses on the transition from **information to actionable response**.

| Traditional Disaster Information | GeoRescuX                               |
| -------------------------------- | --------------------------------------- |
| Disaster information             | Action-oriented response                |
| Emergency contacts               | SOS + location                          |
| Online information               | Offline-first operation                 |
| General maps                     | Regional offline map data               |
| Normal navigation                | Safety-aware routing                    |
| Static destination information   | Safe Route                              |
| Fixed route                      | Hazard-aware route calculation          |
| Cloud-dependent operation        | Local operation + cloud synchronization |

### Core Differentiator

> **Information → Decision → Action**

---

# 22. Design Principles

### 1. Offline First

Core emergency functionality should remain available during network failure.

### 2. Local Reliability

Critical operations should have local persistence and recovery mechanisms.

### 3. Safety Over Distance

Safe Route calculation should account for available operational safety information instead of blindly selecting the shortest path.

### 4. Secure by Design

Authorization must be enforced by trusted infrastructure.

### 5. Regional Scalability

Routing information is organized by region.

### 6. Graceful Failure

Network, GPS, synchronization and routing failures should produce controlled states instead of crashes.

### 7. Separation of Concerns

Mapping, routing, authentication, persistence, synchronization and UI should remain logically separated.

---

# 23. Technology Stack

### Android

* Kotlin
* Android SDK
* Android Jetpack
* WorkManager
* Android Location Services

### Mapping

* OpenStreetMap
* Android-compatible OSM map rendering
* Offline map/tile storage

### Backend

* Firebase Authentication
* Firebase Realtime Database
* Firebase Custom Claims
* Firebase Security Rules
* Secure server-side/admin infrastructure

### Local Infrastructure

* Local persistence
* Region-scoped routing graphs
* Offline routing engine
* Synchronization queue

### Testing

* Unit tests
* Android instrumentation tests
* Offline/network-failure testing
* Firebase security testing

---

# 24. Testing Strategy

GeoRescuX is designed to be tested at multiple levels.

### Unit Testing

Testing includes:

* Routing algorithms
* Regional graph handling
* Safety evaluation
* SOS state transitions
* Synchronization state
* Authentication and authorization logic

### Integration Testing

Testing includes:

* Local storage + routing
* SOS + location
* Routing + map
* Firebase + synchronization
* WorkManager + retry

### Offline Testing

The system should be tested with:

```text
Wi-Fi OFF
Mobile Data OFF
Firebase unavailable
```

and verified to ensure that essential functionality continues to operate.

### Security Testing

Testing should verify that:

* Normal users cannot access Admin operations
* Users cannot self-promote
* Unauthorized Firebase writes are rejected
* Administrative operations require the appropriate Custom Claim

---

# 25. Reliability Model

GeoRescuX separates **core emergency functionality** from **connected cloud functionality**.

```text
                    CORE
                     │
           ┌─────────┼─────────┐
           ▼         ▼         ▼
          SOS       GPS      Routing
           │         │         │
           └─────────┼─────────┘
                     ▼
              Local Persistence
                     │
                     ▼
                 CORE SYSTEM


              CONNECTED LAYER
                     │
                     ▼
                  Firebase
                     │
                     ▼
               Synchronization
```

Cloud connectivity enhances the system without defining whether core emergency functions can operate.

---

# 26. Data & Privacy

GeoRescuX follows a data-minimization approach.

Emergency and location information should only be accessible to authorized users and systems.

Sensitive administrative credentials must never be included in the mobile application.

Firebase security rules should enforce access control at the database layer.

---

# 27. Future Scope

Potential future extensions include:

* Real-time disaster-zone mapping
* Dynamic road-closure updates
* Emergency responder tracking
* Multiple rescue teams
* Evacuation-zone visualization
* Hospital and resource availability
* Advanced hazard classification
* Predictive route-risk scoring
* Regional downloadable map packages
* Automatic geographic-data updates
* Disaster-specific routing profiles
* Advanced emergency coordination
* Mesh/local communication support

---

# 28. Current Development Status

GeoRescuX is a prototype under active development.

The following development status should be updated as features are implemented and validated:

| Module                       | Status      |
| ---------------------------- | ----------- |
| Firebase Authentication      | Implemented |
| Offline persistence          | Implemented |
| SOS foundation               | Implemented |
| GPS / SOS location           | Implemented |
| Firebase synchronization     | Implemented |
| Regional route graphs        | Implemented |
| Local routing                | Implemented |
| OSM production map           | In Progress |
| Offline map caching          | In Progress |
| Safe Route map visualization | In Progress |
| Admin Custom Claims          | In Progress |
| Admin Portal                 | In Progress |
| Full end-to-end validation   | In Progress |

> **A module should only be marked complete after it has been implemented and validated.**

---

# 29. Production Acceptance Criteria

### Online Workflow

```text
Login
 ↓
Open GeoRescuX
 ↓
Open Map
 ↓
Real OSM Map
 ↓
Current Location
 ↓
Select Destination
 ↓
Local Routing Engine
 ↓
Safe Route
 ↓
Route Displayed on Map
 ↓
SOS / Hazard Information
 ↓
Firebase Synchronization
```

### Offline Workflow

```text
Internet OFF
 ↓
Map available from local/cached data
 ↓
GPS continues
 ↓
Local emergency data available
 ↓
Local route graph available
 ↓
Safe Route calculated locally
 ↓
Route displayed on map
 ↓
SOS remains operational
 ↓
Changes stored locally
 ↓
Synchronization becomes pending
```

### Connectivity Restored

```text
Internet ON
 ↓
Pending Local Changes
 ↓
Synchronization
 ↓
Firebase
 ↓
Synchronized State
```

### Admin Workflow

```text
Admin Login
 ↓
Firebase Authentication
 ↓
Custom Claim Verification
 ↓
admin == true
 ↓
Admin Portal
```

---

# 30. Important Limitations

Offline functionality is not the same as having the entire world stored on the device.

For complete offline operation in a geographic area, the required:

* Map data
* Route graph
* Emergency data
* Hazard information

must already be available locally.

Additional limitations include:

1. Completely disconnected devices cannot receive newly generated cloud information until some communication path becomes available.
2. Offline routes depend on the freshness of locally stored data.
3. Smartphone availability may be limited in some remote communities.
4. GPS accuracy can vary depending on device and environmental conditions.
5. Physical rescue still requires human responders and disaster-management infrastructure.

> **Offline capability provides continuity of operation, not unlimited real-time information.**

---

# 31. Development Guidelines

When extending GeoRescuX:

1. Preserve the offline-first architecture.
2. Do not introduce unnecessary cloud dependencies.
3. Do not duplicate existing repositories or services.
4. Preserve `route_graph_{regionId}`.
5. Keep Firebase synchronization separate from local emergency operation.
6. Never place administrative secrets in the Android application.
7. Use server-enforced authorization.
8. Do not delete passing tests to hide regressions.
9. Add tests for new core functionality.
10. Prefer incremental architectural changes over full rewrites.

---

# 32. Project Vision

GeoRescuX aims to provide a resilient digital infrastructure for emergency navigation and response.

> **“A rescue platform that can continue helping people even when the infrastructure around them cannot.”**

Our long-term vision is:

```text
Emergency
    ↓
Evacuation
    ↓
Relief Coordination
    ↓
Recovery Support
```

The goal is to build a connectivity-resilient disaster-response ecosystem connecting affected communities, safe locations and responders.

---

# 33. Team

## Team GeoRescuX

Developed for **Smart India Hackathon (SIH) 2026**

---

# 34. Disclaimer

GeoRescuX is a software project intended to assist emergency-response workflows.

It is **not a replacement** for:

* Official emergency services
* Trained rescue personnel
* Government emergency infrastructure
* Professional navigation systems

Real-world deployment requires appropriate geographic-data validation, security auditing, operational testing, reliability testing, regulatory review and emergency-response validation.

---

# GeoRescuX

### Offline-first. Safety-aware. Rescue-focused.

> **“When connectivity fails, the rescue system shouldn't.”**
