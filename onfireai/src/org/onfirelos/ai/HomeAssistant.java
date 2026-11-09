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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Home Assistant, straight through its REST API with a long-lived token: what the house is
 * like (lights, windows, temperatures) and doing things in it. Doors, locks, alarm systems
 * and garage doors ask first, and not at the lock screen at all.
 */
final class HomeAssistant {

    /**
     * What may be switched without asking, also at the lock screen: light, sound, warmth,
     * air. Everything else (switches, scripts, scenes, locks, doors, alarms - a garage
     * opener is often just a switch) is asked first, and not done at the lock screen.
     */
    static final Set<String> FREE = new HashSet<>(Arrays.asList(
            "light", "media_player", "climate", "fan"));
    /** What may be looked at from the lock screen; not who is home, nor doors and locks. */
    private static final Set<String> FREE_TO_SEE = new HashSet<>(Arrays.asList(
            "light", "media_player", "climate", "fan", "sensor", "weather"));

    /** A call as Home Assistant will take it: its domain, service and the things it hits. */
    static final class Target {
        String domain = "";
        String service = "";
        /** The entity ids (one, several, or "all"); empty when the call names none. */
        final java.util.List<String> entities = new java.util.ArrayList<>();
        /** Hits whole areas or devices instead of named things. */
        boolean wide;

