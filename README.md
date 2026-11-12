<div align="center">

# 🚨 ResQ

### An All-in-One Emergency Assistance and Safety App for Android

**One tap sends your location, battery level and a live-tracking code to the people you trust, even without internet.**

![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-34-blue)
![Firebase](https://img.shields.io/badge/Backend-Firebase-FFCA28?logo=firebase&logoColor=black)
![Course](https://img.shields.io/badge/KUET-CSE%202200-red)

</div>

---

## 📖 Table of Contents

1. [About](#-about)
2. [Screenshots](#-screenshots)
3. [Features](#-features)
4. [How the key features work](#-how-the-key-features-work)
5. [Tech stack](#-tech-stack)
6. [Architecture](#-architecture)
7. [Project structure](#-project-structure)
8. [Getting started](#-getting-started)
9. [Firebase setup](#-firebase-setup)
10. [Permissions](#-permissions)
11. [Data model](#-data-model)
12. [Security rules](#-security-rules)
13. [Android concepts used](#-android-concepts-used)
14. [Testing checklist](#-testing-checklist)
15. [Limitations](#-limitations)
16. [Future work](#-future-work)
17. [Author](#-author)

---

## 📌 About

In an emergency, people usually need many separate tools: a dialer for the national number, a messaging app to tell family, a map to find a hospital, and a web page for first-aid steps.

**ResQ puts all of them in one app.** When the user presses the **SOS** button, the app:

1. reads the GPS position,
2. builds a message with the user's **name, blood group, phone, battery level, time, coordinates, a Google Maps link and a 6-character tracking code**,
3. sends it by **SMS** to up to five emergency contacts (works **without internet**),
4. posts the same alert to the user's chat rooms,
5. starts **live location sharing**, so a friend can enter the code and follow the user on a live screen.

Besides SOS, ResQ has a loud **siren with a flashing light**, a **home-screen widget**, **blood donor search and chat**, **hospitals sorted by distance**, **offline first-aid guides**, national **helplines**, private **chat rooms**, a **Preparedness score**, and automatic **low-battery** and **airplane-mode** warnings.

---

## 📸 Screenshots

| Login | Home | Home (dark) | SOS progress |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/01_login.jpg" width="170"> | <img src="docs/screenshots/02_home.jpg" width="170"> | <img src="docs/screenshots/03_home_dark.jpg" width="170"> | <img src="docs/screenshots/04_sos_progress.jpg" width="170"> |

| SOS SMS | Track a friend | My Circle | Blood Donors |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/05_sms.jpg" width="170"> | <img src="docs/screenshots/06_track_friend.jpg" width="170"> | <img src="docs/screenshots/07_my_circle.jpg" width="170"> | <img src="docs/screenshots/08_blood_donors.jpg" width="170"> |

| Chat room | Hospitals | First Aid | Preparedness |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/09_chat_room.jpg" width="170"> | <img src="docs/screenshots/10_hospitals.jpg" width="170"> | <img src="docs/screenshots/11_first_aid.jpg" width="170"> | <img src="docs/screenshots/12_preparedness.jpg" width="170"> |

---

## ✨ Features

### 🆘 Emergency actions
| Feature | What it does |
|---|---|
| **SOS button** | One tap sends an SMS with location, battery, blood group and tracking code to every contact in *My Circle*. |
| **SOS delivery tracking** | A progress notification ("Sending SOS…", then "sent 3 of 3, delivery confirmed for 2"). If sending fails, it shows "SOS NOT sent" with a **CALL 999** button. |
| **Live location sharing** | Position, accuracy and battery are written to Firebase every 10 seconds. Stops by itself after 2 hours. |
| **Track a friend** | Enter the code from an SOS SMS to see **LIVE** position, battery and last update, with *Open in Maps* and *Call*. |
| **I'M SAFE** | One tap tells the circle and chat rooms "I am SAFE now" and stops sharing. |
| **Siren and flashing light** | Loud alarm sound at maximum alarm volume plus a blinking camera torch. Works with no internet, no GPS and no SIM. |
| **Home-screen widget** | SOS, Siren, Hospital, Call 999 and First aid tiles. Turns red while the siren is on. |
| **Emergency type chips** | Medical, Fire, Accident or Disaster can be added to the SOS message. |

### 👥 People
| Feature | What it does |
|---|---|
| **My Circle** | Up to 5 emergency contacts, added by typing or by searching the phone contacts. |
| **Chat rooms** | Create or join a room with a 6-letter code. Live messages, member photos, location sharing, quick-message chips (*Feeling uneasy*, *Being followed*, *Please call me*), mute and notifications. SOS is also posted into the rooms. |
| **Blood donors** | Register as a donor, search by blood group and city, **call** a donor or send a **chat request** (the donor must accept first). |

### 🏥 Help nearby
| Feature | What it does |
|---|---|
| **Hospitals** | Shared and personal hospitals sorted by distance, with **Call** and **Navigate**. |
| **Nearest Hospital** | One tap opens Google Maps directions to the closest hospital. |
| **Add a hospital** | Share a place from Google Maps to ResQ. The name, address and phone are filled in using the **OpenStreetMap Nominatim REST API**. |
| **First Aid** | 13 offline topics (bleeding, burns, CPR, choking…) in list or grid view. |
| **Helplines** | 999, 16163, 16263, 109, 333. Opens the dialer. |

### 🛡️ Safety net
| Feature | What it does |
|---|---|
| **Low-battery alert** | Warns when the battery is low, with a **SEND SOS…** button. |
| **Airplane-mode alert** | Warns that SOS SMS cannot be sent, with a **TURN OFF** button. |
| **Preparedness score** | A 7-step checklist (name, phone, blood group, contact, circle of 3, donor registration, trusted hospital). |
| **Lock-screen safe** | Private notifications hide details on the lock screen. *STOP* from the lock screen is a setting. |
| **Restart safe** | Chat notifications restart after a phone reboot or app update. |

### 🎨 Experience
- **Light and dark mode** with a hand-drawn sun/moon switch (custom `View`), saved on the phone.
- **Profile photo** from camera or gallery, stored as Base64 text (no Firebase Storage needed).
- **Landscape** and **tablet** layouts.

---

## ⚙️ How the key features work

### SOS message

```text
SOS! I need help.
Name: Md Sibatullah Hosen Rafi
Blood group: A+
My phone: 01319097686
Battery: 89%
Time: 4:28 AM, 09 Nov
GPS: 22.89743, 89.50362
Location: https://maps.google.com/?q=22.8974331,89.5036153
Track live in ResQ: ZZFYQ8
```

1. The location comes from the **Fused Location Provider** (`PRIORITY_HIGH_ACCURACY`). If no fresh fix arrives in **8 seconds**, the last known location is used. If there is none, the SOS is still sent with "Location unavailable".
2. `MedicalProfile.buildSosMessage()` joins the lines with a `StringBuilder`. Empty fields are skipped.
3. `SmsSender` splits long text (`divideMessage`) and sends it with `SmsManager` to every contact, using the default SMS SIM.
4. Each part has its own *sent* and *delivered* `PendingIntent`. `SmsResultReceiver` turns the results into the progress notification.
5. An `AlarmManager` timeout makes sure a final result is shown even if a delivery report never arrives.

### Live tracking

```text
Sender phone                         Firebase Realtime DB                Friend phone
────────────                         ────────────────────                ────────────
LocationShareService  ── every 10s ─▶ sos_alerts/{uid}  ◀── listener ──  TrackActivity
        │                                                                     ▲
        └── at start ───────────────▶ sos_codes/{code} = uid ─── lookup ─────┘
```

- The **code** is 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `O`, `I`, `0`, `1`), made with `SecureRandom`.
- `sos_codes/{code}` points to the sender's `uid`. `sos_alerts/{uid}` holds `lat`, `lng`, `accuracy`, `battery`, `name`, `phone`, `startedAt`, `updatedAt`.
- The friend sees **LIVE**, **No recent update** (after 90 s) or **Sharing has ended**.
- Sharing stops on **Stop**, **STOP SHARING**, **I'M SAFE** or after **2 hours**. Both entries are then deleted.

### Does it work offline?

| Situation | SOS SMS | Location in SMS | Live tracking and chat |
|---|:---:|:---:|:---:|
| No internet, mobile signal present | ✅ | ✅ | ❌ |
| No signal or airplane mode | ❌ | ❌ | ❌ |

SMS uses the mobile network's SMS service and GPS uses satellites, so the SOS works without internet. The siren works in every case.

### Profile photo without Firebase Storage
Photo → centre-crop to a square → 256×256 JPEG (quality 75) → Base64 text → `users/{uid}.photoBase64`. A 96 px thumbnail goes to `chatRooms/{code}/members/{uid}.photo` for chat bubbles.

### Blood donor chat
The *Chat* button sends a request to an **available** donor. The donor sees it under **Blood requests** in *Chat rooms* and presses **Accept** or **Decline**. The chat (`d_{donorUid}_{requesterUid}`) opens only after Accept and only these two people can be in it.

---

## 🧰 Tech stack

| Area | Technology |
|---|---|
| Language | Java 8 |
| IDE / build | Android Studio, Gradle 8.7, Android Gradle Plugin 8.5.2 |
| SDK | `minSdk 24`, `targetSdk 34`, `compileSdk 34` |
| UI | Material Components 1.12, ConstraintLayout 2.2, RecyclerView 1.3.2, View Binding |
| Location | Google Play Services Location 21.3 (Fused Location Provider) |
| Backend | Firebase (BoM 32.7): **Authentication**, **Cloud Firestore**, **Realtime Database**, Analytics |
| External API | **OpenStreetMap Nominatim** (REST, JSON) |
| Phone APIs | `SmsManager`, `MediaPlayer`, `AudioManager`, `CameraManager` (torch), `ContentResolver`, `AlarmManager`, `ClipboardManager` |

---

## 🏗️ Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│ 1. Presentation   17 Activities, RecyclerView Adapters,       │
│                   custom Views                                │
├──────────────────────────────────────────────────────────────┤
│ 2. Background     SirenService · LocationShareService ·       │
│                   ChatNotifyService · Receivers · Widget      │
├──────────────────────────────────────────────────────────────┤
│ 3. Utilities      SosNotifier · SystemAlerts · TrackCode ·    │
│                   CircleStore · NearestHospital · AvatarUtil  │
├──────────────────────────────────────────────────────────────┤
│ 4. Phone          SharedPreferences · SmsManager · Contacts · │
│                   Fused Location · Battery · Camera torch     │
├──────────────────────────────────────────────────────────────┤
│ 5. Cloud          Firebase Auth · Firestore · Realtime DB ·   │
│                   Nominatim REST API                          │
└──────────────────────────────────────────────────────────────┘
```

**Navigation:** `LoginActivity` → `MainActivity` (bottom bar: **Home · My Circle · Donors · Hospitals · Profile**).

| Service | Type | Job |
|---|---|---|
| `SirenService` | `mediaPlayback` | Siren sound and flashing torch |
| `LocationShareService` | `location` | Live location every 10 s |
| `ChatNotifyService` | `remoteMessaging` | Chat and blood-request notifications |

| Receiver | Registered | Listens to |
|---|---|---|
| `SmsResultReceiver` | Manifest | `SMS_SENT`, `SMS_DELIVERED`, `SOS_TIMEOUT` |
| `BootReceiver` | Manifest | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` |
| `SystemEventReceiver` | Dynamic (`ResQApp`) | `BATTERY_LOW`, `BATTERY_OKAY`, `AIRPLANE_MODE_CHANGED` |
| `ResQWidget` | Manifest | `APPWIDGET_UPDATE`, `WIDGET_TOGGLE_SIREN` |

---

## 📁 Project structure

```text
ResQ/
├── app/
│   ├── google-services.json            # Firebase config (replace with your own)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/resq/
│       │   ├── LoginActivity, RegisterActivity, MainActivity, ProfileActivity, KpiActivity
│       │   ├── CircleActivity, EmergencyContactActivity, TrackActivity
│       │   ├── DonorsActivity, DonorRegisterActivity
│       │   ├── ChatRoomsActivity, ChatActivity
│       │   ├── HospitalsActivity, AddHospitalActivity, WidgetHospitalActivity
│       │   ├── FirstAidActivity, EmergencyNumbersActivity
│       │   ├── SirenService, LocationShareService, ChatNotifyService
│       │   ├── SmsResultReceiver, BootReceiver, SystemEventReceiver, ResQWidget
│       │   ├── SosNotifier, SmsSender, SafeMessage, SystemAlerts
│       │   ├── MedicalProfile, CircleStore, TrackCode, ChatSos, ChatMute
│       │   ├── NearestHospital, NavHelper, NetUtil, Readiness, AvatarUtil
│       │   ├── ThemeHelper, ThemeToggleView, CodeClipboard, Rtdb
│       │   └── *Adapter (RecyclerView adapters), ResQApp
│       └── res/
│           ├── layout, layout-land, values, values-night, values-sw600dp …
│           ├── raw/siren.wav
│           └── xml/ (widget info, file_paths)
├── FIRESTORE_RULES_FULL.rules          # Firestore security rules
├── FIRESTORE_CHAT_RULES.txt
├── HOSPITALS_SETUP.txt                 # How to add shared hospitals
├── PROFILE_PICTURE_SETUP.txt           # How profile pictures work
└── README.md
```

---

## 🚀 Getting started

### Requirements
- **Android Studio** (Koala or newer recommended)
- **JDK** from Android Studio (*Settings → Build Tools → Gradle → Gradle JDK → jbr*)
- A **real Android phone** (Android 7.0+) with a **SIM card** for SMS. The emulator cannot send real SMS or give a real GPS fix.
- A free **Firebase** account

### Run the app
1. Clone or extract the project.
2. In Android Studio choose **File → Open** and select the `ResQ` folder (the one with `settings.gradle.kts`).
3. Wait for **Gradle sync** to finish.
4. Connect your phone by USB (USB debugging on) and press **Run ▶**.
5. On first launch, allow **Location**, **SMS**, **Contacts** and **Notifications** when asked.

> ⚠️ A real SOS sends a **real SMS** and uses SMS credit. For testing, add **your own second phone's number** as the only contact.

---

## 🔥 Firebase setup

The app needs **your own** Firebase project. The file `app/google-services.json` in the repository belongs to the original project, so replace it.

1. Create a project in the [Firebase console](https://console.firebase.google.com).
2. **Add an Android app** with package name `com.example.resq`, download `google-services.json`, and put it in `app/`.
3. **Authentication → Sign-in method →** enable **Email/Password**.
4. **Firestore Database → Create database** (production mode). Then open **Rules** and paste the content of `FIRESTORE_RULES_FULL.rules`, and press **Publish**.
5. **Realtime Database → Create database.** Copy its URL into `Rtdb.java`:
   ```java
   // app/src/main/java/com/example/resq/Rtdb.java
   "https://<your-project-id>-default-rtdb.<region>.firebasedatabase.app"
   ```
   Then paste these **Rules** and publish:
   ```json
   {
     "rules": {
       "sos_alerts": {
         "$uid": {
           ".read": "auth != null",
           ".write": "auth != null && auth.uid === $uid"
         }
       },
       "sos_codes": {
         "$code": {
           ".read": "auth != null",
           ".write": "auth != null && ((!data.exists() && newData.val() === auth.uid) || (data.exists() && data.val() === auth.uid && !newData.exists()))"
         }
       }
     }
   }
   ```
6. *(Optional)* Add shared hospitals as described in `HOSPITALS_SETUP.txt`.
7. **Firebase Storage is not used.** Profile pictures are saved as Base64 text in Firestore.

---

## 🔐 Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` (runtime), `ACCESS_COARSE_LOCATION` | GPS for SOS, hospitals and live sharing |
| `SEND_SMS` (runtime) | Send the SOS and *I'M SAFE* messages |
| `READ_CONTACTS` (runtime) | Pick emergency contacts from the phone |
| `POST_NOTIFICATIONS` (runtime, Android 13+) | Show notifications |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Firebase and the Nominatim API |
| `FOREGROUND_SERVICE` (+ `_LOCATION`, `_MEDIA_PLAYBACK`, `_REMOTE_MESSAGING`) | The three foreground services (Android 14) |
| `RECEIVE_BOOT_COMPLETED` | Restart chat notifications after reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep chat notifications alive |

Calls use `ACTION_DIAL`, so **no `CALL_PHONE` permission** is needed. The user presses the call button.

---

## 🗄️ Data model

### Cloud Firestore
```text
users/{uid}                    name, phone, bloodGroup, email, photoBase64, updatedAt
  ├─ contacts/{id}             name, phone
  ├─ myHospitals/{id}          name, address, phone, lat, lng, emergency, createdAt
  └─ chatRooms/{roomId}        joined rooms, mutedUntil
donors/{uid}                   name, bloodGroup, city, cityLower, phone, available, uid
hospitals/{id}                 name, address, phone, lat, lng, emergency   (read-only)
chatRooms/{code}               name, ownerUid, createdAt
  ├─ members/{uid}             name, photo (96 px Base64)
  └─ messages/{id}             senderUid, senderName, text, createdAt
chatRooms/d_{donor}_{requester}   blood chat: type, status, participants, bloodGroup, note
```

### Realtime Database
```text
sos_alerts/{uid}   active, code, name, phone, lat, lng, accuracy, battery, startedAt, updatedAt
sos_codes/{code}   = uid of the sender
```

---

## 🛡️ Security rules

- A user can **read and write only their own** profile, contacts and hospitals. Profile photo is limited to **200,000 characters**.
- A **donor** document can be changed or deleted **only by its owner**.
- Shared **hospitals** are **read-only** from the app.
- A **chat room** opens only if you know its **code**; rooms **cannot be listed**. Only **members** read or write messages, a sender can only write as **themselves**, a message is **≤ 500 characters**, and messages can **never be edited or deleted**.
- A **blood chat** is created only with a donor whose `available` is `true`. Only its two people can list or join it, and after creation only `status` can change.
- In the Realtime Database only **signed-in** users read, and a user writes **only their own** `sos_alerts` entry.

---

## 📚 Android concepts used

| Concept | Where |
|---|---|
| **Activity and lifecycle** | 17 activities; listeners attached in `onStart` and removed in `onStop`; lifecycle toasts in `FirstAidActivity` |
| **Intents** | Explicit (screens), implicit (`ACTION_DIAL`, `ACTION_VIEW`, `ACTION_SEND`), **intent filter** (`ACTION_SEND` + `text/plain` so Google Maps can share to ResQ) |
| **PendingIntent** | Notification buttons, widget tiles, SMS sent/delivered reports |
| **Services** | 3 foreground services with Android 14 service types |
| **BroadcastReceiver** | Manifest and dynamic receivers |
| **AppWidget / RemoteViews** | `ResQWidget` |
| **Notifications** | Channels, actions, progress, heads-up, lock-screen visibility |
| **Runtime permissions** | `ActivityResultContracts` |
| **RecyclerView / Adapter** | Lists everywhere; list and grid via `LayoutManager` |
| **SharedPreferences** | Offline profile, circle, settings, theme |
| **ContentResolver / FileProvider** | Phone contacts; camera photo |
| **Threads and Handler** | Nominatim request on a worker thread; timers with `postDelayed` |
| **AlarmManager** | SOS delivery timeout |
| **REST API and JSON** | Nominatim with `HttpURLConnection` and `org.json` |
| **Firebase** | Auth, Firestore (snapshot listeners), Realtime DB (value listeners), Security Rules |
| **Location, SMS, Audio, Camera torch, Clipboard** | `FusedLocationProviderClient`, `SmsManager`, `MediaPlayer`/`AudioManager`, `CameraManager`, `ClipboardManager` |
| **Theme** | `AppCompatDelegate` night mode, `values-night` |
| **Custom View** | `ThemeToggleView` drawn with `Canvas` and animated with `ValueAnimator` |

---

## ✅ Testing checklist

- [ ] Register, log in, log out, and reset password
- [ ] Fill the profile and see the Preparedness score rise
- [ ] Add a contact (typed and from the phone list)
- [ ] Press **SOS** and check the progress notification, the SMS and the live card
- [ ] Enter the code in **Track a friend** on another phone and see **LIVE**
- [ ] Press **I'M SAFE** and check the SMS and that sharing stops
- [ ] Start and stop the **siren** (Home tile, widget and notification button)
- [ ] Create a chat room, share the code, join from another account, send a message and an SOS
- [ ] Search blood donors, send a chat request, **Accept** on the donor phone
- [ ] Add a hospital by sharing from Google Maps; use **Nearest Hospital**
- [ ] Turn on **airplane mode** and check the warning; press SOS and check "SOS NOT sent"
- [ ] Use the Profile test buttons for the low-battery alert
- [ ] Switch **dark mode**, rotate the phone, and reboot to check chat notifications

---

## ⚠️ Limitations

- The SOS SMS needs a **SIM, signal and SMS credit**. It cannot be sent in airplane mode.
- **Live tracking and chat need internet** on both phones.
- Anyone who knows a tracking code can follow the sender **while sharing is on**. The code is removed when sharing stops.
- Profile pictures are small (256 px) because they are stored as text in Firestore.
- Nominatim does not have a phone number for every hospital, so the user may need to type it. A Google Places key would give more data but needs billing.
- Aggressive battery savers on some phones can stop foreground services.
- Donor phone numbers are visible to every signed-in user.
- The shared `hospitals` list must be filled from the Firebase console.

---

## 🔮 Future work

- Automatic SOS by shaking the phone or pressing the power button several times
- Push notifications with **Firebase Cloud Messaging**
- A larger shared hospital list and offline maps
- Bangla language support
- Hiding donor phone numbers until a donor accepts

---

## 👤 Author

**Md. Sibatullah Hossain Rafi**
Roll: **2307077** · Department of Computer Science and Engineering
**Khulna University of Engineering & Technology (KUET)**, Second Year Second Term

**Course:** CSE 2200, Advanced Programming Laboratory
**Submitted to:** Ehsanul Karim (Lecturer) and Safin Ahmmed (Assistant Professor), Department of CSE, KUET

---

<div align="center">

*Built so that help is one tap away.* 🚑

</div>
