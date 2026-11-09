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

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The clock's timers and alarms, through the interface ON-FireLOS gives the clock app
 * (org.onfirelos.deskclock.api, for apps signed like the system): how long is left, pause,
 * go on, a minute more, stop, delete; alarms on, off, gone.
 */
final class Clock {

    private static final Uri API = Uri.parse("content://org.onfirelos.deskclock.api");
    private static final String[] DAYS = { "", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

    private Clock() {
    }

    private static Bundle call(Context context, String method, String arg, String action) {
        final Bundle extras = new Bundle();
        extras.putString("action", action);
        try {
            final Bundle out = context.getContentResolver().call(API, method, arg, extras);
            return out == null ? error("no answer") : out;
        } catch (RuntimeException e) {
            return error("the clock app cannot be reached");
        }
    }

    private static Bundle error(String message) {
        final Bundle b = new Bundle();
        b.putString("error", message);
        return b;
    }

    // ---------------------------------------------------------------- timers

    static Tools.Result timers(Context context) {
        final Bundle out = call(context, "timers", null, "");
        if (out.getString("error") != null) {
            return new Tools.Result("error: " + out.getString("error"), null);
        }
        return new Tools.Result(describeTimers(out), context.getString(R.string.chip_timers),
                timersCard(out));
    }

    static Tools.Result timer(Context context, JSONObject args) {
        String id = args.optString("id", "").trim();
        final String action = args.optString("action");
        // By its name ("the pasta timer"), or the only one there is; all only when said.
        if (id.isEmpty() || !id.matches("\\d+|all")) {
            final String name = (args.optString("label", "") + " "
                    + (id.matches("\\d+|all") ? "" : id)).trim().toLowerCase(Locale.ROOT);
            final ArrayList<Bundle> list = call(context, "timers", null, "")
                    .getParcelableArrayList("timers");
            if (list == null || list.isEmpty()) {
                return new Tools.Result("error: no timers", null);
            }
            Bundle match = null;
            int matches = 0;
            for (Bundle t : list) {
                final String label = String.valueOf(t.getString("label", ""))
                        .toLowerCase(Locale.ROOT);
                if (name.isEmpty() ? list.size() == 1
                        : !label.isEmpty() && (label.contains(name) || name.contains(label))) {
                    match = t;
                    matches++;
                }
            }
            if (matches != 1) {
                return new Tools.Result("error: which timer? " + describeTimers(
                        call(context, "timers", null, "")), null);
            }
            id = String.valueOf(match.getInt("id"));
        }
        final Bundle out = call(context, "timer", id.isEmpty() ? "all" : id, action);
        if (out.getString("error") != null) {
            return new Tools.Result("error: " + out.getString("error"), null);
        }
        final int chip;
        switch (action) {
            case "pause": chip = R.string.chip_timer_paused; break;
            case "resume": chip = R.string.chip_timer_resumed; break;
            case "add_minute": chip = R.string.chip_timer_minute; break;
            default: chip = R.string.chip_timer_stopped;
        }
        return new Tools.Result("done; now:\n" + describeTimers(out), context.getString(chip),
                timersCard(out));
    }

    private static JSONObject timersCard(Bundle out) {
        final ArrayList<Bundle> list = out.getParcelableArrayList("timers");
        try {
            final org.json.JSONArray a = new org.json.JSONArray();
            for (int i = 0; list != null && i < list.size(); i++) {
                final Bundle t = list.get(i);
                a.put(new JSONObject().put("label", t.getString("label", ""))
                        .put("state", t.getString("state"))
                        .put("remaining", t.getLong("remaining"))
                        .put("end", System.currentTimeMillis() + t.getLong("remaining"))
                        .put("length", t.getLong("length")));
            }
            return a.length() == 0 ? null : new JSONObject().put("type", "timers")
                    .put("timers", a);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static JSONObject alarmsCard(Bundle out) {
        final ArrayList<Bundle> list = out.getParcelableArrayList("alarms");
        try {
            final org.json.JSONArray a = new org.json.JSONArray();
            for (int i = 0; list != null && i < list.size(); i++) {
                final Bundle b = list.get(i);
                final org.json.JSONArray days = new org.json.JSONArray();
                final ArrayList<Integer> d = b.getIntegerArrayList("days");
                for (int k = 0; d != null && k < d.size(); k++) {
                    days.put(d.get(k));
                }
                a.put(new JSONObject().put("hour", b.getInt("hour"))
                        .put("minute", b.getInt("minute")).put("on", b.getBoolean("on"))
                        .put("label", b.getString("label", "")).put("days", days));
            }
            return a.length() == 0 ? null : new JSONObject().put("type", "alarms")
                    .put("alarms", a);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** The clock's timers as it reports them (off the main thread). */
    static java.util.List<Bundle> timerList(Context context) {
        final ArrayList<Bundle> list = call(context, "timers", null, "")
                .getParcelableArrayList("timers");
        return list == null ? new ArrayList<>() : list;
    }

    static String clockText(long ms) {
        return clock(ms);
    }

    private static String describeTimers(Bundle out) {
        final ArrayList<Bundle> list = out.getParcelableArrayList("timers");
        if (list == null || list.isEmpty()) {
            return "no timers";
        }
        final StringBuilder s = new StringBuilder();
        for (Bundle t : list) {
            s.append('[').append(t.getInt("id")).append("] ");
            final String label = t.getString("label");
            if (label != null && !label.isEmpty()) {
                s.append('"').append(label).append("\" ");
            }
            s.append(t.getString("state")).append(", ")
                    .append(clock(t.getLong("remaining"))).append(" left of ")
                    .append(clock(t.getLong("length"))).append('\n');
        }
        return s.toString();
    }

    /** 125000 ms as "2:05", an hour and more as "1:02:05"; a timer that rang is below 0. */
    private static String clock(long ms) {
        final boolean over = ms < 0;
        final long s = Math.abs(ms) / 1000;
        final String t = s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        return over ? "-" + t + " (ringing)" : t;
    }

    // ---------------------------------------------------------------- alarms

    static Tools.Result alarms(Context context) {
        final Bundle out = call(context, "alarms", null, "");
        if (out.getString("error") != null) {
            return new Tools.Result("error: " + out.getString("error"), null);
        }
        return new Tools.Result(describeAlarms(out), context.getString(R.string.chip_alarms),
                alarmsCard(out));
    }

    static Tools.Result alarm(Context context, JSONObject args) {
        final String action = args.optString("action");
        final Bundle out = call(context, "alarm", String.valueOf(args.optLong("id", -1)),
                action);
        if (out.getString("error") != null) {
            return new Tools.Result("error: " + out.getString("error")
                    + "; list the alarms first", null);
        }
        final int chip = "delete".equals(action) ? R.string.chip_alarm_deleted
                : "on".equals(action) ? R.string.chip_alarm_on : R.string.chip_alarm_off;
        return new Tools.Result("done; now:\n" + describeAlarms(out), context.getString(chip),
                alarmsCard(out));
    }

    private static String describeAlarms(Bundle out) {
        final ArrayList<Bundle> list = out.getParcelableArrayList("alarms");
        if (list == null || list.isEmpty()) {
            return "no alarms";
        }
        final StringBuilder s = new StringBuilder();
        for (Bundle a : list) {
            s.append('[').append(a.getLong("id")).append("] ")
                    .append(String.format(Locale.ROOT, "%02d:%02d", a.getInt("hour"),
                            a.getInt("minute")))
                    .append(a.getBoolean("on") ? " on" : " off");
            final ArrayList<Integer> days = a.getIntegerArrayList("days");
            if (days != null && !days.isEmpty()) {
                s.append(", ");
                for (int i = 0; i < days.size(); i++) {
                    s.append(i > 0 ? " " : "").append(DAYS[days.get(i)]);
                }
            } else {
                s.append(", once");
            }
            final String label = a.getString("label");
            if (label != null && !label.isEmpty()) {
                s.append(" \"").append(label).append('"');
            }
            s.append('\n');
        }
        return s.toString();
    }
}