        /** Light, sound, warmth or air, and nothing else hidden in the call. */
        boolean free() {
            if (!FREE.contains(domain) || wide || entities.isEmpty()) {
                return false;
            }
            for (String e : entities) {
                if (!e.startsWith(domain + ".")) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Reads a call the one way both the question and the call itself use. */
    static Target target(String arguments) {
        final Target t = new Target();
        try {
            final JSONObject a = new JSONObject(arguments);
            t.domain = a.optString("domain").trim().toLowerCase(Locale.ROOT);
            t.service = a.optString("service").trim().toLowerCase(Locale.ROOT);
            final JSONObject data = a.optJSONObject("data");
            final Object entity = data != null && data.has("entity_id")
                    ? data.opt("entity_id") : a.opt("entity_id");
            if (entity instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) entity).length(); i++) {
                    t.entities.add(((JSONArray) entity).optString(i).trim()
                            .toLowerCase(Locale.ROOT));
                }
            } else if (entity != null && entity != JSONObject.NULL) {
                for (String e : String.valueOf(entity).split(",")) {
                    if (!e.trim().isEmpty()) {
                        t.entities.add(e.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            t.wide = t.entities.contains("all") || (data != null
                    && (data.has("area_id") || data.has("device_id")));
        } catch (JSONException e) {
            t.wide = true;
        }
        return t;
    }

    private HomeAssistant() {
    }

    static boolean configured(Context context) {
        return !Prefs.string(context, Prefs.HA_URL).isEmpty()
                && !Prefs.string(context, Prefs.HA_TOKEN).isEmpty();
    }

    private static String base(Context context) {
        String b = Prefs.string(context, Prefs.HA_URL).trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String request(Context context, String method, String path, String body)
            throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(base(context) + path)
                .openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer "
                    + Prefs.string(context, Prefs.HA_TOKEN));
            c.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            final int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new LlmClient.ServerException(String.valueOf(code));
            }
            return LlmClient.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** For the settings: reachable, and how many things it knows. */
    static int count(Context context) throws IOException, JSONException {
        return new JSONArray(request(context, "GET", "/api/states", null)).length();
    }

    /**
     * The question before a guarded call, as one would ask it: "Haustür aufsperren?" - the
     * thing's own name from Home Assistant and the action in words.
     */
    static String question(Context context, String arguments) {
        final Target t = target(arguments);
        final String service = t.service;
        final StringBuilder names = new StringBuilder();
        for (String entity : t.entities) {
            String name = entity.contains(".") ? entity.substring(entity.indexOf('.') + 1)
                    .replace('_', ' ') : entity;
            try {
                final JSONObject state = new JSONObject(request(context, "GET",
                        "/api/states/" + android.net.Uri.encode(entity), null));
                final JSONObject attrs = state.optJSONObject("attributes");
                if (attrs != null && !attrs.optString("friendly_name").isEmpty()) {
                    name = attrs.optString("friendly_name");
                }
            } catch (IOException | JSONException ignored) {
            }
            names.append(names.length() == 0 ? "" : ", ").append(name);
        }
        if (t.wide || names.length() == 0) {
            names.append(names.length() == 0 ? "" : ", ").append(context.getString(
                    R.string.ha_everything));
        }
        final String name = names.toString();
        final int action;
        switch (service) {
            case "unlock": action = R.string.ha_unlock; break;
            case "lock": action = R.string.ha_lock; break;
            case "open_cover": action = R.string.ha_open; break;
            case "close_cover": action = R.string.ha_close; break;
            case "alarm_disarm": action = R.string.ha_disarm; break;
            case "turn_on": action = R.string.ha_on; break;
            case "turn_off": action = R.string.ha_off; break;
            case "toggle": action = R.string.ha_toggle; break;
            default:
                if (service.startsWith("alarm_arm")) {
                    action = R.string.ha_arm;
                } else {
                    return context.getString(R.string.confirm_home, name + ": "
                            + service.replace('_', ' '));
                }
        }
        return context.getString(action, name);
    }

    // ---------------------------------------------------------------- tools

    static Tools.Result states(Context context, JSONObject args) {
        if (!configured(context)) {
            return new Tools.Result("error: Home Assistant is not set up", null);
        }
        try {
            final JSONArray all = new JSONArray(request(context, "GET", "/api/states", null));
            final boolean locked = Tools.locked(context);
            final String domain = args.optString("domain").trim().toLowerCase(Locale.ROOT);
            final String[] words = args.optString("search").trim().toLowerCase(Locale.ROOT)
                    .split("\\s+");
            final StringBuilder out = new StringBuilder();
            int n = 0;
            for (int i = 0; i < all.length() && n < 40; i++) {
                final JSONObject e = all.getJSONObject(i);
                final String id = e.optString("entity_id");
                if (!domain.isEmpty() && !id.startsWith(domain + ".")) {
                    continue;
                }
                if (locked && !FREE_TO_SEE.contains(id.substring(0, Math.max(0,
                        id.indexOf('.'))))) {
                    continue;
                }
                final JSONObject attrs = e.optJSONObject("attributes");
                final String name = attrs == null ? "" : attrs.optString("friendly_name", "");
                final String hay = (id + " " + name).toLowerCase(Locale.ROOT);
                boolean all_ = true;
                for (String w : words) {
                    if (!w.isEmpty() && !hay.contains(w)) {
                        all_ = false;
                        break;
                    }
                }
                if (!all_) {
                    continue;
                }
                out.append(id).append(" \"").append(name).append("\": ")
                        .append(e.optString("state"));
                final String unit = attrs == null ? "" : attrs.optString("unit_of_measurement");
                if (!unit.isEmpty()) {
                    out.append(' ').append(unit);
                }
                if (attrs != null && attrs.has("current_temperature")) {
                    out.append(" (now ").append(attrs.opt("current_temperature")).append(')');
                }
                if (attrs != null && attrs.has("brightness")) {
                    out.append(" (brightness ").append(Math.round(
                            attrs.optDouble("brightness") * 100 / 255)).append("%)");
                }
                out.append('\n');
                n++;
            }
            final String more = locked ? "(the tablet is locked: only lights, media, heating,"
                    + " fans and sensors show; for anything else it must be unlocked first)\n"
                    : "";
            return new Tools.Result(more + (n == 0 ? "nothing matches" : out.toString()),
                    context.getString(R.string.chip_home));
        } catch (IOException | JSONException e) {
            return new Tools.Result("error: Home Assistant: " + LlmClient.describe(context, e),
                    null);
        }
    }

    static Tools.Result call(Context context, JSONObject args) {
        if (!configured(context)) {
            return new Tools.Result("error: Home Assistant is not set up", null);
        }
        final Target t = target(args.toString());
        final String domain = t.domain;
        final String service = t.service;
        if (domain.isEmpty() || service.isEmpty()) {
            return new Tools.Result("error: domain and service are needed", null);
        }
        if (!domain.matches("[a-z0-9_]+") || !service.matches("[a-z0-9_]+")) {
            return new Tools.Result("error: no such service " + domain + "." + service, null);
        }
        if (!t.free() && Tools.locked(context)) {
            return new Tools.Result("error: the tablet is locked; at the lock screen only"
                    + " lights, media, heating and fans, each by its entity_id", null);
        }
        try {
            final JSONObject data = args.optJSONObject("data") != null
                    ? args.optJSONObject("data") : new JSONObject();
            // The very things the question named, in the form it read them.
            if (!t.entities.isEmpty()) {
                data.put("entity_id", t.entities.size() == 1 ? t.entities.get(0)
                        : new JSONArray(t.entities));
            }
            request(context, "POST", "/api/services/" + domain + "/" + service,
                    data.toString());
            return new Tools.Result("done: " + domain + "." + service + " " + data,
                    context.getString(R.string.chip_home_done));
        } catch (IOException | JSONException e) {
            return new Tools.Result("error: Home Assistant: " + LlmClient.describe(context, e),
                    null);
        }
    }
}
