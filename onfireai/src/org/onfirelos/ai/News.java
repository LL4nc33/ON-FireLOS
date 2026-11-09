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
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The news, as Alexa's briefing has them, but summed up by the model: the headlines of a
 * public broadcaster's RSS feed - ORF in Austria, Tagesschau in Germany, SRF in
 * Switzerland, BBC elsewhere - or of a feed the user sets.
 */
final class News {

    private News() {
    }

    /** The feed for where the tablet is: by its time zone and language. */
    static String feed(Context context) {
        final String own = Prefs.string(context, Prefs.NEWS_URL);
        if (!own.isEmpty()) {
            return own;
        }
        final String zone = TimeZone.getDefault().getID();
        final String lang = Locale.getDefault().getLanguage();
        if ("Europe/Vienna".equals(zone)) {
            return "https://rss.orf.at/news.xml";
        }
        if ("Europe/Zurich".equals(zone)) {
            return "https://www.srf.ch/news/bnf/rss/1646";
        }
        if ("de".equals(lang)) {
            return "https://www.tagesschau.de/xml/rss2/";
        }
        return "https://feeds.bbci.co.uk/news/rss.xml";
    }

    static Tools.Result headlines(Context context, String topic) {
        final StringBuilder out = new StringBuilder();
        final String address = feed(context);
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "ON-FireAI");
            try (InputStream in = c.getInputStream()) {
                final XmlPullParser p = Xml.newPullParser();
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                p.setInput(in, null);
                String title = null;
                String text = null;
                boolean inItem = false;
                int n = 0;
                final String want = topic == null ? "" : topic.toLowerCase(Locale.ROOT).trim();
                for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT && n < 12;
                        e = p.next()) {
                    if (e == XmlPullParser.START_TAG) {
                        final String tag = p.getName();
                        if ("item".equals(tag) || "entry".equals(tag)) {
                            inItem = true;
                            title = null;
                            text = null;
                        } else if (inItem && "title".equals(tag)) {
                            title = p.nextText().trim();
                        } else if (inItem && ("description".equals(tag)
                                || "summary".equals(tag))) {
                            text = p.nextText().replaceAll("<[^>]+>", " ")
                                    .replaceAll("\\s+", " ").trim();
                        }
                    } else if (e == XmlPullParser.END_TAG && inItem
                            && ("item".equals(p.getName()) || "entry".equals(p.getName()))) {
                        inItem = false;
                        if (title == null) {
                            continue;
                        }
                        final String all = (title + " " + (text == null ? "" : text))
                                .toLowerCase(Locale.ROOT);
                        if (!want.isEmpty() && !all.contains(want)) {
                            continue;
                        }
                        out.append("- ").append(title);
                        if (text != null && !text.isEmpty()) {
                            out.append(": ").append(text.length() > 220
                                    ? text.substring(0, 220) + "…" : text);
                        }
                        out.append('\n');
                        n++;
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return new Tools.Result("error: news: " + LlmClient.describe(context, e), null);
        }
        if (out.length() == 0) {
            return new Tools.Result("no news" + (topic == null || topic.isEmpty() ? ""
                    : " about " + topic), null);
        }
        return new Tools.Result("headlines from " + host(address) + ":\n" + out,
                context.getString(R.string.chip_news));
    }

    private static String host(String address) {
        return android.net.Uri.parse(address).getHost();
    }
}
