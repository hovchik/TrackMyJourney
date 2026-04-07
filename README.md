# Pathwise 🗺️

**Pathwise** (formerly TrackMyJourney) — an Android application that tracks your movements on OpenStreetMap, integrates with smartwatches via Bluetooth LE for health metrics, and uses AI (on-device or cloud) to analyze your trips.

## Features

### 📍 GPS Tracking with OpenStreetMap
- Real-time location tracking rendered on OSM (osmdroid)
- Color-coded track segments by detected activity type
- Configurable recording interval with smart adaptive sampling
- Speed recording and satellite quality monitoring at every data point
- Foreground service for reliable background tracking

### ⌚ Smartwatch Integration (Bluetooth LE)
- **Wide device support** — Garmin, Samsung, Polar, Wahoo, Suunto, Fitbit, Xiaomi/Amazfit, Huawei, COROS, WHOOP, Apple Watch, Wear OS, Generic BLE
- Auto-detection of device brand from BLE advertisement name
- **Battery level** monitoring via BLE Battery Service (0x180F)
- **Running cadence** (steps/min) via Running Speed & Cadence Service (0x1814)
- **Cycling cadence** (RPM) via Cycling Speed & Cadence Service (0x1816)
- Device manufacturer and model read from Device Information Service (0x180A)
- Cadence data stored alongside GPS points in each track

### 🤖 AI Analysis
- **Real-time activity detection**: Walking, Running, Hiking, Cycling, Driving, Flying, Stationary
- **Configurable AI mode** — choose per session:
  - `Rule-Based` — immediate classification from speed/altitude thresholds (always available)
  - `TFLite Model` — on-device ML model for nuanced classification
  - `Local AI Model` — uses phone's built-in AI (Android AICore / MediaPipe LLM)
  - `Cloud AI` — Deepseek, OpenAI, Anthropic, Gemini (API key required)
  - `Off` — disable automatic detection
- **Model catalog**: browse, download, and manage on-device models
- **Post-trip analysis**: track segmentation, segment-level activity breakdown, trip suggestions
- **Calorie estimation** based on user weight and activity MET values

### 💾 Local Storage & Export
- Room database for structured persistence
- One-tap export to pretty-printed JSON, GPX, or CSV files
- Export includes session metadata, all GPS points, and AI analysis
- Files saved to `Android/data/com.trackmyjourney/files/tracks/`

### 🔔 Webhooks
- Push track data to a custom endpoint on trip completion

## Architecture

