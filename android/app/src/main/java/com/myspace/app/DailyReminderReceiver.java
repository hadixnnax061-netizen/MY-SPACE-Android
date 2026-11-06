package com.myspace.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DailyReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DailyReminderScheduler.ACTION_DAILY.equals(intent.getAction())) return;
        if (!DailyReminderScheduler.isEnabled(context)) return;
        DailyReminderScheduler.showNotification(context);
        DailyReminderScheduler.scheduleNext(context);
    }
}
