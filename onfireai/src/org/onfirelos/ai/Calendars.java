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

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.CalendarContract;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The tablet's calendar: what is coming up, and new appointments. Read from every calendar
 * there is (accounts synced by other apps too); new ones go into the first writable one, or
 * into a calendar "Tablet" kept on the device that ON-FireAI makes the first time.
 */
final class Calendars {

    private static final String TAG = "ONFireAI";
    private static final String LOCAL = "ON-FireLOS";

    private Calendars() {
    }

    /** As the system's assistant, ON-FireAI may give itself the calendar. */
    private static boolean allowed(Context context) {
        for (String p : new String[] { Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR }) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                try {
                    context.getPackageManager().grantRuntimePermission(context.getPackageName(),
                            p, Process.myUserHandle());
                } catch (RuntimeException e) {
                    Log.w(TAG, "calendar: " + e);
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- reading

    static Tools.Result events(Context context, JSONObject args) {
        if (!allowed(context)) {
            return new Tools.Result("error: no access to the calendar", null);
        }
        final Calendar from = Calendar.getInstance();
        final String date = args.optString("date");
        if (date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            final String[] d = date.split("-");
            from.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, Integer.parseInt(d[2]),
                    0, 0, 0);
        } else {
            from.set(Calendar.HOUR_OF_DAY, 0);
            from.set(Calendar.MINUTE, 0);
            from.set(Calendar.SECOND, 0);
        }
        from.set(Calendar.MILLISECOND, 0);
        final int days = Math.max(1, Math.min(31, args.optInt("days", 1)));
        final long begin = from.getTimeInMillis();
        final long end = begin + days * 24L * 3600 * 1000;
        final String list = describe(context, begin, end, 30);
        return new Tools.Result(list.isEmpty() ? "no appointments in that time" : list,
                context.getString(R.string.chip_calendar));
    }

    /** The next appointment within a few hours, for the context; "" when there is none. */
    static String next(Context context, long hours) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        final long now = System.currentTimeMillis();
        final String list = describe(context, now, now + hours * 3600 * 1000, 1);
        return list.trim();
    }

    private static String describe(Context context, long begin, long end, int max) {
        final Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, begin);
        ContentUris.appendId(uri, end);
        final StringBuilder out = new StringBuilder();
        final SimpleDateFormat day = new SimpleDateFormat("EEE yyyy-MM-dd", Locale.ENGLISH);
        final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ROOT);
        try (Cursor c = context.getContentResolver().query(uri.build(), new String[] {
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION }, null, null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < max) {
                final boolean allDay = c.getInt(3) != 0;
                final Date start = new Date(c.getLong(1));
                out.append(day.format(start)).append(' ');
                if (allDay) {
                    out.append("all day");
                } else {
                    out.append(time.format(start)).append('-')
                            .append(time.format(new Date(c.getLong(2))));
                }
                out.append(": ").append(c.getString(0) == null ? "(no title)" : c.getString(0));
                final String where = c.getString(4);
                if (where != null && !where.isEmpty()) {
                    out.append(" @ ").append(where);
                }
                out.append('\n');
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "calendar", e);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- writing

    static Tools.Result add(Context context, JSONObject args) {
        if (!allowed(context)) {
            return new Tools.Result("error: no access to the calendar", null);
        }
        final String title = args.optString("title").trim();
        final String start = args.optString("start").trim();
        if (title.isEmpty() || start.isEmpty()) {
            return new Tools.Result("error: title and start are needed", null);
        }
        final long now = System.currentTimeMillis();
        final boolean allDay = start.matches("\\d{4}-\\d{2}-\\d{2}");
        long begin;
        long end;
        if (allDay) {
            begin = Reminders.parseWhen(start + "T00:00", now);
            end = begin + 24L * 3600 * 1000;
        } else {
            begin = Reminders.parseWhen(start, now);
            final String until = args.optString("end").trim();
            end = until.isEmpty() ? begin + Math.max(5, args.optInt("minutes", 60)) * 60_000L
                    : Reminders.parseWhen(until, now);
        }
        if (begin < 0 || end <= begin) {
            return new Tools.Result("error: start as YYYY-MM-DDTHH:MM (or YYYY-MM-DD for all"
                    + " day), end after it", null);
        }
        final long calendar = writableCalendar(context);
        if (calendar < 0) {
            return new Tools.Result("error: no calendar to write into", null);
        }
        final ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calendar);
        v.put(CalendarContract.Events.TITLE, title);
        if (allDay) {
            // All-day events are kept in UTC midnight to midnight.
            final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            final String[] d = start.split("-");
            utc.clear();
            utc.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, Integer.parseInt(d[2]));
            v.put(CalendarContract.Events.DTSTART, utc.getTimeInMillis());
            v.put(CalendarContract.Events.DTEND, utc.getTimeInMillis() + 24L * 3600 * 1000);
            v.put(CalendarContract.Events.ALL_DAY, 1);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        } else {
            v.put(CalendarContract.Events.DTSTART, begin);
            v.put(CalendarContract.Events.DTEND, end);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        }
        final String where = args.optString("location").trim();
        if (!where.isEmpty()) {
            v.put(CalendarContract.Events.EVENT_LOCATION, where);
        }
        final Uri row = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
        if (row == null) {
            return new Tools.Result("error: the calendar did not take it", null);
        }
        return new Tools.Result("added: " + title + " " + Reminders.describe(begin, now),
                context.getString(R.string.chip_event, Reminders.shortTime(context, begin, now)));
    }

    /** The first calendar one may write into; else the tablet's own, made now. */
    private static long writableCalendar(Context context) {
        final ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI,
                new String[] { CalendarContract.Calendars._ID },
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ? AND "
                        + CalendarContract.Calendars.VISIBLE + " = 1",
                new String[] { String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) },
                CalendarContract.Calendars._ID + " ASC")) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        }
        final Uri uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        final ContentValues v = new ContentValues();
        v.put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL);
        v.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        v.put(CalendarContract.Calendars.NAME, LOCAL);
        v.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                context.getString(R.string.calendar_local));
        v.put(CalendarContract.Calendars.CALENDAR_COLOR, context.getColor(R.color.glow_green));
        v.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER);
        v.put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL);
        v.put(CalendarContract.Calendars.VISIBLE, 1);
        v.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        v.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
        final Uri made = cr.insert(uri, v);
        return made == null ? -1 : ContentUris.parseId(made);
    }
}
