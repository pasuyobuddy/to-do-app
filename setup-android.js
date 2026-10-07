// Runs on Codemagic after "npx cap add android".
// 1) lets the app ring at the exact minute, 2) copies the alarm sound into the Android app.
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
  fs.writeFileSync(manifest, xml);
}

const raw = path.join('android', 'app', 'src', 'main', 'res', 'raw');
fs.mkdirSync(raw, { recursive: true });
fs.copyFileSync(path.join('assets', 'alarm.wav'), path.join(raw, 'alarm.wav'));
console.log('Android setup done: permissions added, alarm sound copied.');
