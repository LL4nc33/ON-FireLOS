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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Written text as it is said: "7°C" is "7 Grad", not "7 Grad Celsius" each time, "15,50 €"
 * is "15 Euro 50", "(gefühlt 5°C)" is a pause and not a bracket, "Mo–Sa" is "Montag bis
 * Samstag". Only the voice gets this; the screen keeps the written form.
 */
final class Spoken {

    private Spoken() {
    }

    private static final String[][] DAYS_DE = {{"Mo", "Montag"}, {"Di", "Dienstag"},
            {"Mi", "Mittwoch"}, {"Do", "Donnerstag"}, {"Fr", "Freitag"}, {"Sa", "Samstag"},
            {"So", "Sonntag"}};
    private static final Pattern DAY_RANGE = Pattern.compile(
            "\\b(Mo|Di|Mi|Do|Fr|Sa|So)\\.?\\s*[-–]\\s*(Mo|Di|Mi|Do|Fr|Sa|So)\\b\\.?");
    private static final Pattern MONEY = Pattern.compile(
            "(\\d+)(?:[,.](\\d{2}))?\\s*(?:€|EUR\\b|Euro\\b)|(?:€|EUR)\\s*(\\d+)(?:[,.](\\d{2}))?");

    static String say(String text) {
        final boolean german = german(text);
        String t = text;
        // Square brackets are not said at all; round ones are a pause.
        t = t.replace("[", "").replace("]", "");
        t = t.replaceAll("\\s*\\(([^()]{1,80})\\)", ", $1,");
        // Minus before a number, a range between two.
        t = t.replaceAll("(^|[\\s(,])[-−](\\d)", "$1" + (german ? "minus " : "minus ") + "$2");
        t = t.replaceAll("(\\d)\\s*[–-]\\s*(\\d)", "$1 " + (german ? "bis" : "to") + " $2");
        // Temperatures: the unit once is enough, and Celsius is understood.
        t = t.replaceAll("\\s*°\\s*[CF]\\b", german ? " Grad" : " degrees");
        t = t.replace("°", german ? " Grad" : " degrees");
        t = t.replaceAll("(?i)\\b(Grad|degrees) (Celsius|Fahrenheit)\\b", "$1");
        // Times without their leading nought: "09:00" is said "neun Uhr".
        t = t.replaceAll("\\b0(\\d):(\\d\\d)", "$1:$2");
        t = money(t, german);
        if (german) {
            t = t.replaceAll("(\\d)\\s*km/h", "$1 Kilometer pro Stunde")
                    .replaceAll("(\\d)\\s*km\\b", "$1 Kilometer")
                    .replaceAll("(\\d)\\s*kg\\b", "$1 Kilo")
                    .replaceAll("(\\d)\\s*mm\\b", "$1 Millimeter")
                    .replaceAll("(\\d)\\s*cm\\b", "$1 Zentimeter")
                    .replaceAll("\\bz\\.\\s?B\\.", "zum Beispiel")
                    .replaceAll("\\bca\\.", "circa")
                    .replaceAll("\\bbzw\\.", "beziehungsweise")
                    .replaceAll("\\busw\\.", "und so weiter")
                    .replaceAll("\\binkl\\.", "inklusive")
                    .replaceAll("\\bggf\\.", "gegebenenfalls")
                    .replaceAll("\\bd\\.\\s?h\\.", "das heißt")
                    .replaceAll("\\bu\\.\\s?a\\.", "unter anderem");
            t = days(t);
        } else {
            t = t.replaceAll("(\\d)\\s*km/h", "$1 kilometers per hour")
                    .replaceAll("(\\d)\\s*km\\b", "$1 kilometers")
                    .replaceAll("\\be\\.g\\.", "for example")
                    .replaceAll("\\bapprox\\.", "about");
        }
        // What the replacing left: doubled commas, a comma before the end.
        return t.replaceAll("\\s*,\\s*,", ",").replaceAll(",\\s*([.!?])", "$1")
                .replaceAll(",\\s*$", "").replaceAll("\\s+", " ").trim();
    }

    private static final Pattern GERMAN_WORDS = Pattern.compile(
            "(?iu)\\b(der|die|das|und|ist|nicht|mit|ein|eine|es|ich|du|heute|morgen|bis|uhr|grad"
            + "|laut|sind|wird|für|auf)\\b");
    private static final Pattern ENGLISH_WORDS = Pattern.compile(
            "(?i)\\b(the|and|is|not|with|a|an|it|you|today|tomorrow|to|of|are|will|for|on"
            + "|according|degrees)\\b");

    /** The answer's own language, so an English answer is read the English way. */
    static boolean german(String text) {
        final int de = count(GERMAN_WORDS, text);
        final int en = count(ENGLISH_WORDS, text);
        return de == en ? "de".equals(Locale.getDefault().getLanguage()) : de > en;
    }

    private static int count(Pattern p, String text) {
        final Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** "15,50 €" as "15 Euro 50", "20,00 €" as "20 Euro". */
    private static String money(String t, boolean german) {
        final Matcher m = MONEY.matcher(t);
        final StringBuffer out = new StringBuffer();
        while (m.find()) {
            final String whole = m.group(1) != null ? m.group(1) : m.group(3);
            final String cents = m.group(1) != null ? m.group(2) : m.group(4);
            final String said = whole + (german ? " Euro" : " euros")
                    + (cents == null || "00".equals(cents) ? ""
                    : " " + (cents.startsWith("0") ? cents.substring(1) : cents));
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** "Mo–Sa" as "Montag bis Samstag". */
    private static String days(String t) {
        final Matcher m = DAY_RANGE.matcher(t);
        final StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(day(m.group(1)) + " bis "
                    + day(m.group(2))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String day(String shortName) {
        for (String[] d : DAYS_DE) {
            if (d[0].equals(shortName)) {
                return d[1];
            }
        }
        return shortName;
    }
}
