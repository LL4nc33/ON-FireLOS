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
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * The weather from Open-Meteo, as the clock's weather has it: no key, no account. "Here" is
 * the city of the tablet's time zone unless the user names a place.
 */
final class Weather {

    private Weather() {
    }

    static Tools.Result forTool(Context context, String place, int days) {
        final String where = place == null || place.trim().isEmpty() ? Tools.zoneCity()
                : place.trim();
        try {
            final double[] point = new double[2];
            final String name = locate(context, where, point);
            if (name == null) {
                return new Tools.Result("error: place not found: " + where, null);
            }
            final int n = Math.max(1, Math.min(7, days <= 0 ? 3 : days));
            final JSONObject json = new JSONObject(get(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,apparent_temperature,weather_code,"
                            + "wind_speed_10m,relative_humidity_2m"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                            + "precipitation_probability_max,precipitation_sum,sunrise,sunset"
                            + "&timezone=auto&forecast_days=%d", point[0], point[1], n)));
            final StringBuilder out = new StringBuilder(name).append(": ");
            final JSONObject now = json.optJSONObject("current");
            if (now != null) {
                out.append("now ").append(round(now.optDouble("temperature_2m"))).append("°C (feels ")
                        .append(round(now.optDouble("apparent_temperature"))).append("°C), ")
                        .append(describe(now.optInt("weather_code"))).append(", wind ")
                        .append(round(now.optDouble("wind_speed_10m"))).append(" km/h, humidity ")
                        .append(now.optInt("relative_humidity_2m")).append("%.\n");
            }
            final JSONObject daily = json.optJSONObject("daily");
            if (daily != null) {
                final JSONArray dates = daily.optJSONArray("time");
                for (int i = 0; dates != null && i < dates.length(); i++) {
                    out.append(dates.optString(i)).append(i == 0 ? " (today)" : i == 1
                            ? " (tomorrow)" : "").append(": ")
                            .append(describe(daily.optJSONArray("weather_code").optInt(i)))
                            .append(", ")
                            .append(round(daily.optJSONArray("temperature_2m_min").optDouble(i)))
                            .append(" to ")
                            .append(round(daily.optJSONArray("temperature_2m_max").optDouble(i)))
                            .append("°C, rain ")
                            .append(daily.optJSONArray("precipitation_probability_max").optInt(i))
                            .append("% (")
                            .append(daily.optJSONArray("precipitation_sum").optDouble(i))
                            .append(" mm)");
                    final String sunset = daily.optJSONArray("sunset").optString(i, "");
                    if (i == 0 && sunset.length() >= 16) {
                        out.append(", sunset ").append(sunset.substring(11, 16));
                    }
                    out.append('\n');
                }
            }
            return new Tools.Result(out.toString(), context.getString(R.string.chip_weather, name));
        } catch (Exception e) {
            return new Tools.Result("error: weather unavailable (" + e.getMessage() + ")", null);
        }
    }

    private static long round(double d) {
        return Math.round(d);
    }

    /** Coordinates of a place, remembered once found. Returns its name or null. */
    private static String locate(Context context, String place, double[] point) throws Exception {
        final SharedPreferences cache = context.getSharedPreferences("geo", Context.MODE_PRIVATE);
        final String key = place.toLowerCase(Locale.ROOT);
        final String hit = cache.getString(key, null);
        if (hit != null) {
            final String[] parts = hit.split("\\|", 3);
            point[0] = Double.parseDouble(parts[0]);
            point[1] = Double.parseDouble(parts[1]);
            return parts[2];
        }
        final JSONObject json = new JSONObject(get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&language="
                        + Locale.getDefault().getLanguage() + "&name="
                        + URLEncoder.encode(place, "UTF-8")));
        final JSONArray results = json.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return null;
        }
        final JSONObject r = results.getJSONObject(0);
        point[0] = r.getDouble("latitude");
        point[1] = r.getDouble("longitude");
        final String name = r.optString("name", place);
        cache.edit().putString(key, point[0] + "|" + point[1] + "|" + name).apply();
        return name;
    }

    private static String get(String address) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            return LlmClient.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather codes in words, for the model to put into its own. */
    static String describe(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "overcast";
        if (code <= 48) return "fog";
        if (code <= 57) return "drizzle";
        if (code <= 67) return "rain";
        if (code <= 77) return "snow";
        if (code <= 82) return "rain showers";
        if (code <= 86) return "snow showers";
        return "thunderstorm";
    }
}
