# Terms of Use

**Last updated: March 10, 2026**

Welcome to **Pathwise Tracker** ("the App"), an Android application developed and published under the package name **com.trackmyjourney**. By downloading, installing, or using the App, you agree to be bound by these Terms of Use ("Terms"). If you do not agree to these Terms, do not use the App.

## 1. Acceptance of Terms

By accessing or using Pathwise Tracker, you confirm that you are at least 13 years of age and have the legal capacity to enter into these Terms. If you are using the App on behalf of an organization, you represent that you have the authority to bind that organization to these Terms.

## 2. Description of the App

Pathwise Tracker is a GPS tracking and journey logging application that provides:

- Real-time GPS location tracking displayed on OpenStreetMap
- Bluetooth LE integration with compatible Garmin and Samsung smartwatches for heart rate and SpO2 monitoring
- On-device AI-powered activity detection (walking, running, cycling, driving, flying, stationary)
- Local storage and JSON export of tracking sessions and health data
- Web-based live tracking and data explorer via webhook connectivity

## 3. User Data and Privacy

Pathwise Tracker is designed with a privacy-first approach:

- **Local processing:** All GPS data, health metrics, and AI analysis are processed and stored locally on your device. No personal data is sent to external servers unless you explicitly use the webhook live-tracking feature.
- **Location data:** The App collects precise GPS location data (latitude, longitude, altitude, speed, bearing) only while a tracking session is active. Background location access is used solely to maintain tracking accuracy when the App is not in the foreground.
- **Health data:** Heart rate and SpO2 data are collected from connected Bluetooth LE devices only during active tracking sessions and stored locally on your device.
- **Webhook feature:** If you choose to use the live-tracking webhook feature, location and session data will be transmitted to the connected web server. You are responsible for the security of your webhook key.
- **No third-party sharing:** We do not sell, rent, or share your personal data with third parties.
- **Data export:** You may export your tracking data as JSON files at any time. Exported files are saved to your device's local storage.

For more details, please refer to our [Privacy Policy](PRIVACY_POLICY.md).

## 4. Permissions

The App requests the following device permissions, each necessary for core functionality:

- **Location (Fine, Coarse, Background):** Required for GPS tracking and route recording.
- **Bluetooth (Scan, Connect):** Required for pairing with compatible smartwatches to receive health data.
- **Foreground Service (Location):** Required for reliable tracking when the App is in the background.
- **Notifications:** Required to display the ongoing tracking notification.
- **Activity Recognition:** Required for detecting your movement type (walking, driving, etc.).
- **Internet and Network State:** Required for the optional webhook-based live-tracking feature and map tile loading.

You may revoke any permission at any time through your device settings, though this may limit the App's functionality.

## 5. Acceptable Use

You agree to use the App only for lawful purposes. You shall not:

- Use the App to track or monitor another person without their knowledge and consent
- Attempt to reverse-engineer, decompile, or disassemble the App beyond what is permitted by applicable law
- Use the App in any way that could damage, disable, or impair the App or interfere with other users
- Use the webhook feature to transmit data to unauthorized or malicious servers
- Redistribute, sublicense, or resell the App or any part of it

## 6. Health Data Disclaimer

The health metrics provided by the App (heart rate, SpO2, activity detection) are for **informational and fitness purposes only**. They are not intended to diagnose, treat, cure, or prevent any medical condition. The accuracy of health data depends on your connected wearable device. Do not rely on the App for medical decisions. Always consult a qualified healthcare professional for health concerns.

## 7. Location Accuracy Disclaimer

GPS location data is subject to inherent inaccuracies caused by atmospheric conditions, device hardware, signal obstructions, and other factors. The App provides location data on a best-effort basis and does not guarantee the accuracy, completeness, or reliability of any GPS coordinates, speed, or altitude readings.

## 8. Intellectual Property

All rights, title, and interest in and to the App, including its source code, design, graphics, and documentation, are owned by the developer. The App is licensed to you under the MIT License. OpenStreetMap data is provided under the [Open Data Commons Open Database License (ODbL)](https://www.openstreetmap.org/copyright).

## 9. Third-Party Services

The App integrates with or relies on the following third-party services:

- **OpenStreetMap:** Map tiles and geographic data are provided by OpenStreetMap contributors.
- **Google Play Services:** Used for the Fused Location Provider.
- **Garmin / Samsung:** Smartwatch connectivity is provided via standard Bluetooth LE protocols. The App is not affiliated with or endorsed by Garmin Ltd. or Samsung Electronics.

Your use of these third-party services is subject to their respective terms and conditions.

## 10. Limitation of Liability

To the maximum extent permitted by applicable law, the App is provided "AS IS" and "AS AVAILABLE" without warranties of any kind, whether express or implied. The developer shall not be liable for any indirect, incidental, special, consequential, or punitive damages, including but not limited to loss of data, loss of profits, or personal injury arising from your use of the App.

## 11. Indemnification

You agree to indemnify and hold harmless the developer from any claims, damages, losses, or expenses (including reasonable legal fees) arising from your use of the App or violation of these Terms.

## 12. Termination

You may stop using the App at any time by uninstalling it from your device. We reserve the right to suspend or terminate your access to any server-side features (such as the webhook service) if you violate these Terms.

## 13. Changes to These Terms

We may update these Terms from time to time. When we do, we will revise the "Last updated" date at the top of this page. Continued use of the App after any changes constitutes your acceptance of the updated Terms. We encourage you to review these Terms periodically.

## 14. Governing Law

These Terms shall be governed by and construed in accordance with the laws of the jurisdiction in which the developer resides, without regard to conflict of law principles.

## 15. Contact

If you have any questions about these Terms of Use, please contact us at:

**Email:** support@pathwisetracker.com
