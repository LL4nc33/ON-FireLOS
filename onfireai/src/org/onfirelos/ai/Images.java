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
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Pictures of a thing, shown as a row in the sheet like the videos: first the pictures of the
 * matching Wikipedia articles (the thing itself, usually), then from Wikimedia Commons. Free
 * pictures with their source, without a key.
 */
final class Images {

    /** Wikimedia asks every caller to say who it is. */
    static final String AGENT = "ON-FireAI/1.0 (ON-FireLOS tablet assistant)";
    private static final int MAX = 8;

    private Images() {
    }

    static Tools.Result search(Context context, String query) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return new Tools.Result("error: pictures of what?", null);
        }
        final JSONArray found = new JSONArray();
        final Set<String> seen = new HashSet<>();
        final String lang = Locale.getDefault().getLanguage();
        try {
            wikipedia(lang.isEmpty() ? "en" : lang, q, found, seen);
            if (found.length() < 3 && !"en".equals(lang)) {
                wikipedia("en", q, found, seen);
            }
            if (found.length() < MAX) {
                commons(q, found, seen);
            }
        } catch (Exception e) {
            if (found.length() == 0) {
                return new Tools.Result("error: no pictures right now (" + e.getMessage()
                        + "); say so", null);
            }
        }
        if (found.length() == 0) {
            return new Tools.Result("no pictures found for " + q + "; say so", null);
        }
        JSONObject card = null;
        try {
            card = new JSONObject().put("type", "images").put("images", found);
        } catch (org.json.JSONException ignored) {
            // no card then
        }
        return new Tools.Result(found.length() + " pictures of " + q + " are shown as a row the"
                + " user can swipe and tap to see large. Say in a few words that they are there;"
                + " do not describe them.", context.getString(R.string.chip_images, q), card);
    }

    private static void wikipedia(String lang, String q, JSONArray found, Set<String> seen)
            throws Exception {
        final JSONObject json = new JSONObject(get("https://" + lang + ".wikipedia.org/w/api.php"
                + "?action=query&format=json&generator=search&gsrlimit=8&prop=pageimages"
                + "&piprop=thumbnail&pithumbsize=960&gsrsearch=" + URLEncoder.encode(q, "UTF-8")));
        final JSONObject pages = json.optJSONObject("query") == null ? null
                : json.getJSONObject("query").optJSONObject("pages");
        if (pages == null) {
            return;
        }
        // In the order of the search, not of the page ids.
        final JSONObject[] ordered = new JSONObject[16];
        for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
            final JSONObject p = pages.getJSONObject(it.next());
            final int index = p.optInt("index", 15);
            if (index >= 0 && index < ordered.length && ordered[index] == null) {
                ordered[index] = p;
            }
        }
        for (JSONObject p : ordered) {
            final JSONObject thumb = p == null ? null : p.optJSONObject("thumbnail");
            // The best match always; the others only when they are about it ("Axolotl
            // Roadkill", a novel, brought its author's photo).
            if (thumb == null || found.length() >= MAX
                    || (p.optInt("index") > 1 && !about(p.optString("title"), q))) {
                continue;
            }
            final String full = thumb.optString("source");
            // A video or a document has a still too, but it is no picture of the thing.
            if (!photo(full) || !seen.add(full)) {
                continue;
            }
            found.put(new JSONObject().put("full", full).put("thumb", smaller(full))
                    .put("title", p.optString("title")).put("source", "Wikipedia"));
        }
    }

    private static void commons(String q, JSONArray found, Set<String> seen) throws Exception {
        final JSONObject json = new JSONObject(get("https://commons.wikimedia.org/w/api.php"
                + "?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=12"
                + "&prop=imageinfo&iiprop=url&iiurlwidth=960&gsrsearch="
                + URLEncoder.encode(q + " filetype:bitmap", "UTF-8")));
        final JSONObject pages = json.optJSONObject("query") == null ? null
                : json.getJSONObject("query").optJSONObject("pages");
        if (pages == null) {
            return;
        }
        final JSONObject[] ordered = new JSONObject[16];
        for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
            final JSONObject p = pages.getJSONObject(it.next());
            final int index = p.optInt("index", 15);
            if (index >= 0 && index < ordered.length && ordered[index] == null) {
                ordered[index] = p;
            }
        }
        for (JSONObject p : ordered) {
            final JSONArray info = p == null ? null : p.optJSONArray("imageinfo");
            if (info == null || info.length() == 0 || found.length() >= MAX
                    || !about(p.optString("title"), q)) {
                continue;
            }
            final String full = info.getJSONObject(0).optString("thumburl");
            if (full.isEmpty() || !photo(full)
                    || !seen.add(full)) {
                continue;
            }
            found.put(new JSONObject().put("full", full).put("thumb", smaller(full))
                    .put("title", p.optString("title").replaceFirst("^File:", "")
                            .replaceFirst("\\.[A-Za-z]{3,4}$", ""))
                    .put("source", "Wikimedia Commons"));
        }
    }

    /** Whether a title names what was asked for: all its longer words, in any form. */
    static boolean about(String title, String query) {
        final String t = title.toLowerCase(Locale.ROOT).replace('ß', 's').replace("ss", "s");
        for (String w : query.toLowerCase(Locale.ROOT).replace('ß', 's').replace("ss", "s")
                .split("[^\\p{L}\\p{N}]+")) {
            // The stem: "Großglockners", "Stephansdoms" are the same thing.
            if (w.length() >= 4 && !t.contains(w.length() > 6 ? w.substring(0, w.length() - 2)
                    : w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A photo with a scaled copy: JPEG or WebP under /thumb/. Maps, coats of arms and
     * diagrams (PNG, SVG), videos and documents are left out, and so are originals Wikimedia
     * does not hand out in a smaller size.
     */
    private static boolean photo(String url) {
        return url.contains("/thumb/") && url.matches("(?i).*\\.(jpe?g|webp)/.*");
    }

    /** The same thumbnail, 500 wide (one of the widths Wikimedia makes), for the row. */
    private static String smaller(String url) {
        return url.replaceFirst("/(\\d+)px-", "/500px-");
    }

    private static String get(String address) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", AGENT);
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
