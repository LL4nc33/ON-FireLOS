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

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Official weather warnings for a place, from the national weather service: GeoSphere
 * Austria in Austria, the DWD (through Bright Sky) in Germany. No key, no account; anywhere
 * else, and when a service cannot be reached, there are none to tell.
 */
final class Warnings {

    private static final String TAG = "ONFireAI";
    private static final String[] AT_TYPES = {"", "storm", "rain", "snow", "black ice",
            "thunderstorm", "heat", "cold"};
    private static final String[] AT_LEVELS = {"", "yellow", "orange", "red"};

    private Warnings() {
    }

    /** One line per warning in force or coming, "" for none. */
    static String at(double lat, double lon) {
        try {
            if (lat > 46.3 && lat < 49.1 && lon > 9.5 && lon < 17.2) {
                // The box holds Munich and Passau too: GeoSphere answers 404 there.
                try {
                    final String at = austria(lat, lon);
                    if (at != null) {
                        return at;
                    }
                } catch (Exception e) {
                    Log.i(TAG, "weather warnings: not in Austria (" + e.getMessage() + ")");
                }
            }
            if (lat > 47.2 && lat < 55.1 && lon > 5.8 && lon < 15.1) {
                return germany(lat, lon);
            }
        } catch (Exception e) {
            Log.w(TAG, "weather warnings: " + e.getMessage());
        }
        return "";
    }

    /** GeoSphere Austria; null when the point is not in Austria after all. */
    private static String austria(double lat, double lon) throws Exception {
        final JSONObject json = new JSONObject(get(String.format(Locale.ROOT,
                "https://warnungen.zamg.at/wsapp/api/getWarningsForCoords?lon=%.4f&lat=%.4f"
                        + "&lang=%s", lon, lat, Locale.getDefault().getLanguage())));
        final JSONObject props = json.optJSONObject("properties");
        if (props == null || props.optJSONObject("location") == null) {
            return null;
        }
        final JSONArray list = props.optJSONArray("warnings");
        final StringBuilder out = new StringBuilder();
        for (int i = 0; list != null && i < list.length(); i++) {
            final JSONObject w0 = list.optJSONObject(i);
            final JSONObject w = w0 == null ? null
                    : w0.optJSONObject("properties") != null ? w0.optJSONObject("properties")
                    : w0;
            if (w == null) {
                continue;
            }
            final int type = w.optInt("warntypid");
            final int level = w.optInt("warnstufeid");
            out.append("- ").append(level > 0 && level < AT_LEVELS.length ? AT_LEVELS[level]
                    : "").append(" warning: ").append(type > 0 && type < AT_TYPES.length
                    ? AT_TYPES[type] : "weather").append(when(w.optString("begin"),
                    w.optString("end")));
            final String text = w.optString("text").trim();
            if (!text.isEmpty()) {
                out.append(": ").append(text.length() > 200 ? text.substring(0, 200) + "…"
                        : text);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** The DWD's warnings for the place's district, through api.brightsky.dev. */
    private static String germany(double lat, double lon) throws Exception {
        final JSONObject json = new JSONObject(get(String.format(Locale.ROOT,
                "https://api.brightsky.dev/alerts?lat=%.4f&lon=%.4f", lat, lon)));
        final JSONArray list = json.optJSONArray("alerts");
        final boolean german = "de".equals(Locale.getDefault().getLanguage());
        final StringBuilder out = new StringBuilder();
        for (int i = 0; list != null && i < list.length(); i++) {
            final JSONObject a = list.optJSONObject(i);
            if (a == null) {
                continue;
            }
            out.append("- ").append(a.optString("severity")).append(": ")
                    .append(a.optString(german ? "headline_de" : "headline_en"))
                    .append(when(a.optString("onset"), a.optString("expires"))).append('\n');
        }
        return out.toString();
    }

    /** " (from … to …)" in local time, when the times can be read. */
    private static String when(String begin, String end) {
        final String b = time(begin);
        final String e = time(end);
        return b.isEmpty() && e.isEmpty() ? "" : " (" + (b.isEmpty() ? "" : "from " + b)
                + (e.isEmpty() ? "" : (b.isEmpty() ? "" : " ") + "until " + e) + ")";
    }

    /** Seconds since 1970, or an ISO time, as "Tue 14:00"; "" when it is neither. */
    private static String time(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        Date d = null;
        try {
            if (s.matches("\\d{9,11}")) {
                d = new Date(Long.parseLong(s) * 1000);
            } else if (s.matches("\\d{12,14}")) {
                d = new Date(Long.parseLong(s));
            } else {
                d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(s);
            }
        } catch (Exception e) {
            return "";
        }
        return new SimpleDateFormat("EEE HH:mm", Locale.ENGLISH).format(d);
    }

    private static String get(String address) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "ON-FireAI");
        try {
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            return LlmClient.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
