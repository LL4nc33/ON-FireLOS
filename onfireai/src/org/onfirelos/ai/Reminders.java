/*
 * Copyright (C) 2026 ON-FireLOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onfirelos.ai;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Remind me at six to call mum": kept by ON-FireAI itself, woken by the alarm manager at the
 * time, shown as a notification and said aloud after a soft chime. They outlast a restart.
 */
final class Reminders {

    private static final String CHANNEL = "reminders";
    static final String EXTRA_ID = "id";

    private Reminders() {
    }

    // ---------------------------------------------------------------- tools

    static Tools.Result set(Context context, JSONObject args) {
        final String text = args.optString("text").trim();
        if (text.isEmpty()) {
            return new Tools.Result("error: what should I remind of?", null);
        }
        final long now = System.currentTimeMillis();
        long at = -1;
        if (args.has("in_minutes")) {
            at = now + Math.max(1, args.optInt("in_minutes", 0)) * 60_000L;
        } else if (!args.optString("at").isEmpty()) {
            at = parseWhen(args.optString("at"), now);
        }
        if (at <= now) {
            return new Tools.Result("error: give a time in the future (at as YYYY-MM-DDTHH:MM,"
                    + " or in_minutes)", null);
        }
        final long id = Store.get(context).addReminder(text, at);
        schedule(context, id, at);
        return new Tools.Result("reminder " + id + " set for " + describe(at, now),
                context.getString(R.string.chip_reminder, shortTime(context, at, now)));
    }

    static Tools.Result list(Context context) {
        final long now = System.currentTimeMillis();
        final List<Store.Reminder> pending = Store.get(context).reminders(true);
        if (pending.isEmpty()) {
            return new Tools.Result("no reminders", null);
        }
        final StringBuilder out = new StringBuilder();
        for (Store.Reminder r : pending) {
            out.append("[").append(r.id).append("] ").append(describe(r.at, now)).append(": ")
                    .append(r.text).append('\n');
        }
        return new Tools.Result(out.toString(), context.getString(R.string.chip_reminders));
    }

    static Tools.Result cancel(Context context, long id) {
        final Store.Reminder r = Store.get(context).reminder(id);
        if (r == null) {
            return new Tools.Result("error: no reminder " + id + "; list_reminders first", null);
        }
        context.getSystemService(AlarmManager.class).cancel(pending(context, id));
        Store.get(context).deleteReminder(id);
        return new Tools.Result("reminder " + id + " deleted",
                context.getString(R.string.chip_reminder_gone));
    }

    // ---------------------------------------------------------------- the clock

    private static PendingIntent pending(Context context, long id) {
        return PendingIntent.getBroadcast(context, (int) id,
                new Intent(context, ReminderReceiver.class).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context context, long id, long at) {
        context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, pending(context, id));
    }

    /** After a start: the alarms are gone, the reminders not; one missed meanwhile comes now. */
    static void rescheduleAll(Context context) {
        final long now = System.currentTimeMillis();
        for (Store.Reminder r : Store.get(context).reminders(true)) {
            schedule(context, r.id, Math.max(r.at, now + 5000));
        }
    }

    /** The time has come: a notification, and said aloud unless do not disturb is on. */
    static void fire(Context context, long id) {
        final Store.Reminder r = Store.get(context).reminder(id);
        // Gone, or said already (an alarm left over from before): nothing to say.
        if (r == null || r.fired) {
            return;
        }
        Store.get(context).markFired(id);
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.reminders_channel),
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(channel);
        final Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_spark)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(r.text)
                .setStyle(new Notification.BigTextStyle().bigText(r.text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setColor(context.getColor(R.color.accent))
                .setAutoCancel(true)
                .build();
        nm.notify("reminder", (int) id, n);
        if (nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL
                && Prefs.flag(context, Prefs.SPEAK)) {
            Session.get(context).announce(context.getString(R.string.reminder_say, r.text));
        }
    }

    // ---------------------------------------------------------------- times

    /**
     * "2026-11-09T18:00" (seconds and a space instead of the T are fine too), or just "18:00",
     * which is the next 18:00. -1 when it cannot be read.
     */
    static long parseWhen(String when, long now) {
        final String w = when.trim().replace(' ', 'T');
        for (String pattern : new String[] { "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm" }) {
            try {
                final SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
                f.setLenient(false);
                return f.parse(w.length() > 19 ? w.substring(0, 19) : w).getTime();
            } catch (ParseException ignored) {
            }
        }
        if (w.matches("\\d{1,2}:\\d{2}")) {
            final String[] hm = w.split(":");
            final Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= now) {
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
            return c.getTimeInMillis();
        }
        return -1;
    }

    /** For the model: "Mon 2026-11-09 18:00 (in 2 h 5 min)". */
    static String describe(long at, long now) {
        final String when = new SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.ENGLISH)
                .format(new Date(at));
        final long minutes = Math.max(0, (at - now) / 60_000);
        return when + (minutes < 24 * 60 ? " (in " + (minutes >= 60 ? minutes / 60 + " h "
                : "") + minutes % 60 + " min)" : "");
    }

    /** For a chip: "18:00" today, "Tue 18:00" later. */
    static String shortTime(Context context, long at, long now) {
        final Calendar a = Calendar.getInstance();
        a.setTimeInMillis(at);
        final Calendar n = Calendar.getInstance();
        n.setTimeInMillis(now);
        final boolean today = a.get(Calendar.YEAR) == n.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
        final String time = android.text.format.DateFormat.getTimeFormat(context)
                .format(new Date(at));
        return today ? time : new SimpleDateFormat("EEE", Locale.getDefault())
                .format(new Date(at)) + " " + time;
    }
}
