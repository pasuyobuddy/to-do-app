// Runs after "npx cap add android" (GitHub Actions / Codemagic).
// 1) lets the app ring at the exact minute, 2) copies the alarm sound into the Android app,
// 3) adds the AdMob App ID to the Android manifest.
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

const raw = path.join('android', 'app', 'src', 'main', 'res', 'raw');
fs.mkdirSync(raw, { recursive: true });
fs.copyFileSync(path.join('assets', 'alarm.wav'), path.join(raw, 'alarm.wav'));
console.log('Android setup done: permissions added, AdMob app ID added, alarm sound copied.');
