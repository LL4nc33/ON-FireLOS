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

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Facts with a source of their own instead of a web search a small model misreads: exchange
 * rates (the European Central Bank's, through frankfurter.dev) and public holidays
 * (date.nager.at). Both free, without a key.
 */
final class Facts {

    private Facts() {
    }

    /** Words for currencies as they are said, and their codes. */
    private static final String[][] CURRENCIES = {{"kanadische", "CAD"}, {"canadian", "CAD"},
            {"australische", "AUD"}, {"australian", "AUD"}, {"hongkong", "HKD"},
            {"neuseeland", "NZD"}, {"singapur", "SGD"}, {"dollar", "USD"}, {"usd", "USD"},
            {"euro", "EUR"}, {"eur", "EUR"}, {"franken", "CHF"}, {"franc", "CHF"},
            {"chf", "CHF"}, {"pfund", "GBP"}, {"pound", "GBP"}, {"gbp", "GBP"},
            {"yen", "JPY"}, {"jpy", "JPY"}, {"forint", "HUF"}, {"huf", "HUF"},
            {"zloty", "PLN"}, {"złoty", "PLN"}, {"pln", "PLN"}, {"tschechische", "CZK"},
            {"czk", "CZK"}, {"schwedische", "SEK"}, {"sek", "SEK"}, {"norwegische", "NOK"},
            {"nok", "NOK"}, {"dänische", "DKK"}, {"dkk", "DKK"}, {"yuan", "CNY"},
            {"renminbi", "CNY"}, {"lira", "TRY"}, {"try", "TRY"}, {"rupie", "INR"},
            {"rupee", "INR"}};

    /** "Dollar", "usd", "US-Dollar" as "USD"; a three-letter code as it is. */
    static String code(String said) {
        final String s = said == null ? "" : said.trim().toLowerCase(Locale.ROOT);
        if (s.matches("[a-z]{3}")) {
            for (String[] c : CURRENCIES) {
                if (c[0].equals(s)) {
                    return c[1];
                }
            }
            return s.toUpperCase(Locale.ROOT);
        }
        for (String[] c : CURRENCIES) {
            if (s.contains(c[0])) {
                return c[1];
            }
        }
        return s.toUpperCase(Locale.ROOT);
    }

    static Tools.Result convert(double amount, String from, String to) {
        final String a = code(from);
        final String b = code(to == null || to.trim().isEmpty() ? "EUR" : to);
        if (a.equals(b)) {
            return new Tools.Result(Calc.plain(java.math.BigDecimal.valueOf(amount)) + " " + a,
                    null);
        }
        try {
            final JSONObject json = new JSONObject(get(String.format(Locale.ROOT,
                    "https://api.frankfurter.dev/v1/latest?amount=%s&from=%s&to=%s",
                    Calc.plain(java.math.BigDecimal.valueOf(amount <= 0 ? 1 : amount)), a, b)));
            final double result = json.getJSONObject("rates").getDouble(b);
            final double rate = result / (amount <= 0 ? 1 : amount);
            return new Tools.Result(String.format(Locale.ROOT, "%s %s = %.2f %s (rate of %s from"
                            + " the European Central Bank: 1 %s = %.4f %s). Say the result in"
                            + " digits, rounded sensibly.",
                    Calc.plain(java.math.BigDecimal.valueOf(amount)), a, result, b,
                    json.optString("date"), a, rate, b), null);
        } catch (Exception e) {
            return new Tools.Result("error: no rate for " + a + " to " + b + " right now ("
                    + e.getMessage() + "); say so, do not guess a rate", null);
        }
    }

    /** The country of the tablet: from its time zone first, as the weather does. */
    static String country() {
        final String zone = TimeZone.getDefault().getID();
        if (zone.equals("Europe/Vienna")) return "AT";
        if (zone.equals("Europe/Berlin") || zone.equals("Europe/Busingen")) return "DE";
        if (zone.equals("Europe/Zurich")) return "CH";
        if (zone.equals("Europe/London")) return "GB";
        final String c = Locale.getDefault().getCountry();
        return c.isEmpty() ? "AT" : c;
    }

    static Tools.Result holidays(String country, int count) {
        final String c = country == null || country.trim().length() != 2 ? country()
                : country.trim().toUpperCase(Locale.ROOT);
        final int n = Math.max(1, Math.min(10, count <= 0 ? 3 : count));
        try {
            final JSONArray list = new JSONArray(get(
                    "https://date.nager.at/api/v3/NextPublicHolidays/" + c));
            final boolean german = "de".equals(Locale.getDefault().getLanguage());
            final SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            final SimpleDateFormat out = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH);
            final String today = in.format(new Date());
            final StringBuilder b = new StringBuilder("Next public holidays in " + c
                    + " (today is " + out.format(new Date()) + "):\n");
            int shown = 0;
            for (int i = 0; i < list.length() && shown < n; i++) {
                final JSONObject h = list.getJSONObject(i);
                final String date = h.optString("date");
                if (date.compareTo(today) < 0) {
                    continue;
                }
                b.append("- ").append(out.format(in.parse(date))).append(": ")
                        .append(german ? h.optString("localName") : h.optString("name"))
                        .append(h.optBoolean("global", true) ? "" : " (only in some regions)")
                        .append(date.equals(today) ? " (today)" : "").append('\n');
                shown++;
            }
            return new Tools.Result(b.append("Say the name as written and the date.").toString(),
                    null);
        } catch (Exception e) {
            return new Tools.Result("error: holidays not available (" + e.getMessage() + ")",
                    null);
        }
    }

    private static String get(String address) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
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
