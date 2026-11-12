ResQ (Java) - Milestone 1 (updated)
1. Extract the zip.
2. Android Studio -> File -> Open -> select the ResQ folder (the one containing settings.gradle.kts).
3. After Gradle sync, connect your phone by USB and press Run.

Added in this version:
- Activity lifecycle: onCreate, onStart, onRestart, onResume, onPause, onStop, onDestroy
  in MainActivity and FirstAidActivity (Logcat tag: ResQ_Lifecycle + Toast messages).
- Intent data passing: putExtra in MainActivity, getStringExtra in FirstAidActivity.
- Resources: values/strings.xml, values/colors.xml, drawable/ic_resq_logo.xml,
  mipmap launcher icons.
- Manifest uses @string/app_name, @string/first_aid and the new launcher icon.

Added in the latest update:
- EmergencyContactActivity: returns the saved number to the home screen (activity result).
- SMS status: PendingIntent + BroadcastReceiver show "sent / failed / delivered" after SOS.
- SirenService: startService()/stopService() plays the default alarm sound (Siren / Stop Siren buttons).
- onSaveInstanceState: keeps the typed contact number after screen rotation.
- Back to Home button on the First Aid screen (explicit intent).
- Emergency Contact screen now uses ConstraintLayout (new dependency in app/build.gradle.kts; press Sync Now).
JDK error: File > Settings > Build Tools > Gradle > Gradle JDK = jbr (Embedded JDK).

Added for the ListView / RecyclerView lab manual:
- EmergencyNumbersActivity (button "Emergency Numbers" on Home):
  * Simple ListView: String[] -> ArrayAdapter(simple_list_item_1) + setOnItemClickListener (position, Toast).
  * Custom ListView: item_emergency_number.xml + EmergencyNumberAdapter (getView, LayoutInflater, findViewById,
    convertView reuse); tapping a row dials numbers[position].
- First Aid screen: "Switch to Grid / List" button swaps LinearLayoutManager <-> GridLayoutManager(2 columns).

Step 1 - Firebase Authentication:
- LoginActivity is now the launcher: email/password login, forgot-password email, auto-skip if already logged in.
- RegisterActivity: name, email, password, confirm; saves the display name on the account.
- Profile tab (bottom bar) shows name/email and a Log out button.
- AuthErrorHelper: friendly error messages. Dependencies: firebase-bom, firebase-auth, google-services plugin.
- The first login/registration needs internet; after that the session is kept, so the app opens offline.

Step 2 - Cloud Firestore (medical profile):
- ProfileActivity (Profile tab): name, phone, blood group -> Firestore users/{uid} + local copy.
- MedicalProfile: model + SharedPreferences cache, so the SOS SMS includes medical info even offline.
- SOS SMS now contains name, blood group, phone and location (sent as a multipart SMS).
- Log out moved to the Profile screen (clears the local medical copy).
- Firestore needs a database in the console (Build > Firestore Database) and the rules given in the chat.

Step 2b - richer SOS message:
- Emergency type chips (Medical / Fire / Accident / Disaster) on the home screen, optional.
- SOS SMS now also contains Battery %, Time and plain GPS coordinates (works even if the maps link can't open offline).

Step 3 (My Circle): CircleActivity + CircleAdapter + CircleStore, Firestore users/{uid}/contacts, SOS sends to every contact.

Step 4 (Blood Donors): DonorsActivity (search) + DonorRegisterActivity, Firestore donors/{uid}.

Step 5 (Live location): LocationShareService (foreground, Realtime Database sos_alerts/{uid} + sos_codes/{code}), TrackActivity, tracking code in the SOS SMS.

v14: SOS always sends (location fallback), default-SIM SmsManager, SIM warning, readable SMS error text.

v15: if the phone reports an SMS failure, a dialog offers to open the Messages app with the SOS text ready.

v16: logout asks before stopping live sharing; Profile switch "Share my live location when I press SOS".

Step 6: HospitalsActivity (Firestore hospitals, nearest first, Call + Navigate, Google Maps fallback). See HOSPITALS_SETUP.txt.

v18: siren plays at full alarm volume (not into headphones) and restores the old volume when stopped.

v19: same bottom bar (with all labels) on every main + detail screen via NavHelper; "Disaster" quick button renamed "Helplines"; clearer Helplines list headings.

v20: bottom bar uses the SOS colours (selected red, others orange).

v21: removed the Home "Emergency Contact Settings" card. My Circle > Add contact opens EmergencyContactActivity (Content Provider contact picker) and gets name+number back with setResult. SOS with an empty circle shows a dialog that opens My Circle.

v22: Add a hospital from Google Maps (Share -> ResQ, or paste link/coordinates). Private list at users/{uid}/myHospitals merged into the Hospitals screen, with delete.

v23: Home "Medical" button is now "Nearest Hospital": one tap navigates to the nearest saved hospital (24h emergency first), or Google Maps navigation to the nearest hospital as a backup.

v24: notifications. SOS result notification (heads-up + drawer + lock screen) with Send-from-Messages action (SosNotifier + manifest SmsResultReceiver); siren/sharing notifications visible on the lock screen (tracking code hidden), STOP needs unlock unless allowed in Profile.

Profile picture: Base64 string in Firestore users/{uid}.photoBase64 + chat avatars. See PROFILE_PICTURE_SETUP.txt.

v47: delivery reports are counted once per contact (no more "Delivered to 2 of 1"); plain-English text for modem/network SMS error codes (111, 112 ...).

v48: one live SOS progress notification for My Circle AND chat rooms.
- Stays on top (ongoing, progress bar, pops up again on every step) until each contact has a delivery report and each
  chat room is confirmed by the Firestore SERVER (the old "SOS also posted to N chat rooms" Toast was shown before that).
- After 60 s (SosNotifier.DELIVERY_WAIT_MS) it stops waiting and shows an honest result: "SOS delivered", "SOS sent" (no delivery
  report from the carrier), "SOS partly delivered" or "SOS NOT delivered". A late report updates that result quietly.
- Each SOS has its own id, so a late report of an older SOS can never change the new one.

v51: "I am safe".
- Stop on the Home sharing card asks "Are you safe now?". "Yes, I'm safe" stops sharing AND sends the safe message to
  My Circle (SMS) and the chat rooms, with the same progress notification as the SOS. "Stop sharing only" just stops.
- The sharing notification has two buttons: I'M SAFE and STOP SHARING (both need unlock unless allowed in Profile).
- Chat members get the safe message even if they muted the room (they got the SOS through mute too).
- Removed toasts that only repeated what is already visible (Accident/First Aid screen lifecycle toast, Siren ON/OFF,
  Welcome, "added to your circle", photo saved, test-button toasts, hospital navigation toasts).

v56: chat with an available blood donor (the donor must accept first).
- Donors list: every available donor has a Chat button (next to Call). The requester writes a first message.
- Donor: gets a notification, sees "Blood requests" (Accept / Decline) on top of the Chat rooms screen.
  Accept opens the chat. Decline tells the requester.
- Same chat screen, seen-by, mute, notifications as normal rooms. A blood chat has no join code.
- FIRESTORE RULES MUST BE UPDATED: paste FIRESTORE_RULES_FULL.rules into Firebase console > Firestore > Rules > Publish.
