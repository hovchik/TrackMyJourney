# TrackMyJourney 🗺️

An Android application that tracks your movements on OpenStreetMap, integrates with Garmin/Samsung smartwatches via Bluetooth LE for health metrics, and uses on-device AI to analyze your trips.

## Features

### 📍 GPS Tracking with OpenStreetMap
- Real-time location tracking rendered on OSM (osmdroid)
- Color-coded track segments by detected activity type
- Configurable recording interval (1s – 30s) and minimum distance filter
- Speed recording at every data point
- Foreground service for reliable background tracking

### ⌚ Smartwatch Integration (Bluetooth LE)
- **Garmin watches** — Heart Rate via standard BLE GATT Heart Rate Service (0x180D)
- **Samsung Galaxy watches** — Heart Rate + SpO2 via BLE GATT
- Auto-detection of device type from BLE advertisement name
- Real-time heart rate (BPM) and blood oxygen saturation (SpO2 %) display
- Health data stored alongside GPS points in each track

### 🤖 On-Device AI Analysis
- **Real-time activity detection**: Walking, Running, Cycling, Driving, Flying, Stationary
- **Rule-based classifier** works immediately with speed + altitude heuristics
- **Optional TFLite model** slot — drop `activity_classifier.tflite` into `assets/` for ML inference
- **Post-trip analysis**: Track segmentation, health insights, trip suggestions
- **Best trip suggestions** across your history (best cardio, most scenic, longest, etc.)
- All processing runs locally — no data leaves your device

### 💾 Local JSON Storage
- Room database for structured persistence
- One-tap export to pretty-printed JSON files
- Export includes session metadata, all GPS points, health readings, and AI analysis
- Files saved to `Android/data/com.trackjourney/files/tracks/`

## Architecture

```
com.trackjourney/
├── TrackMyJourneyApp.kt         # Hilt Application
├── data/
│   ├── model/Models.kt          # Entities, enums, export models
│   ├── local/
│   │   ├── TrackDatabase.kt     # Room DB + DAOs
│   │   └── SettingsDataStore.kt # DataStore preferences
│   ├── location/LocationTracker.kt  # Fused Location Provider
│   ├── bluetooth/WearableManager.kt # BLE GATT client
│   ├── ai/LocalAiEngine.kt     # Activity classifier + analysis
│   └── repository/TrackRepository.kt # Single source of truth
├── di/AppModule.kt              # Hilt DI bindings
├── service/TrackingService.kt   # Foreground location service
└── ui/
    ├── MainActivity.kt          # Navigation host
    ├── theme/Theme.kt           # Material 3 theming
    ├── navigation/Screen.kt     # Route definitions
    ├── components/OsmMapView.kt # OSM composable wrapper
    └── screens/
        ├── map/                 # Live tracking map
        ├── tracks/              # Track history list
        ├── analysis/            # AI insights dashboard
        └── settings/            # Configuration
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Map | osmdroid 6.1.18 (OpenStreetMap) |
| Database | Room 2.6.1 |
| DI | Hilt 2.50 |
| Location | Fused Location Provider (Play Services) |
| Bluetooth | Android BLE GATT API + Nordic BLE library |
| AI/ML | TensorFlow Lite 2.14 + rule-based engine |
| Settings | DataStore Preferences |
| Background | Foreground Service + WorkManager |
| Serialization | Gson |

## Setup

1. **Clone and open** in Android Studio Hedgehog or later
2. **Sync Gradle** — all dependencies are from Maven Central / Google
3. **Garmin SDK (optional)**: Download Connect IQ Mobile SDK from [developer.garmin.com](https://developer.garmin.com), place `.aar` in `app/libs/`, uncomment the dependency in `build.gradle.kts`
4. **TFLite model (optional)**: Place `activity_classifier.tflite` in `app/src/main/assets/`. The app works without it using rule-based classification
5. **Run** on a physical device (BLE and GPS require real hardware)

## Permissions Required

| Permission | Reason |
|-----------|--------|
| ACCESS_FINE_LOCATION | GPS tracking |
| ACCESS_BACKGROUND_LOCATION | Background tracking |
| BLUETOOTH_SCAN / CONNECT | Smartwatch pairing |
| FOREGROUND_SERVICE_LOCATION | Reliable background tracking |
| POST_NOTIFICATIONS | Tracking notification |
| ACTIVITY_RECOGNITION | Android activity transitions |

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
    "avg_heart_rate": 92,
    "avg_spo2": 97
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
      "heart_rate": 88,
      "spo2": 97,
      "activity_type": "WALKING"
    }
  ],
  "health_data": [
    {
      "timestamp": 1708531203000,
      "heart_rate": 88,
      "spo2": 97,
      "device_name": "Garmin Venu 3",
      "device_type": "GARMIN"
    }
  ],
  "ai_analysis": {
    "detected_activity": "WALKING",
    "confidence": 0.92,
    "summary": "📍 Trip Summary\nActivity: Walking (92% confidence)\nDistance: 4.52km | Duration: 60min\nAvg Speed: 5.2 km/h",
    "suggestions": [
      "Great short trip!",
      "Heart rate in optimal zone"
    ],
    "health_insights": "Heart Rate: avg 92 bpm, range 78-112 bpm\nGood cardio zone\nSpO2: avg 97%, healthy range"
  }
}
```

## Activity Detection Thresholds

| Activity | Speed Range | Additional Signals |
|----------|-----------|-------------------|
| Stationary | < 0.5 km/h | — |
| Walking | 0.5 – 7 km/h | — |
| Running | 7 – 15 km/h | — |
| Cycling | 15 – 40 km/h | Lower speed variance |
| Driving | 40 – 250 km/h | Stop-and-go patterns |
| Flying | > 250 km/h | Rapid altitude change |

## License

MIT License
