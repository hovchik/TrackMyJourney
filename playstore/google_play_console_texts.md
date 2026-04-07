# Google Play Console — Full Listing & Compliance Guide

> Prepared for **Pathwise Tracker** (`com.trackjourney`) — version 1.3.2

---

## 1. Store Listing

### App Name (30 chars max)
```
Pathwise — Journey Tracker
```
(26 characters)

### Short Description (80 chars max)
```
Track walks, runs, drives & flights with AI insights — all offline & private.
```
(78 characters)

### Full Description (4000 chars max)
```
Pathwise is a GPS journey tracker that automatically detects your activity — walking, running, cycling, driving, hiking, or flying — and gives you AI-powered insights, all without ever sending your data off your phone.

KEY FEATURES

● Automatic Activity Detection
Pathwise uses speed, altitude, and motion sensors to recognize what you're doing. No manual tagging required — just start tracking and go.

● AI-Powered Insights
Get trip summaries, performance analysis, and smart suggestions generated entirely on-device. See activity breakdowns, elevation gain, and trends across all your journeys.

● Real-Time GPS Tracking
Watch your route unfold on a live map with speed, distance, elevation, and satellite accuracy displayed in real time.

● Smartwatch & Wearable Integration
Connect Bluetooth LE smartwatches and wearables (Garmin, Samsung, Polar, Wahoo, Suunto, Fitbit, Xiaomi, Huawei, COROS, WHOOP, and more) to capture cadence and additional sensor data alongside your GPS track.

● Detailed Trip History
Browse every trip with full route playback, speed graphs, and per-track AI analysis including detected activity segments and confidence scores.

● Car Profiles & Ride Cost
Add your vehicles with fuel type, consumption rate, and fuel price to automatically calculate the cost of each drive.

● Import & Export
Export tracks in JSON, GPX, or CSV formats. Full database backup and restore is supported for easy device migration.

● Complete Privacy
All AI analysis and activity detection runs locally on your device. No accounts, no cloud, no data collection. Your journeys stay yours.

● Optional Cloud AI Analysis
For deeper insights, optionally connect your own API key for OpenAI, Anthropic Claude, Google Gemini, or DeepSeek. Summarized trip statistics (no raw GPS coordinates) are sent only when you request an analysis.

● Live Tracking via Webhook
Share your real-time location with friends or a personal server through an optional webhook. You control the endpoint and can disable it at any time.

● Premium Features
Free users get unlimited tracking and AI insights. Subscribe to unlock track playback, advanced analytics, and more.

Download Pathwise and start discovering the patterns in your everyday journeys.
```
(1,568 characters)

---

## 2. App Category & Tags

| Field | Value |
|---|---|
| **Application type** | App |
| **Category** | Travel & Local |
| **Tags (up to 5)** | GPS tracker, Journey tracker, Trip logger, Route tracker, Travel log |

> **Important:** Do NOT select "Health & Fitness" as the category. The app is a journey/travel tracker, not a health app.

---

## 3. Content Rating Questionnaire (IARC)

Answer the following in the Google Play Console content rating questionnaire:

| Question | Answer |
|---|---|
| Does the app share the user's location with other users? | **No** (webhook is user-to-own-server, not social sharing) |
| Does the app allow users to interact or exchange information? | **No** |
| Does the app contain user-generated content? | **No** |
| Does the app contain violence? | **No** |
| Does the app contain sexual content? | **No** |
| Does the app facilitate gambling? | **No** |
| Does the app allow purchases of digital goods? | **Yes** (subscriptions) |
| Does the app contain ads? | **No** |
| Does the app collect precise location data? | **Yes** |

Expected rating: **Rated for Everyone (PEGI 3 / ESRB Everyone)**

---

## 4. Data Safety Form

### 4.1 Data Collection Overview

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all collected data encrypted in transit? | **Yes** (HTTPS for all network calls) |
| Do you provide a way for users to request data deletion? | **Yes** (in-app delete + clear data + uninstall) |

### 4.2 Data Types Collected

#### Location — Approximate location
| Field | Value |
|---|---|
| Collected | **Yes** |
| Shared | **No** |
| Ephemeral | **No** |
| Required | **Yes** (core functionality) |
| Purpose | App functionality |

#### Location — Precise location
| Field | Value |
|---|---|
| Collected | **Yes** |
| Shared | **No** |
| Ephemeral | **No** |
| Required | **Yes** (core functionality) |
| Purpose | App functionality |

#### App activity — In-app search history
| Field | Value |
|---|---|
| Collected | **No** |

#### Personal info
| Field | Value |
|---|---|
| Collected | **No** |

#### Financial info — Purchase history
| Field | Value |
|---|---|
| Collected | **Yes** (managed by Google Play Billing) |
| Shared | **No** |
| Ephemeral | **No** |
| Required | **No** (optional subscriptions) |
| Purpose | App functionality |

#### Device or other IDs
| Field | Value |
|---|---|
| Collected | **No** |

