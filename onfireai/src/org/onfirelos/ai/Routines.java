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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Routines: a phrase ("good morning") and what to do then, said as one does to a person
 * ("tell me the weather and my appointments, then play the radio"). The model carries the
 * instruction out with all its tools, so a routine is not a fixed list of steps. A routine
 * may also run on its own at a time of day, on chosen weekdays: the sheet comes up with it.
 */
final class Routines {

    private static final String KEY = "routines";
    static final String EXTRA_NAME = "routine";

    private Routines() {
    }

    static JSONArray all(Context context) {
        try {
            return new JSONArray(Prefs.get(context).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray routines) {
        Prefs.get(context).edit().putString(KEY, routines.toString()).apply();
    }

    private static String plain(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** The instruction of the routine that the words say, or null. */
    static String match(Context context, String said) {
        final String s = plain(said);
        if (s.isEmpty()) {
            return null;
        }
        final JSONArray routines = all(context);
        for (int i = 0; i < routines.length(); i++) {
            final JSONObject r = routines.optJSONObject(i);
            final JSONArray phrases = r == null ? null : r.optJSONArray("phrases");
            for (int k = 0; phrases != null && k < phrases.length(); k++) {
                if (s.equals(plain(phrases.optString(k)))) {
                    return framed(r);
                }
            }
        }
        return null;
    }

    /** What the model gets for a routine: its name and the instruction, to carry out now. */
    /** How a run at its time begins: no one may be there, so the agent does less. */
    static final String TIMED = "Run the timed routine \"";

    static String framed(JSONObject r) {
        return "Run the routine \"" + r.optString("name") + "\" now: "
                + r.optString("instruction");
    }

    // ---------------------------------------------------------------- tools

    private static final int MAX_TIMED = 20;

    static Tools.Result create(Context context, JSONObject args) {
        final String name = args.optString("name").trim();
        final String instruction = args.optString("instruction").trim();
        if (name.isEmpty() || instruction.isEmpty()) {
            return new Tools.Result("error: name and instruction are needed", null);
        }
        try {
            final JSONArray phrases = args.optJSONArray("phrases") != null
                    ? args.optJSONArray("phrases") : new JSONArray().put(name);
            final JSONObject r = new JSONObject().put("name", name).put("phrases", phrases)
                    .put("instruction", instruction);
            final String time = args.optString("time").trim();
            if (!time.isEmpty()) {
                final String[] hm = time.split(":");
                if (!time.matches("\\d{1,2}:\\d{2}") || Integer.parseInt(hm[0]) > 23
                        || Integer.parseInt(hm[1]) > 59) {
                    return new Tools.Result("error: time as HH:MM, 00:00 to 23:59", null);
                }
                final JSONArray days = args.optJSONArray("days") != null
                        ? args.optJSONArray("days") : new JSONArray();
                for (int i = 0; i < days.length(); i++) {
                    if (days.optInt(i) < 1 || days.optInt(i) > 7) {
                        return new Tools.Result("error: days are 1 (Sunday) to 7 (Saturday)",
                                null);
                    }
                }
                r.put("time", time);
                r.put("days", days);
            }
            final JSONArray routines = all(context);
            final JSONArray kept = new JSONArray();
            for (int i = 0; i < routines.length(); i++) {
                final JSONObject o = routines.optJSONObject(i);
                if (o != null && !o.optString("name").equalsIgnoreCase(name)) {
                    kept.put(o);
                }
            }
            kept.put(r);
            // Only the first MAX_TIMED get an alarm (see scheduleAll).
            if (r.has("time") && kept.length() > MAX_TIMED) {
                return new Tools.Result("error: at most " + MAX_TIMED + " routines; delete one"
                        + " first", null);
            }
            save(context, kept);
            scheduleAll(context);
            return new Tools.Result("routine \"" + name + "\" kept" + (r.has("time")
                    ? ", runs at " + time : ""), context.getString(R.string.chip_routine, name));
        } catch (JSONException e) {
            return new Tools.Result("error: " + e.getMessage(), null);
        }
    }

    static Tools.Result list(Context context) {
        final JSONArray routines = all(context);
        if (routines.length() == 0) {
            return new Tools.Result("no routines yet", null);
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < routines.length(); i++) {
            final JSONObject r = routines.optJSONObject(i);
            out.append('"').append(r.optString("name")).append("\" on ")
                    .append(r.optJSONArray("phrases")).append(": ")
                    .append(r.optString("instruction"));
            if (r.has("time")) {
                out.append(" (at ").append(r.optString("time")).append(", days ")
                        .append(r.optJSONArray("days")).append(')');
            }
            out.append('\n');
        }
        return new Tools.Result(out.toString(), context.getString(R.string.chip_routines));
    }

    static Tools.Result delete(Context context, String name) {
        final JSONArray routines = all(context);
        final JSONArray kept = new JSONArray();
        boolean gone = false;
        for (int i = 0; i < routines.length(); i++) {
            final JSONObject o = routines.optJSONObject(i);
            if (o != null && o.optString("name").equalsIgnoreCase(name.trim())) {
                gone = true;
            } else if (o != null) {
                kept.put(o);
            }
        }
        if (!gone) {
            return new Tools.Result("error: no routine " + name, null);
        }
        save(context, kept);
        scheduleAll(context);
        return new Tools.Result("routine deleted", context.getString(R.string.chip_routine_gone));
    }

    // ---------------------------------------------------------------- at a time of day

    private static PendingIntent pending(Context context, int index) {
        return PendingIntent.getBroadcast(context, 5000 + index,
                new Intent(context, RoutineReceiver.class).putExtra(EXTRA_NAME, index),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Every routine with a time gets its next run; the others none. */
    static void scheduleAll(Context context) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        final JSONArray routines = all(context);
        for (int i = 0; i < MAX_TIMED; i++) {
            alarms.cancel(pending(context, i));
        }
        for (int i = 0; i < routines.length() && i < MAX_TIMED; i++) {
            final JSONObject r = routines.optJSONObject(i);
            final long next = r == null ? -1 : next(r, System.currentTimeMillis());
            if (next > 0) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next,
                        pending(context, i));
            }
        }
    }

    /** The next time a routine runs, on one of its days (all when none are given). */
    static long next(JSONObject r, long now) {
        final String time = r.optString("time");
        if (!time.matches("\\d{1,2}:\\d{2}")) {
            return -1;
        }
        final String[] hm = time.split(":");
        final JSONArray days = r.optJSONArray("days");
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now && onDay(days, c.get(Calendar.DAY_OF_WEEK))) {
                return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return -1;
    }

    private static boolean onDay(JSONArray days, int day) {
        if (days == null || days.length() == 0) {
            return true;
        }
        for (int i = 0; i < days.length(); i++) {
            if (days.optInt(i) == day) {
                return true;
            }
        }
        return false;
    }

    /** Its time has come: the sheet comes up and carries it out; the next run is set. */
    static void run(Context context, int index) {
        final JSONObject r = all(context).optJSONObject(index);
        scheduleAll(context);
        if (r == null) {
            return;
        }
        context.startActivity(new Intent().setClassName(context, context.getPackageName()
                + ".Ask").setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, TIMED + r.optString("name") + "\" now: "
                        + r.optString("instruction"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
