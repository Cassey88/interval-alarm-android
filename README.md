# Interval Alarm (Android)

Native Android version of the interval alarm — rings every 15 minutes (configurable)
between a From and To time (defaults 09:00–12:00), ticks each time off as it rings,
with a selectable sound. Because it uses Android's `AlarmManager` with exact wake-up
alarms, it fires **with the screen off, the app closed, and the phone in deep sleep** —
no need to keep anything open.

## Build the APK (no Android Studio needed)

1. Create a new GitHub repo (e.g. `Cassey88/interval-alarm-android`) and push this
   whole folder to it (the `.github/workflows/build.yml` file must be included).
2. On GitHub, open the repo → **Actions** tab. The "Build APK" workflow runs
   automatically on push (or press **Run workflow**).
3. When it finishes (about 3–4 minutes), open the run and download the
   **interval-alarm-apk** artifact. Unzip it to get `app-debug.apk`.

## Install on your phone

1. Copy `app-debug.apk` to your phone (or download the artifact directly on the phone).
2. Tap it to install. Allow "install from unknown sources" when prompted.
3. First run: allow **notifications**, and if asked, allow **Alarms & reminders**
   (Settings screen opens automatically on Android 12+).

## Use

- Set From / To / interval / sound, tap **Start alarms**, then close the app —
  the toast confirms how many alarms were set.
- Each alarm fires as a full-volume notification (alarm channel) with vibration.
- Reopen the app any time to see the schedule with ✓ ticks for rung times.
- **Stop alarms** cancels everything remaining.

## Notes

- Sound choice maps to your system's default Alarm / Ringtone / Notification sound.
  Android locks a notification channel's sound after first use, so if you want to
  change which system sound each option uses later, do it in
  Settings → Apps → Interval Alarm → Notifications.
- Alarms are scheduled for **today** only, matching the web version's behaviour.
  Press Start again the next day (or ask Claude to add daily repeat).