```
com.trackmyjourney/
├── TrackMyJourneyApp.kt         # Hilt Application
├── data/
│   ├── model/Models.kt          # Entities, enums, export models
│   ├── local/
│   │   ├── TrackDatabase.kt     # Room DB + DAOs
│   │   └── SettingsDataStore.kt # DataStore preferences
│   ├── location/
│   │   ├── LocationTracker.kt       # Fused Location Provider
│   │   ├── GpsSatelliteTracker.kt   # Satellite quality monitoring
│   │   ├── SmartIntervalManager.kt  # Adaptive recording interval
│   │   ├── MotionSensorManager.kt   # Motion-based filtering
│   │   └── BatteryMonitor.kt        # Battery-aware tracking
│   ├── bluetooth/WearableManager.kt # BLE GATT client
│   ├── ai/
│   │   ├── LocalAiEngine.kt         # Activity classifier + analysis orchestrator
│   │   ├── provider/                # AI provider implementations (Cloud, System, Local)
│   │   ├── runtime/                 # Runtime adapters (MediaPipe, LiteRt, SystemAI)
│   │   └── models/                  # Model catalog, installer, and compatibility
│   ├── billing/BillingManager.kt    # Google Play Billing
│   ├── webhook/WebhookSender.kt     # Outbound webhook integration
│   └── repository/TrackRepository.kt # Single source of truth
├── di/AppModule.kt              # Hilt DI bindings
├── service/
│   ├── TrackingService.kt       # Foreground location service
│   └── ModelDownloadService.kt  # Background model downloads
└── ui/
    ├── MainActivity.kt          # Navigation host
    ├── TrackingStateViewModel.kt # Global tracking state
    ├── theme/Theme.kt           # Material 3 theming
    ├── navigation/Screen.kt     # Route definitions
    ├── components/OsmMapView.kt # OSM composable wrapper
    └── screens/
        ├── map/                 # Live tracking map
        ├── tracks/              # Track history list
        ├── dashboard/           # Summary dashboard
        ├── analysis/            # AI insights
        ├── aiengine/            # AI engine configuration
        ├── aiwizard/            # First-run AI setup wizard
        ├── onboarding/          # Onboarding flow
        ├── subscription/        # Subscription management
        └── settings/            # App configuration
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 (BOM 2025.04.01) |
| Map | osmdroid 6.1.20 (OpenStreetMap) |
| Database | Room 2.8.4 |
| DI | Hilt 2.59.2 |
| Location | Fused Location Provider (Play Services 21.3.0) |
| Bluetooth | Android BLE GATT API + Nordic BLE 2.11.0 |
| AI/ML | MediaPipe LLM Inference 0.10.33 + rule-based engine |
| Settings | DataStore Preferences 1.2.1 |
| Background | Foreground Service + WorkManager 2.11.2 |
| Serialization | Gson 2.13.2 |
| Billing | Google Play Billing 8.3.0 |

## Setup

1. **Clone and open** in Android Studio Ladybug or later
2. **Sync Gradle** — all dependencies are from Maven Central / Google
3. **API keys (optional)**: Create `local.properties` in the project root and add any cloud AI keys you want to use:
   ```properties
   DEEPSEEK_API_KEY=sk-...
   OPENAI_API_KEY=sk-...
   ANTHROPIC_API_KEY=sk-ant-...
   GEMINI_API_KEY=AIza...
   ```
   The app works without any keys using the on-device rule-based classifier or a downloaded local model.
4. **Run** on a physical device (BLE and GPS require real hardware)

## Permissions Required

| Permission | Reason |
|-----------|--------|
| ACCESS_FINE_LOCATION | GPS tracking |
| ACCESS_COARSE_LOCATION | Fallback location |
| ACCESS_BACKGROUND_LOCATION | Background tracking |
| BLUETOOTH_SCAN / CONNECT | Smartwatch pairing |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION | Reliable background tracking |
| FOREGROUND_SERVICE_DATA_SYNC | Background model downloads |
| POST_NOTIFICATIONS | Tracking notification |
| ACTIVITY_RECOGNITION | Android activity transitions |
| INTERNET / ACCESS_NETWORK_STATE | Cloud AI providers and model downloads |
| WAKE_LOCK | Prevent sleep during active tracking |

## JSON Export Format

```json
{
  "session": {
    "id": "uuid",
    "name": "Morning Walk",
    "start_time": 1708531200000,
    "end_time": 1708534800000,
    "distance_meters": 4523.7,
    "avg_speed_kmh": 5.2,
    "max_speed_kmh": 6.8,
    "activity_type": "WALKING",
    "start_place_name": "Central Park",
    "end_place_name": "Times Square",
    "calories_burned": 312.5,
    "battery_start": 85,
    "battery_end": 78,
    "ride_cost": null
  },
  "points": [
    {
      "latitude": 40.1234,
      "longitude": 44.5678,
      "altitude": 1050.3,
      "speed_kmh": 5.1,
      "bearing": 127.5,
      "accuracy": 3.2,
      "timestamp": 1708531203000,
      "cadence": 92,
      "activity_type": "WALKING",
      "place_name": null,
      "satellites_used": 12,
      "is_accurate": true
    }
  ],
  "ai_analysis": {
    "detected_activity": "WALKING",
    "confidence": 0.92,
    "summary": "Activity: Walking (92% confidence)\nDistance: 4.52km | Duration: 60min\nAvg Speed: 5.2 km/h",
    "suggestions": ["Great short trip!", "Try a longer route next time"],
    "segment_activities": [
      { "start_index": 0, "end_index": 45, "activity_type": "WALKING", "confidence": 0.95 }
    ]
  }
}
```

## Activity Detection Thresholds

Default speed ranges used by the rule-based classifier (fully configurable in settings):

| Activity | Speed Range | MET Value | Enabled by Default |
|----------|------------|-----------|-------------------|
| Stationary | < 0.5 km/h | 1.0 | Yes |
| Walking | 0.5 – 6 km/h | 3.5 | Yes |
| Hiking | 2 – 6 km/h | 4.5 | No |
| Running | 6 – 15 km/h | 8.0 | Yes |
| Cycling | 15 – 35 km/h | 6.0 | Yes |
| Driving | 35 – 200 km/h | 0.0 | Yes |
| Flying | > 200 km/h | 1.0 | Yes |

MET values are used for calorie estimation based on user weight.

## License

MIT License
