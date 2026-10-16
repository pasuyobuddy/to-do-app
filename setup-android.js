// Runs after "npx cap add android" (GitHub Actions / Codemagic).
// 1) lets the app ring at the exact minute, 2) copies the alarm sound into the Android app,
// 3) adds the AdMob App ID to the Android manifest, 4) sets the app version numbers.
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

console.log('Android setup done: permissions, AdMob app ID, alarm sound, version ' +
  versionName + ' (code ' + versionCode + ').');
