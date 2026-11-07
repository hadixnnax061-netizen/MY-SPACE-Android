package com.myspace.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Color;

import java.util.Calendar;

final class DailyReminderScheduler {
    static final String ACTION_DAILY = "com.myspace.app.action.DAILY_REFLECTION";
    private static final String PREFS = "daily_reflection_reminder";
    private static final String CHANNEL_ID = "daily_reflection_v1";
    private static final int ALARM_REQUEST = 17031;
    private static final int NOTIFICATION_ID = 17032;

    private DailyReminderScheduler() {}

    static void enable(Context context, int hour, int minute, String starName, String mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", true)
                .putInt("hour", hour).putInt("minute", minute)
                .putString("star_name", cleanName(starName))
                .putString("mode", safeMode(mode)).apply();
        scheduleNext(context);
    }

    static void disable(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", false).apply();
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(alarmPendingIntent(context));
        NotificationManager notifications = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
    }

    static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    static void scheduleNext(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return;
        int hour = Math.max(0, Math.min(23, prefs.getInt("hour", 20)));
        int minute = Math.max(0, Math.min(59, prefs.getInt("minute", 0)));
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            // Deliberately inexact: this gentle reminder is not a critical alarm.
            // Android may defer it under Doze or battery-saving restrictions.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    next.getTimeInMillis(), alarmPendingIntent(context));
        }
    }

    static void showNotification(Context context) {
        if (!isEnabled(context) || !canPost(context)) return;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        createChannel(context, manager);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = cleanName(prefs.getString("star_name", "نجمك"));
        String title = "همسة من " + (name.isEmpty() ? "نجمك" : name);
        String body = reminderText(prefs.getString("mode", "gentle"));

        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context).setPriority(Notification.PRIORITY_LOW);
        Notification notification = builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(Color.rgb(67, 213, 205))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static String reminderText(String mode) {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if ("quran".equals(mode)) {
            String[] verses = {
                    "﴿وَأَنْ لَيْسَ لِلْإِنْسَانِ إِلَّا مَا سَعَىٰ﴾\nالقرآن الكريم · سورة النجم، الآية 39",
                    "﴿وَاللَّهُ خَلَقَكُمْ وَمَا تَعْمَلُونَ﴾\nالقرآن الكريم · سورة الصافات، الآية 96"
            };
            return verses[day % verses.length];
        }
        if ("space".equals(mode)) {
            String[] facts = {
                    "تتكوّن النجوم داخل سحب جزيئية كبيرة من الغاز والغبار. — NASA Science",
                    "قد تنضغط كتلة تقارب كتلة الشمس في نجم نيوتروني قطره نحو 20 كم. — NASA Goddard"
            };
            return facts[day % facts.length];
        }
        return "إن رغبت، خذ دقيقة لتأمل خطوة صغيرة أو اكتشاف معلومة عن الفضاء.";
    }

    private static String safeMode(String mode) {
        return "quran".equals(mode) || "space".equals(mode) ? mode : "gentle";
    }

    private static void createChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "تذكير MY SPACE", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("تذكير يومي اختياري في الوقت الذي تختاره. يمكنك التحكم به من إعدادات Android.");
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent alarmPendingIntent(Context context) {
        Intent intent = new Intent(context, DailyReminderReceiver.class).setAction(ACTION_DAILY);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String cleanName(String input) {
        String value = input == null ? "" : input.replaceAll("[\\p{Cntrl}<>\\\"'`]+", "").trim();
        return value.length() > 40 ? value.substring(0, 40) : value;
    }
}
