// Runs after "npx cap add android" (GitHub Actions / Codemagic).
// 1) lets the app ring at the exact minute, 2) copies the alarm sound into the Android app,
// 3) adds the AdMob App ID to the Android manifest, 4) sets the app version numbers, 5) sets the app icon.
const fs = require('fs');
const path = require('path');

const manifest = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifest, 'utf8');
const perms = [
  '<uses-permission android:name="android.permission.USE_EXACT_ALARM" />',
  '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />',
  '<uses-permission android:name="android.permission.VIBRATE" />'
].filter(p => !xml.includes(p.split('"')[1]));
if (perms.length) {
  xml = xml.replace('</manifest>', '    ' + perms.join('\n    ') + '\n</manifest>');
}

// 3) tells Google Mobile Ads which AdMob app this is (To-do Alarm App ID).
const ADMOB_APP_ID = 'ca-app-pub-1108774748467505~6287900616';
if (!xml.includes('com.google.android.gms.ads.APPLICATION_ID')) {
  xml = xml.replace('</application>',
    '    <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="' + ADMOB_APP_ID + '"/>\n    </application>');
}
fs.writeFileSync(manifest, xml);

// 5) app icon: copy our To-Do icon (all sizes) over Capacitor's default icon.
// app-icons.json holds every icon size (as text), so GitHub only needs one plain file.
const iconFile = 'app-icons.json';
if (!fs.existsSync(iconFile)) throw new Error('app-icons.json is missing: upload it to GitHub');
const icons = JSON.parse(fs.readFileSync(iconFile, 'utf8'));
const resDir = path.join('android', 'app', 'src', 'main', 'res');
for (const rel of Object.keys(icons)) {
  const dest = path.join(resDir, ...rel.split('/'));
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, Buffer.from(icons[rel], 'base64'));
}

const raw = path.join('android', 'app', 'src', 'main', 'res', 'raw');
fs.mkdirSync(raw, { recursive: true });
fs.copyFileSync(path.join('assets', 'alarm.wav'), path.join(raw, 'alarm.wav'));

// 4) version: Google Play needs a bigger versionCode on every upload.
//    The GitHub build passes its run number as VERSION_CODE, and the
//    version from package.json (e.g. 1.2.0) as VERSION_NAME.
const versionCode = parseInt(process.env.VERSION_CODE || '1', 10);
const versionName = process.env.VERSION_NAME || require('./package.json').version;
const gradleFile = path.join('android', 'app', 'build.gradle');
let gradle = fs.readFileSync(gradleFile, 'utf8');
if (!/versionCode \d+/.test(gradle) || !/versionName "[^"]*"/.test(gradle)) {
  throw new Error('Could not find versionCode/versionName in ' + gradleFile);
}
gradle = gradle.replace(/versionCode \d+/, 'versionCode ' + versionCode)
               .replace(/versionName "[^"]*"/, 'versionName "' + versionName + '"');
fs.writeFileSync(gradleFile, gradle);

// 6) Alarm-clock mode: schedule each alarm with AlarmManager.setAlarmClock(), the same
//    kind of alarm the phone's own Clock app uses. Phones (Nubia, Xiaomi, Samsung...) do not
//    hold these back while the phone sleeps. Patches the notification plugin before the build.
const lnFile = path.join('node_modules', '@capacitor', 'local-notifications', 'android', 'src', 'main',
  'kotlin', 'com', 'capacitorjs', 'plugins', 'localnotifications', 'LocalNotificationManager.kt');
let ln = fs.readFileSync(lnFile, 'utf8');
const oldCall = 'alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)';
if (!ln.includes('setAlarmClock(')) {
  if (!ln.includes(oldCall)) throw new Error('Alarm-clock patch: could not find the alarm call in ' + lnFile);
  const newCall = [
    'run {',
    '                    val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: android.content.Intent()',
    '                    val showPi = android.app.PendingIntent.getActivity(context, 0, openApp,',
    '                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)',
    '                    alarmManager.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(trigger, showPi), pendingIntent)',
    '                }'
  ].join('\n');
  ln = ln.replace(oldCall, newCall);
  fs.writeFileSync(lnFile, ln);
}

console.log('Android setup done: permissions, AdMob app ID, app icon, alarm sound, alarm-clock mode, version ' +
  versionName + ' (code ' + versionCode + ').');
