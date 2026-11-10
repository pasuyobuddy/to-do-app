package com.pasuyolabs.todoalarm;

// To-Do Alarm: native alarms (version 1.3.3)
// Rings like the phone's Clock app:
//  - schedules with AlarmManager.setAlarmClock() (not held back while the phone sleeps)
//  - plays on the ALARM volume, repeats until stopped, vibrates
//  - shows a full-screen alarm over the lock screen with Stop and Snooze
//  - puts alarms back after the phone restarts or the app is updated
// The web part of the app talks to this through the "TaskAlarm" plugin.

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class TaskAlarm {
    static final String PREFS = "task_alarms";
    static final String CHANNEL = "task_alarm_ring_v1";
    static final String TAG = "taskalarm";
    static final String EXTRA_ID = "id";
    static final String EXTRA_TITLE = "title";
    static final long SNOOZE_MS = 5 * 60 * 1000L;
    static final long RING_FOR_MS = 10 * 60 * 1000L;   // stop ringing by itself after 10 minutes

    // ---------- scheduling ----------

    static PendingIntent firePi(Context c, int id, String title) {
        Intent i = new Intent(c, Fire.class);
        i.setAction("com.pasuyolabs.todoalarm.FIRE." + id);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_TITLE, title);
        return PendingIntent.getBroadcast(c, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void set(Context c, int id, String title, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = firePi(c, id, title);
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent show = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canExact(am);
        try {
            if (exact) am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("at", at);
            o.put("title", title);
            prefs(c).edit().putString(String.valueOf(id), o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static boolean canExact(AlarmManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return am.canScheduleExactAlarms();
        return true;
    }

    static void unset(Context c, int id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = firePi(c, id, "");
        am.cancel(pi);
        pi.cancel();
        prefs(c).edit().remove(String.valueOf(id)).apply();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- ringing ----------

    static Uri soundUri(Context c) {
        int res = c.getResources().getIdentifier("alarm", "raw", c.getPackageName());
        if (res == 0) return Settings.System.DEFAULT_ALARM_ALERT_URI;
        return Uri.parse("android.resource://" + c.getPackageName() + "/" + res);
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rings when a task is due");
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(soundUri(c), aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 700, 500, 700, 500});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    static PendingIntent buttonPi(Context c, int id, String title, String what) {
        Intent i = new Intent(c, Buttons.class);
        i.setAction("com.pasuyolabs.todoalarm." + what + "." + id);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_TITLE, title);
        return PendingIntent.getBroadcast(c, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    static void ring(Context c, int id, String title) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent full = new Intent(c, Ring.class);
        full.putExtra(EXTRA_ID, id);
        full.putExtra(EXTRA_TITLE, title);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, id, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(c, CHANNEL);
        else b = new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Time to do this")
                .setContentText(title)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(new Notification.Action.Builder((Icon) null, "Stop",
                        buttonPi(c, id, title, "STOP")).build())
                .addAction(new Notification.Action.Builder((Icon) null, "Snooze 5 min",
                        buttonPi(c, id, title, "SNOOZE")).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setTimeoutAfter(RING_FOR_MS);
        } else {
            b.setPriority(Notification.PRIORITY_MAX);
            b.setSound(soundUri(c), AudioManager.STREAM_ALARM);
            b.setVibrate(new long[]{0, 700, 500, 700, 500});
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;   // keep repeating the sound until stopped

        wakeScreen(c);
        try {
            nm.notify(TAG, id, n);
        } catch (SecurityException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    static void wakeScreen(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "todoalarm:ring");
            wl.acquire(10 * 1000L);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(TAG, id);
        Ring r = Ring.current;
        if (r != null && r.id == id) r.finish();
    }

    static void stopAll(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (TAG.equals(s.getTag())) nm.cancel(TAG, s.getId());
            }
        } catch (Exception ignored) {
        }
        Ring r = Ring.current;
        if (r != null) r.finish();
    }

    static void snooze(Context c, int id, String title) {
        stop(c, id);
        int sid = id < 0 ? id : -id;   // snoozes use negative ids so the app's own updates never cancel them
        set(c, sid, title, System.currentTimeMillis() + SNOOZE_MS);
    }

    // ---------- receivers ----------

    /** The alarm time arrived. */
    public static class Fire extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int id = i.getIntExtra(EXTRA_ID, 0);
            String title = i.getStringExtra(EXTRA_TITLE);
            prefs(c).edit().remove(String.valueOf(id)).apply();
            ring(c, id, title == null || title.isEmpty() ? "Your task" : title);
        }
    }

    /** Stop / Snooze buttons on the notification. */
    public static class Buttons extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int id = i.getIntExtra(EXTRA_ID, 0);
            String title = i.getStringExtra(EXTRA_TITLE);
            String action = i.getAction() == null ? "" : i.getAction();
            if (action.contains(".SNOOZE.")) snooze(c, id, title == null ? "Your task" : title);
            else stop(c, id);
        }
    }

    /** Puts saved alarms back after a restart, an app update or a clock change. */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            long now = System.currentTimeMillis();
            Map<String, ?> all = prefs(c).getAll();
            List<String> keys = new ArrayList<>(all.keySet());
            for (String k : keys) {
                try {
                    JSONObject o = new JSONObject(String.valueOf(all.get(k)));
                    long at = o.optLong("at", 0);
                    String title = o.optString("title", "Your task");
                    int id = Integer.parseInt(k);
                    if (at > now - 60 * 1000L) set(c, id, title, Math.max(at, now + 5000L));
                    else prefs(c).edit().remove(k).apply();
                } catch (Exception e) {
                    prefs(c).edit().remove(k).apply();
                }
            }
        }
    }

    // ---------- full-screen alarm ----------

    public static class Ring extends Activity {
        static Ring current;
        int id;
        String title;
        TextView whatView;
        TextView timeView;

        @Override
        @SuppressWarnings("deprecation")
        protected void onCreate(Bundle saved) {
            super.onCreate(saved);
            current = this;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            buildUi();
            read(getIntent());
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            setIntent(intent);
            read(intent);
        }

        @Override
        protected void onDestroy() {
            if (current == this) current = null;
            super.onDestroy();
        }

        void read(Intent i) {
            id = i.getIntExtra(EXTRA_ID, 0);
            title = i.getStringExtra(EXTRA_TITLE);
            if (title == null || title.isEmpty()) title = "Your task";
            whatView.setText(title);
            timeView.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
        }

        int dp(int v) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
        }

        Button button(String text, int bg, int fg) {
            Button b = new Button(this);
            b.setText(text);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(fg);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(bg);
            shape.setCornerRadius(dp(14));
            b.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
            lp.topMargin = dp(14);
            b.setLayoutParams(lp);
            return b;
        }

        void buildUi() {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(Color.parseColor("#0E1626"));
            root.setPadding(dp(28), dp(48), dp(28), dp(48));

            timeView = new TextView(this);
            timeView.setTextColor(Color.WHITE);
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
            timeView.setTypeface(Typeface.DEFAULT_BOLD);
            timeView.setGravity(Gravity.CENTER);

            TextView label = new TextView(this);
            label.setText("Time to do this");
            label.setTextColor(Color.parseColor("#9BA8BD"));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(24), 0, dp(6));

            whatView = new TextView(this);
            whatView.setTextColor(Color.WHITE);
            whatView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            whatView.setTypeface(Typeface.DEFAULT_BOLD);
            whatView.setGravity(Gravity.CENTER);
            whatView.setPadding(0, 0, 0, dp(36));

            Button stopB = button("Stop alarm", Color.parseColor("#FFB81F"), Color.parseColor("#2B1D00"));
            stopB.setOnClickListener(v -> {
                stop(this, id);
                finish();
            });
            Button snoozeB = button("Snooze 5 minutes", Color.parseColor("#18233A"), Color.WHITE);
            snoozeB.setOnClickListener(v -> {
                snooze(this, id, title);
                finish();
            });

            root.addView(timeView);
            root.addView(label);
            root.addView(whatView);
            root.addView(stopB);
            root.addView(snoozeB);
            setContentView(root);
        }
    }

    // ---------- bridge to the app's screens ----------

    @CapacitorPlugin(name = "TaskAlarm")
    public static class JsPlugin extends Plugin {

        @PluginMethod
        public void schedule(PluginCall call) {
            JSArray list = call.getArray("alarms");
            if (list == null) {
                call.reject("alarms is missing");
                return;
            }
            try {
                for (int k = 0; k < list.length(); k++) {
                    JSONObject o = list.getJSONObject(k);
                    set(getContext(), o.getInt("id"), o.optString("title", "Your task"), o.getLong("at"));
                }
                call.resolve();
            } catch (Exception e) {
                call.reject("Could not set the alarm: " + e.getMessage());
            }
        }

        @PluginMethod
        public void cancel(PluginCall call) {
            JSArray ids = call.getArray("ids");
            if (ids == null) {
                call.resolve();
                return;
            }
            try {
                for (int k = 0; k < ids.length(); k++) unset(getContext(), ids.getInt(k));
                call.resolve();
            } catch (Exception e) {
                call.reject("Could not cancel the alarm: " + e.getMessage());
            }
        }

        @PluginMethod
        public void stopAll(PluginCall call) {
            TaskAlarm.stopAll(getContext());
            call.resolve();
        }

        @PluginMethod
        public void status(PluginCall call) {
            Context c = getContext();
            JSObject r = new JSObject();
            boolean battery = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                battery = pm.isIgnoringBatteryOptimizations(c.getPackageName());
            }
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean fullScreen = true;
            if (Build.VERSION.SDK_INT >= 34) fullScreen = nm.canUseFullScreenIntent();
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            r.put("battery", battery);
            r.put("fullScreen", fullScreen);
            r.put("exact", canExact(am));
            r.put("notifications", nm.areNotificationsEnabled());
            r.put("sdk", Build.VERSION.SDK_INT);
            call.resolve(r);
        }

        void open(Intent i) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(i);
            } catch (Exception e) {
                Intent app = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getContext().getPackageName()));
                app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(app);
                } catch (Exception ignored) {
                }
            }
        }

        @PluginMethod
        public void openBattery(PluginCall call) {
            // Android 12+: the app's own settings page has "Battery -> Unrestricted".
            // Older Android: the "battery optimization" list, where the app can be set to "Don't optimize".
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getContext().getPackageName())));
            } else {
                open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
            call.resolve();
        }

        @PluginMethod
        public void openAppSettings(PluginCall call) {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName())));
            call.resolve();
        }

        @PluginMethod
        public void openFullScreen(PluginCall call) {
            if (Build.VERSION.SDK_INT >= 34) {
                open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getContext().getPackageName())));
            } else {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getContext().getPackageName())));
            }
            call.resolve();
        }
    }
}
