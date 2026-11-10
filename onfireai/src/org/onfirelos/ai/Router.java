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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which tools a question gets, and what it is told right at the question. A small model
 * offered all sixty tools picks the wrong one or none ("Wecker aus" became "do not disturb",
 * facts came from memory); offered the few that fit, with a hint where it tends to go
 * wrong, it gets them right. Measured with Qwen3-4B on 25 typical questions: 44 of 75 right
 * with all tools, 74 of 75 with these groups and hints.
 */
final class Router {

    private Router() {
    }

    /** Always there: looking up, working out, and the plain things. */
    private static final Set<String> CORE = new HashSet<>(Arrays.asList("calculate",
            "web_search", "weather", "news", "open_app", "media", "now_playing", "set_volume",
            "use_skill", "recall", "home_state", "home_control", "notifications",
            "convert_currency", "holidays", "show_images"));

    private static final class Group {
        final Pattern words;
        final Set<String> tools;

        Group(String words, String... tools) {
            this.words = Pattern.compile(words, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            this.tools = new HashSet<>(Arrays.asList(tools));
        }
    }

    private static final Group CLOCK = new Group("timer|wecker|weck|alarm|erinner|sekunde"
            + "|minute|stunde|countdown|schlummer|aufwach|aufsteh|remind|wake|uhr\\b|um \\d"
            + "|halb|viertel",
            "set_timer", "timers", "timer_control", "set_alarm", "alarms", "alarm_control",
            "set_wakeup", "wakeups", "delete_wakeup", "wakeup_control", "set_reminder",
            "list_reminders", "cancel_reminder", "sleep_timer");
    private static final Group AUDIO = new Group("spiel|musik|radio|sender|ö3|oe3|fm4|song"
            + "|lied|playlist|album|podcast|youtube|video|regen|meer|rausch|einschlaf|hören"
            + "|abspiel|play|music|sound",
            "play_music", "play_radio", "play_sounds", "stop_sounds", "sleep_timer",
            "youtube_search", "youtube_play");
    private static final Group LISTS = new Group("liste|einkauf|kaufen|besorgen|abhak|todo"
            + "|to-do|streich|list\\b|shopping",
            "add_to_list", "show_list", "check_off", "clear_list");
    private static final Group CALENDAR = new Group("termin|kalender|meeting|verabredung"
            + "|geburtstag|event|eintrag|appointment|calendar",
            "calendar_events", "add_event");
    private static final Group DEVICE = new Group("hell|dunkel|wlan|wifi|w-lan|bluetooth"
            + "|nicht stören|ruhe|bildschirm|display|brightness|dark",
            "set_brightness", "set_dark_mode", "set_wifi", "set_bluetooth",
            "set_do_not_disturb");
    private static final Group ROUTINES = new Group("routine|jeden|täglich|immer wenn"
            + "|automatisch|every day|whenever",
            "create_routine", "list_routines", "delete_routine");
    private static final Group MEMORY = new Group("merk|vergiss|weißt du|erinnerst du"
            + "|notier|was weißt|lern|remember|forget|learn",
            "remember", "forget", "save_skill");
    private static final Group BROWSER = new Group("browser|webseite|seite|tab\\b|willhaben"
            + "|amazon|geizhals|idealo|bestell|formular|\\.at\\b|\\.com\\b|\\.de\\b|preis"
            + "|kostet|vergleich|recherch|kochen|rezept|website|price",
            "open_url", "browser_tabs", "browser_open", "browser_read", "browser_act",
            "browser_media", "save_skill");
    private static final Group SCREEN = new Group("tipp|klick|scroll|wisch|drück|zurück"
            + "|nach unten|nach oben|bildschirm|in der app|tap|click|swipe",
            "screen_read", "screen_tap", "screen_type", "screen_scroll", "screen_key");
    private static final Group[] GROUPS = {CLOCK, AUDIO, LISTS, CALENDAR, DEVICE, ROUTINES,
            MEMORY, BROWSER, SCREEN};

    /** Set by the session: the question comes from the pill over an app in front. */
    static volatile boolean sAppInFront;

