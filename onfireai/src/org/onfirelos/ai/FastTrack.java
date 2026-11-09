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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The quick lane: short commands the tablet carries out itself in well under a second,
 * without asking the model - pause, go on, next track, louder, quieter, a timer.
 */
final class FastTrack {

    private static final Pattern PAUSE = Pattern.compile(
            "(pause|pausieren|pausiere|stopp?|halt|anhalten|stop|stop the music|musik aus)");
    private static final Pattern PLAY = Pattern.compile(
            "(weiter|weiterspielen|fortsetzen|play|resume|abspielen|musik an|spiel weiter)");
    private static final Pattern NEXT = Pattern.compile(
            "(nächstes( lied)?|nächster titel|nächster song|next( song| track)?|skip|überspringen)");
    private static final Pattern PREVIOUS = Pattern.compile(
            "(voriges lied|vorheriges lied|letztes lied|vorheriger titel|previous( song| track)?)");
    private static final Pattern LOUDER = Pattern.compile("(lauter|louder|volume up)");
    private static final Pattern QUIETER = Pattern.compile("(leiser|quieter|volume down)");
    private static final Pattern TIMER = Pattern.compile(
            "(?:stell(?:e)? )?(?:einen |den |a |the )?(?:timer|wecker in) (?:auf |für |von |for |in )?"
                    + "(\\d+|[a-zäöüß]+) (sekunden?|minuten?|stunden?|seconds?|minutes?|hours?)"
                    + "(?: stellen| lang| starten)?");

    private static final Map<String, Integer> NUMBERS = new HashMap<>();

    static {
        final String[] de = { "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben",
                "acht", "neun", "zehn", "elf", "zwölf" };
        for (int i = 0; i < de.length; i++) {
            NUMBERS.put(de[i], i);
        }
        NUMBERS.put("ein", 1);
        NUMBERS.put("eine", 1);
        NUMBERS.put("einen", 1);
        NUMBERS.put("fünfzehn", 15);
        NUMBERS.put("zwanzig", 20);
        NUMBERS.put("dreißig", 30);
        NUMBERS.put("vierzig", 40);
        NUMBERS.put("fünfzig", 50);
        NUMBERS.put("sechzig", 60);
        final String[] en = { "zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve" };
        for (int i = 0; i < en.length; i++) {
            NUMBERS.put(en[i], i);
        }
        NUMBERS.put("a", 1);
        NUMBERS.put("an", 1);
        NUMBERS.put("fifteen", 15);
        NUMBERS.put("twenty", 20);
        NUMBERS.put("thirty", 30);
    }

    private FastTrack() {
    }

    /** Runs the command and returns what was said, or null when it is one for the model. */
    static String handle(Context context, String text, Agent.Callback callback) {
        final String t = text.toLowerCase(Locale.ROOT).replaceAll("[.!?,]", "").trim();
        final Tools tools = new Tools(context);
        Tools.Result result = null;
        String spoken = context.getString(R.string.fast_ok);
        if (PAUSE.matcher(t).matches()) {
            result = tools.run("media", "{\"action\":\"pause\"}");
        } else if (PLAY.matcher(t).matches()) {
            result = tools.run("media", "{\"action\":\"play\"}");
        } else if (NEXT.matcher(t).matches()) {
            result = tools.run("media", "{\"action\":\"next\"}");
        } else if (PREVIOUS.matcher(t).matches()) {
            result = tools.run("media", "{\"action\":\"previous\"}");
        } else if (LOUDER.matcher(t).matches()) {
            result = tools.run("set_volume", "{\"change\":10}");
        } else if (QUIETER.matcher(t).matches()) {
            result = tools.run("set_volume", "{\"change\":-10}");
        } else {
            final Matcher m = TIMER.matcher(t);
            if (m.matches()) {
                final Integer n = m.group(1).matches("\\d{1,4}") ? Integer.valueOf(m.group(1))
                        : NUMBERS.get(m.group(1));
                // Nothing, or more than a day: the model asks back.
                if (n == null || n <= 0 || n > 1440) {
                    return null;
                }
                final String unit = m.group(2);
                final int factor = unit.startsWith("s") ? 1 : unit.startsWith("m") ? 60 : 3600;
                result = tools.run("set_timer", "{\"seconds\":" + (n * factor) + "}");
                spoken = context.getString(R.string.fast_timer, n, unit);
            }
        }
        if (result == null) {
            return null;
        }
        if (result.content.startsWith("error")) {
            // Let the model deal with it, it can explain.
            return null;
        }
        if (result.chip != null) {
            callback.onChip(result.chip);
        }
        callback.onText(spoken);
        callback.onSentence(spoken);
        callback.onDone(spoken);
        return spoken;
    }
}