#### Health and fitness — Health info (heart rate, SpO2)
| Field | Value |
|---|---|
| Collected | **No** |

> **Critical — Health App Avoidance:** Do NOT declare heart rate or SpO2 under "Health info". These metrics come from third-party Bluetooth wearables and are stored only locally. The app reads raw BLE GATT characteristics (Running Speed & Cadence, Cycling Speed & Cadence services) — these are **sensor data from external hardware**, not health data collected by the app. If Google asks during review, clarify that the app reads standard BLE service data from paired peripherals for display purposes only, similar to a generic BLE scanner.

#### Health and fitness — Fitness info
| Field | Value |
|---|---|
| Collected | **No** |

> **Note:** Activity detection (walking, cycling, etc.) is derived from GPS speed and device motion sensors for route visualization purposes, not fitness tracking. Do not declare this as fitness info. Calorie estimates are derived calculations displayed alongside trip statistics, not tracked fitness metrics.

### 4.3 Data Sharing

| Question | Answer |
|---|---|
| Does the app share data with third parties? | **No** by default |

> **Webhook note:** The webhook feature transmits location to a user-specified server. This is user-initiated data transfer to the user's own infrastructure, not third-party sharing. Google's data safety guidelines define "sharing" as transfer to a third party — the webhook endpoint is controlled by the user.

> **Cloud AI note:** When users optionally configure Cloud AI with their own API key, summarized trip statistics (no raw GPS) are sent to the selected provider. This is user-initiated and requires explicit configuration. Declare this under the "Optional" toggle if Google requires it.

---

## 5. Target Audience & Content

| Field | Value |
|---|---|
| Target age group | 13+ |
| Does the app appeal to children? | **No** |
| Does the app contain ads? | **No** |
| Is this a news app? | **No** |

---

## 6. App Access (for Review)

| Field | Value |
|---|---|
| Restricted access? | **No** — all core features work without login |
| Special instructions for reviewers | "The app requires location permission to function. Grant all requested permissions. Start a tracking session by tapping the record button on the map screen. Premium features require a subscription but free features are fully functional. Cloud AI features require an external API key — skip these during review. Bluetooth wearable features require a physical BLE device." |

---

## 7. Privacy Policy URL

```
https://pathwisetracker.com/privacy-policy.html
```

This URL must be publicly accessible and match the privacy policy bundled with the app.

---

## 8. Subscription Details (for In-App Purchases)

### Monthly Plan
- **Product ID:** (as configured in Play Console)
- **Price:** $2.99/month
- **Free trial:** 7 days
- **Benefits:** Track playback, advanced analytics

### Semi-Annual Plan
- **Product ID:** (as configured in Play Console)
- **Price:** $14.99/6 months (save 16%)
- **Free trial:** 7 days
- **Benefits:** Track playback, advanced analytics

### Annual Plan
- **Product ID:** (as configured in Play Console)
- **Price:** $30.00/year (save 16%)
- **Free trial:** 7 days
- **Benefits:** Track playback, advanced analytics

---

## 9. Health App Declaration

When Google Play asks "Is this a health app?", answer **No**.

**Rationale:**
- The app's primary purpose is GPS journey tracking and route logging (Travel & Local category)
- Activity detection is used to color-code route segments on a map, not for fitness tracking
- Calorie display is a derived statistic shown alongside trip distance/speed, not a fitness goal
- BLE wearable integration reads standard GATT services (cadence, battery) for display alongside route data
- The app does not set fitness goals, track workouts, monitor health conditions, or provide health recommendations
- The app includes a clear medical disclaimer

---

## 10. Permissions Declaration

Google Play requires justification for sensitive permissions:

| Permission | Justification |
|---|---|
| `ACCESS_FINE_LOCATION` | Core functionality: records GPS routes on a map during active tracking sessions |
| `ACCESS_BACKGROUND_LOCATION` | Continues route recording when the app is minimized or screen is off during an active tracking session started by the user |
| `ACTIVITY_RECOGNITION` | Detects movement type to color-code route segments on the map (walking, driving, cycling, etc.) |
| `BLUETOOTH_SCAN` | Discovers nearby Bluetooth LE wearables for optional pairing |
| `BLUETOOTH_CONNECT` | Connects to paired BLE wearables to read sensor data (cadence, battery level) |

### Background Location Justification (required by Google)

**Core feature requiring background location:**
Route tracking — the app records the user's journey on a map. Users start a tracking session, then put the phone in their pocket. The app must continue recording GPS points in the background to produce a complete route. Without background location, routes would have gaps whenever the screen turns off.

**User-visible benefit:**
A persistent notification shows the active tracking session with live distance, duration, and speed. The user explicitly starts and stops each session.

---

## 11. Changelogs (What's New)

### Version 1.3.2
```
• Improved GPS accuracy with adaptive sampling
• Added support for more Bluetooth LE wearables
• Enhanced AI activity detection with confidence scores
• Bug fixes and performance improvements
```