    /**
     * The tools for this question: the core, the groups its words call for, and the groups of
     * tools used in this conversation already (a follow-up "und auf 8 Uhr" needs the clock).
     */
    static JSONArray tools(JSONArray all, String question, Set<String> used) {
        final Set<String> keep = new HashSet<>(CORE);
        final String q = question.toLowerCase(Locale.ROOT);
        for (Group g : GROUPS) {
            if (g.words.matcher(q).find()) {
                keep.addAll(g.tools);
            }
        }
        for (String name : used) {
            for (Group g : GROUPS) {
                if (g.tools.contains(name)) {
                    keep.addAll(g.tools);
                }
            }
        }
        // An app opened or in front is worked by screen; a skill may need the browser.
        if (sAppInFront || used.contains("open_app")) {
            keep.addAll(SCREEN.tools);
        }
        if (used.contains("use_skill")) {
            keep.addAll(BROWSER.tools);
        }
        // The core first, always the same: the server keeps the start of a prompt that does
        // not change, and only reads the rest anew (measured: about 330 instead of 750 ms of
        // reading per question with Qwen3-4B).
        final JSONArray out = new JSONArray();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < all.length(); i++) {
                final JSONObject t = all.optJSONObject(i);
                final JSONObject fn = t == null ? null : t.optJSONObject("function");
                final String name = fn == null ? "" : fn.optString("name");
                if (keep.contains(name) && CORE.contains(name) == (pass == 0)) {
                    out.put(t);
                }
            }
        }
        return out;
    }

    private static final Pattern NEWS = Pattern.compile(
            "nachrichten|\\bnews\\b|neuigkeiten|gibt.s neues|was gibt es neues|schlagzeilen");
    private static final Pattern MATH = Pattern.compile("\\d\\s*([+*/×÷^]|mal\\b"
            + "|plus\\b|minus\\b|geteilt|durch\\b|hoch\\b|times\\b|divided|squared)"
            + "\\s*(\\p{L}+\\s+)?\\d|\\d\\s*(%|prozent|percent)|(wurzel|root|fakultät"
            + "|factorial)\\D{0,12}\\d|rechne|calculate", Pattern.UNICODE_CASE);
    private static final Pattern CLOCK_WORDS = Pattern.compile(
            "\\b(halb|viertel|dreiviertel)\\b");
    private static final Pattern FROM_NOW = Pattern.compile("\\bin (einer|einem|einer halben"
            + "|\\d+|zwei|drei|vier|fünf|zehn|zwanzig|dreißig|vierzig) (minute|stunde"
            + "|halben stunde|sekunde)|\\bin (an|a|\\d+) (minute|hour|second)");
    private static final Pattern ALARM_OFF = Pattern.compile(
            "(wecker|alarm)\\S*.*\\b(aus|ab|lösch\\w*|stopp|weg|off|delete|cancel)\\b"
                    + "|\\b(lösch\\w*|stopp?|delete|cancel|turn off)\\b.*(wecker|alarm)");
    private static final Pattern CALENDAR_ASK = Pattern.compile("\\b(termin|kalender"
            + "|appointment|calendar)");
    private static final Pattern FACT = Pattern.compile("^(wer|wann|wo|welche\\w*|wie viele"
            + "|wie viel|wie hoch|wie alt|wie lang|wie groß|wie weit|wie heißt|who|when|where"
            + "|which|how many|how much|how old|how tall)\\b|gewonnen|öffnungszeit|offen\\b"
            + "|won\\b|opening hours");
    private static final Pattern PERSONAL = Pattern.compile("termin|wecker|timer|liste"
            + "|erinner|notiz|spät|\\buhr\\b|wetter|regnet|\\bmeine?\\w*|\\bich\\b|alarm"
            + "|weather|\\bmy\\b|\\btag\\b|datum|wochentag|uhrzeit|kalenderwoche|\\bdate\\b"
            + "|\\bday\\b|\\btime\\b|\\bdu\\b|\\bdich\\b|\\bdir\\b|\\byou\\b|\\byour\\b");
    private static final Pattern CURRENCY = Pattern.compile("(dollar|euro|franken|pfund"
            + "|yen|kronen|forint|złoty|zloty|bitcoin|usd|eur|chf|gbp)\\S*\\s+(in|zu|nach|to)\\s+"
            + "\\p{L}+|wechselkurs|exchange rate");
    private static final Pattern PICTURES = Pattern.compile("\\bbilder?\\b|\\bfotos?\\b"
            + "|photo|picture|image|wie sieht .* aus|what does .* look like");
    private static final Pattern HOLIDAY = Pattern.compile("feiertag|holiday|fenstertag");
    private static final Pattern ABILITIES = Pattern.compile("was kannst du|was du alles"
            + "|wobei kannst du|wie kannst du mir helfen|what can you do");

    private static final Pattern ENGLISH = Pattern.compile("(?i)\\b(the|what|what's|how|who"
            + "|when|where|which|is|are|my|me|you|your|please|can|could|will|would|tell|show"
            + "|thanks|thank|it|of|to|in|for|and|today|tomorrow)\\b");
    private static final Pattern GERMAN = Pattern.compile("(?iu)\\b(der|die|das|was|wie|wer"
            + "|wann|wo|welche\\w*|ist|sind|mein|meine|mir|mich|du|dir|bitte|kannst|danke"
            + "|und|heute|morgen|es|ein|eine|nicht|mit|auf|für)\\b");

    /** Whether the user spoke English (the answer then is English too). */
    static boolean english(String question) {
        int en = 0;
        int de = 0;
        for (Matcher m = ENGLISH.matcher(question); m.find(); ) {
            en++;
        }
        for (Matcher m = GERMAN.matcher(question); m.find(); ) {
            de++;
        }
        return en > de;
    }

    private static final Pattern LIST_READ = Pattern.compile("(was steht|was ist auf|zeig|lies"
            + "|what.s on|what is on|show|read).*(liste|list)");

    /** Hints for this question, as lines for the context block; "" for none. */
    static String hints(String question) {
        final String q = question.toLowerCase(Locale.ROOT);
        final StringBuilder h = new StringBuilder();
        if (LIST_READ.matcher(q).find()) {
            h.append("Hint: to read a list: show_list; add only things the user names to"
                    + " add.\n");
        }
        if (NEWS.matcher(q).find()) {
            h.append("Hint: the news come from news; never make up news.\n");
        }
        if (MATH.matcher(q).find()) {
            h.append("Hint: this asks for arithmetic: work it out with calculate, then answer"
                    + " with its result.\n");
        }
        if (CLOCK_WORDS.matcher(q).find()) {
            h.append("Hint: in German \"halb sieben\" is 6:30, \"viertel acht\" 7:15,"
                    + " \"dreiviertel acht\" 7:45.\n");
        }
        if (FROM_NOW.matcher(q).find()) {
            h.append("Hint: a time from now: give it as in_minutes or seconds, not as a"
                    + " date.\n");
        }
        if (ALARM_OFF.matcher(q).find()) {
            h.append("Hint: first alarms to see the alarms and their ids, then"
                    + " alarm_control.\n");
        }
        if (CALENDAR_ASK.matcher(q).find()) {
            h.append("Hint: look in the calendar with calendar_events, do not guess.\n");
        }
        if (CURRENCY.matcher(q).find()) {
            h.append("Hint: money in another currency: convert_currency (today's exchange"
                    + " rate); never guess a rate.\n");
        }
        if (PICTURES.matcher(q).find() && !q.contains("video")) {
            h.append("Hint: pictures are shown with show_images.\n");
        }
        if (HOLIDAY.matcher(q).find()) {
            h.append("Hint: public holidays come from holidays, not from a web search.\n");
        }
        if (ABILITIES.matcher(q).find()) {
            h.append("Hint: answer in 2 short spoken sentences with 4 or 5 examples (weather,"
                    + " timers, music and radio, looking things up, the lists), no list.\n");
        }
        if (fragment(q)) {
            h.append("Hint: this may be misheard, or only part of a sentence: unless it is a"
                    + " clear request, do nothing and ask in one short question what the user"
                    + " means.\n");
        }
        if (FACT.matcher(q).find() && !PERSONAL.matcher(q).find()
                && !HOLIDAY.matcher(q).find() && !CURRENCY.matcher(q).find()) {
            h.append("Hint: a fact that can change: look it up with web_search first, answer"
                    + " from what it finds.\n");
        }
        return h.toString();
    }

    private static final Pattern COMMAND = Pattern.compile("\\b(mach|spiel|stopp?|öffne|zeig"
            + "|schließ|weiter|zurück|lauter|leiser|pause|hilfe|danke|ja|nein|ok|okay|nochmal"
            + "|wie|was|wer|wo|wann|warum|hallo|hi|servus|play|stop|open|show|yes|no|thanks"
            + "|erste|zweite|dritte|vierte|letzte|nächste|vorige|mehr|weniger|first|second"
            + "|third|last|next|more|wetter|nachrichten|news|licht|lampe|heizung|laut|leise"
            + "|lautstärke|volume|weather|rechne|uhr|zeit|time|bilder|bild|app|ein|aus|an"
            + "|und|auch|noch|morgen|heute|on|off|and|tomorrow|today)"
            + "\\b");

    /** A few words without a question, a command or anything a tool group knows. */
    static boolean fragment(String q) {
        final String t = q.trim();
        if (t.isEmpty() || t.endsWith("?") || t.split("\\s+").length > 3
                || COMMAND.matcher(t).find()) {
            return false;
        }
        for (Group g : GROUPS) {
            if (g.words.matcher(t).find()) {
                return false;
            }
        }
        return true;
    }

    /** A question that surely needs a tool first: the model may not answer from its head. */
    static boolean needsTool(String question) {
        final String h = hints(question);
        return h.contains("arithmetic") || h.contains("the news")
                || h.contains("exchange")
                || h.contains("from holidays")
                || h.contains("calendar_events");
    }
}
