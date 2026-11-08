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

import android.app.SearchManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What ON-FireAI can do on the tablet, as function-calling tools: their descriptions for the
 * model and their effect. Each run gives the model a short result and the sheet a chip.
 */
final class Tools {

    private static final String TAG = "ONFireAI";

    static final class Result {
        final String content;
        final String chip;

        Result(String content, String chip) {
            this.content = content;
            this.chip = chip;
        }
    }

    private final Context mContext;

    Tools(Context context) {
        mContext = context;
    }

    // ---------------------------------------------------------------- descriptions

    static JSONArray definitions(Context context) {
        final JSONArray tools = new JSONArray();
        try {
            tools.put(tool("set_timer", "Start a countdown timer.",
                    props().put("seconds", integer("Length in seconds"))
                            .put("label", string("Optional name")),
                    "seconds"));
            tools.put(tool("set_alarm", "Set an alarm clock.",
                    props().put("hour", integer("0-23"))
                            .put("minute", integer("0-59"))
                            .put("label", string("Optional name"))
                            .put("days", new JSONObject().put("type", "array")
                                    .put("items", integer("1=Sunday ... 7=Saturday"))
                                    .put("description", "Weekdays to repeat on; empty for once")),
                    "hour", "minute"));
            tools.put(tool("media", "Control what is playing (any app).",
                    props().put("action", enumeration(
                            "play, pause, next or previous", "play", "pause", "next", "previous")),
                    "action"));
            tools.put(tool("now_playing", "What is playing right now.", props()));
            tools.put(tool("play_music", "Play music by search: a song, artist, album, playlist"
                            + " or genre. Uses Spotify when installed.",
                    props().put("query", string("What to play")),
                    "query"));
            tools.put(tool("set_volume", "Media volume. Give level, or change for louder/quieter.",
                    props().put("level", integer("0-100"))
                            .put("change", integer("e.g. 10 louder, -10 quieter"))));
            tools.put(tool("set_brightness", "Screen brightness.",
                    props().put("level", integer("0-100")), "level"));
            tools.put(tool("set_dark_mode", "Switch the system between dark and light.",
                    props().put("on", bool("true for dark")), "on"));
            tools.put(tool("set_wifi", "Wi-Fi on or off.", props().put("on", bool("")), "on"));
            tools.put(tool("set_bluetooth", "Bluetooth on or off.",
                    props().put("on", bool("")), "on"));
            tools.put(tool("set_do_not_disturb", "Do not disturb on or off.",
                    props().put("on", bool("")), "on"));
            tools.put(tool("open_app", "Open an app on the tablet by its name.",
                    props().put("name", string("App name")), "name"));
            tools.put(tool("open_url", "Open a web page in the browser.",
                    props().put("url", string("Full address")), "url"));
            tools.put(tool("web_search", "Search the web. Returns results to answer from, or"
                            + " opens the browser.",
                    props().put("query", string("Search words")), "query"));
            tools.put(tool("youtube_search", "Find YouTube videos. Returns titles and links.",
                    props().put("query", string("Search words")), "query"));
            tools.put(tool("youtube_play", "Open a YouTube video or a YouTube search.",
                    props().put("url", string("Video link from youtube_search"))
                            .put("query", string("Or: words to search for"))));
            tools.put(tool("weather", "Weather now and the next days.",
                    props().put("place", string("City; empty for here"))
                            .put("days", integer("1-7, default 3"))));
            tools.put(tool("remember", "Keep a note for later conversations (facts about the"
                            + " user, wishes, things to remember).",
                    props().put("text", string("The note, as a full sentence")), "text"));
            tools.put(tool("recall", "Look through notes and earlier conversations.",
                    props().put("query", string("What to look for")), "query"));
            tools.put(tool("forget", "Delete a note.",
                    props().put("id", integer("Note id from recall")), "id"));
            if (Prefs.get(context).getBoolean(Prefs.NOTIFICATIONS, false)
                    && NotificationWatcher.get() != null) {
                tools.put(tool("notifications", "The notifications on the tablet now, newest"
                        + " first. Summarise them in a few words; read out only what is"
                        + " asked for.", props()));
            }
            if (screenOn(context)) {
                tools.put(tool("screen_read", "Read the app on screen: its elements, numbered."
                        + " Do this before acting and again after each step.", props()));
                tools.put(tool("screen_tap", "Tap element [n] from screen_read.",
                        props().put("n", integer("Element number")), "n"));
                tools.put(tool("screen_type", "Type text into field [n] from screen_read.",
                        props().put("n", integer("Field number"))
                                .put("text", string("The text")), "n", "text"));
                tools.put(tool("screen_scroll", "Scroll the screen or list [n].",
                        props().put("direction", enumeration("down or up", "down", "up"))
                                .put("n", integer("Optional: the list's number"))));
                tools.put(tool("screen_key", "Press back, home, recents or notifications.",
                        props().put("key", enumeration("which",
                                "back", "home", "recents", "notifications")), "key"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "tools", e);
        }
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject properties,
            String... required) throws JSONException {
        final JSONObject parameters = new JSONObject().put("type", "object")
                .put("properties", properties);
        if (required.length > 0) {
            parameters.put("required", new JSONArray(java.util.Arrays.asList(required)));
        }
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description).put("parameters", parameters));
    }

    private static JSONObject props() {
        return new JSONObject();
    }

    private static JSONObject string(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private static JSONObject integer(String description) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", description);
    }

    private static JSONObject bool(String description) throws JSONException {
        final JSONObject o = new JSONObject().put("type", "boolean");
        return description.isEmpty() ? o : o.put("description", description);
    }

    private static JSONObject enumeration(String description, String... values)
            throws JSONException {
        return new JSONObject().put("type", "string").put("description", description)
                .put("enum", new JSONArray(java.util.Arrays.asList(values)));
    }

    // ---------------------------------------------------------------- running

    Result run(String name, String arguments) {
        JSONObject args;
        try {
            args = arguments == null || arguments.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(arguments);
        } catch (JSONException e) {
            return new Result("error: arguments are not valid JSON", null);
        }
        try {
            switch (name) {
                case "set_timer": return setTimer(args);
                case "set_alarm": return setAlarm(args);
                case "media": return media(args.optString("action"));
                case "now_playing": return nowPlaying();
                case "play_music": return playMusic(args.optString("query"));
                case "set_volume": return setVolume(args);
                case "set_brightness": return setBrightness(args.optInt("level", 50));
                case "set_dark_mode": return setDarkMode(args.optBoolean("on", true));
                case "set_wifi": return setWifi(args.optBoolean("on", true));
                case "set_bluetooth": return setBluetooth(args.optBoolean("on", true));
                case "set_do_not_disturb": return setDnd(args.optBoolean("on", true));
                case "open_app": return openApp(args.optString("name"));
                case "open_url": return openUrl(args.optString("url"));
                case "web_search": return webSearch(args.optString("query"));
                case "youtube_search": return youtubeSearch(args.optString("query"));
                case "youtube_play": return youtubePlay(args);
                case "weather": return Weather.forTool(mContext, args.optString("place", ""),
                        args.optInt("days", 3));
                case "remember": return remember(args.optString("text"));
                case "recall": return recall(args.optString("query"));
                case "forget": return forget(args.optLong("id", -1));
                case "notifications": return notifications();
                case "screen_read": return screen(a -> a.read(blocked(mContext)));
                case "screen_tap": return screen(a -> a.tap(args.optInt("n", -1)));
                case "screen_type": return screen(a -> a.type(args.optInt("n", -1),
                        args.optString("text")));
                case "screen_scroll": return screen(a -> a.scroll(args.optInt("n", -1),
                        !"up".equals(args.optString("direction"))));
                case "screen_key": return screen(a -> a.global(args.optString("key")));
                default: return new Result("error: no tool " + name, null);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, name, e);
            return new Result("error: " + e.getMessage(), null);
        }
    }

    private String s(int id, Object... args) {
        return mContext.getString(id, args);
    }

    private boolean start(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "start " + intent, e);
            return false;
        }
    }

    private Result setTimer(JSONObject args) {
        final int seconds = Math.max(1, args.optInt("seconds", 60));
        final Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        final String label = args.optString("label", "");
        if (!label.isEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        if (!start(intent)) {
            return new Result("error: no clock app", null);
        }
        return new Result("timer started: " + seconds + " s", s(R.string.chip_timer,
                duration(seconds)));
    }

    private static String duration(int seconds) {
        final int h = seconds / 3600;
        final int m = seconds % 3600 / 60;
        final int sec = seconds % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
        }
        return String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private Result setAlarm(JSONObject args) {
        final int hour = args.optInt("hour", 7);
        final int minute = args.optInt("minute", 0);
        final Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        final String label = args.optString("label", "");
        if (!label.isEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        final JSONArray days = args.optJSONArray("days");
        if (days != null && days.length() > 0) {
            final java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
            for (int i = 0; i < days.length(); i++) {
                list.add(days.optInt(i));
            }
            intent.putExtra(AlarmClock.EXTRA_DAYS, list);
        }
        if (!start(intent)) {
            return new Result("error: no clock app", null);
        }
        final String time = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        return new Result("alarm set: " + time, s(R.string.chip_alarm, time));
    }

    // ---------------------------------------------------------------- media

    private MediaController activeController() {
        final MediaSessionManager sessions = mContext.getSystemService(MediaSessionManager.class);
        final List<MediaController> controllers = sessions.getActiveSessions(null);
        MediaController best = null;
        for (MediaController c : controllers) {
            final PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                return c;
            }
            if (best == null) {
                best = c;
            }
        }
        return best;
    }

    private Result media(String action) {
        final MediaController c = activeController();
        final String chip;
        int key;
        switch (action) {
            case "pause":
                key = KeyEvent.KEYCODE_MEDIA_PAUSE;
                chip = s(R.string.chip_paused);
                break;
            case "next":
                key = KeyEvent.KEYCODE_MEDIA_NEXT;
                chip = s(R.string.chip_next);
                break;
            case "previous":
                key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                chip = s(R.string.chip_previous);
                break;
            default:
                key = KeyEvent.KEYCODE_MEDIA_PLAY;
                chip = s(R.string.chip_resumed);
        }
        if (c != null) {
            final MediaController.TransportControls t = c.getTransportControls();
            switch (key) {
                case KeyEvent.KEYCODE_MEDIA_PAUSE: t.pause(); break;
                case KeyEvent.KEYCODE_MEDIA_NEXT: t.skipToNext(); break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: t.skipToPrevious(); break;
                default: t.play();
            }
            return new Result("done (" + appName(c.getPackageName()) + ")", chip);
        }
        // No session: the last media app still gets the key.
        final AudioManager audio = mContext.getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        return new Result("sent to the last media app", chip);
    }

    private Result nowPlaying() {
        final MediaController c = activeController();
        if (c == null || c.getMetadata() == null) {
            return new Result("nothing is playing", null);
        }
        final MediaMetadata m = c.getMetadata();
        final PlaybackState state = c.getPlaybackState();
        final boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        return new Result((playing ? "playing" : "paused") + ": "
                + nonNull(m.getString(MediaMetadata.METADATA_KEY_TITLE)) + " - "
                + nonNull(m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                + " (" + appName(c.getPackageName()) + ")", null);
    }

    static String describePlaying(Context context) {
        try {
            final Tools tools = new Tools(context);
            final MediaController c = tools.activeController();
            if (c == null || c.getMetadata() == null) {
                return "";
            }
            final PlaybackState state = c.getPlaybackState();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING) {
                return "";
            }
            final MediaMetadata m = c.getMetadata();
            return nonNull(m.getString(MediaMetadata.METADATA_KEY_TITLE)) + " - "
                    + nonNull(m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                    + " (" + tools.appName(c.getPackageName()) + ")";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private Result playMusic(String query) {
        final String spotify = "com.spotify.music";
        final Intent intent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        if (installed(spotify)) {
            intent.setPackage(spotify);
        }
        if (start(intent)) {
            return new Result("playing: " + query, s(R.string.chip_playing, query));
        }
        if (installed(spotify) && start(new Intent(Intent.ACTION_VIEW,
                Uri.parse("spotify:search:" + Uri.encode(query))).setPackage(spotify))) {
            return new Result("Spotify opened its search for: " + query
                    + " (the user picks the result)", s(R.string.chip_search, query));
        }
        return new Result("error: no music app can play by search", null);
    }

    private boolean installed(String pkg) {
        try {
            mContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- system

    private Result setVolume(JSONObject args) {
        final AudioManager audio = mContext.getSystemService(AudioManager.class);
        final int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int percent;
        if (args.has("level")) {
            percent = args.optInt("level", 50);
        } else {
            final int now = Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    / max);
            percent = now + args.optInt("change", 10);
        }
        percent = Math.max(0, Math.min(100, percent));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent * max / 100f),
                AudioManager.FLAG_SHOW_UI);
        return new Result("volume " + percent + "%", s(R.string.chip_volume, percent));
    }

    private Result setBrightness(int level) {
        final int percent = Math.max(0, Math.min(100, level));
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        // A gentle curve: low percentages stay usable in the dark.
        final int value = Math.max(1, Math.round(255 * (percent / 100f) * (percent / 100f)));
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                value);
        return new Result("brightness " + percent + "%", s(R.string.chip_brightness, percent));
    }

    /** The ROM's light/dark switch: LineageOS's berry_global_style, 2 light, 3 dark. */
    private Result setDarkMode(boolean on) {
        final ContentValues values = new ContentValues();
        values.put("name", "berry_global_style");
        values.put("value", on ? "3" : "2");
        mContext.getContentResolver().insert(Uri.parse("content://lineagesettings/system"),
                values);
        return new Result(on ? "dark mode on" : "light mode on",
                s(on ? R.string.chip_dark_on : R.string.chip_dark_off));
    }

    private Result setWifi(boolean on) {
        mContext.getSystemService(WifiManager.class).setWifiEnabled(on);
        return new Result("wifi " + (on ? "on" : "off"),
                s(on ? R.string.chip_wifi_on : R.string.chip_wifi_off));
    }

    private Result setBluetooth(boolean on) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return new Result("error: no bluetooth", null);
        }
        if (on) {
            adapter.enable();
        } else {
            adapter.disable();
        }
        return new Result("bluetooth " + (on ? "on" : "off"),
                s(on ? R.string.chip_bt_on : R.string.chip_bt_off));
    }

    private Result setDnd(boolean on) {
        // 1 = important interruptions only, 0 = off; the zen mode service follows the setting.
        Settings.Global.putInt(mContext.getContentResolver(), "zen_mode", on ? 1 : 0);
        return new Result("do not disturb " + (on ? "on" : "off"),
                s(on ? R.string.chip_dnd_on : R.string.chip_dnd_off));
    }

    // ---------------------------------------------------------------- apps and web

    private String appName(String pkg) {
        try {
            final PackageManager pm = mContext.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Result openApp(String name) {
        final PackageManager pm = mContext.getPackageManager();
        final List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        final String wanted = fold(name);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo app : apps) {
            final String label = fold(app.loadLabel(pm).toString());
            int score = 0;
            if (label.equals(wanted)) {
                score = 3;
            } else if (label.startsWith(wanted) || wanted.startsWith(label)) {
                score = 2;
            } else if (label.contains(wanted) || (wanted.length() > 3 && wanted.contains(label))) {
                score = 1;
            }
            if (score > bestScore) {
                best = app;
                bestScore = score;
            }
        }
        // Web apps on the home screen (YouTube, ...) are the browser's pinned shortcuts.
        android.content.pm.ShortcutInfo bestShortcut = null;
        for (android.content.pm.ShortcutInfo s : webApps()) {
            final String label = fold(String.valueOf(s.getShortLabel()));
            int score = label.equals(wanted) ? 4 : label.startsWith(wanted)
                    || wanted.startsWith(label) ? 2 : label.contains(wanted) ? 1 : 0;
            if (score > bestScore) {
                bestShortcut = s;
                bestScore = score;
            }
        }
        if (bestShortcut != null) {
            final String label = String.valueOf(bestShortcut.getShortLabel());
            try {
                mContext.getSystemService(android.content.pm.LauncherApps.class).startShortcut(
                        bestShortcut.getPackage(), bestShortcut.getId(), null, null,
                        android.os.Process.myUserHandle());
                return new Result("opened " + label, s(R.string.chip_opened, label));
            } catch (RuntimeException e) {
                Log.w(TAG, "web app " + label, e);
            }
        }
        if (best == null || wanted.isEmpty()) {
            final StringBuilder list = new StringBuilder("error: no app called '" + name
                    + "'. Installed: ");
            for (int i = 0; i < apps.size() && i < 60; i++) {
                list.append(i == 0 ? "" : ", ").append(apps.get(i).loadLabel(pm));
            }
            return new Result(list.toString(), null);
        }
        final Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(best.activityInfo.packageName,
                        best.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        final String label = best.loadLabel(pm).toString();
        return start(intent) ? new Result("opened " + label, s(R.string.chip_opened, label))
                : new Result("error: could not open " + label, null);
    }

    private static final String BROWSER = "org.lineageos.jelly";

    /** The browser's web apps pinned on the home screen. */
    private List<android.content.pm.ShortcutInfo> webApps() {
        try {
            final android.content.pm.LauncherApps apps =
                    mContext.getSystemService(android.content.pm.LauncherApps.class);
            final List<android.content.pm.ShortcutInfo> list = apps.getShortcuts(
                    new android.content.pm.LauncherApps.ShortcutQuery().setPackage(BROWSER)
                            .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery
                                    .FLAG_MATCH_PINNED),
                    android.os.Process.myUserHandle());
            return list == null ? new java.util.ArrayList<>() : list;
        } catch (RuntimeException e) {
            Log.w(TAG, "web apps", e);
            return new java.util.ArrayList<>();
        }
    }

    /** A link opens in its installed web app where there is one, else in the browser. */
    private boolean view(String address) {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address))
                .putExtra("org.lineageos.jelly.extra.PREFER_WEBAPP", true);
        if (installed(BROWSER)) {
            intent.setPackage(BROWSER);
        }
        return start(intent) || start(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
    }

    private Result openUrl(String url) {
        String address = url.trim();
        if (!address.contains("://")) {
            address = "https://" + address;
        }
        return view(address)
                ? new Result("opened " + address, s(R.string.chip_opened, Uri.parse(address)
                        .getHost()))
                : new Result("error: no browser", null);
    }

    private String searx() {
        return Prefs.string(mContext, Prefs.SEARCH_URL);
    }

    /** SearXNG's JSON: the first results as "title - url: snippet". */
    private String searxResults(String query, String categories, int max) throws Exception {
        String base = searx();
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        final String address = base + "/search?format=json&q=" + URLEncoder.encode(query, "UTF-8")
                + (categories.isEmpty() ? "" : "&categories=" + categories)
                + "&language=" + Locale.getDefault().getLanguage();
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "ON-FireAI");
        try {
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            final JSONArray results = new JSONObject(LlmClient.readAll(c.getInputStream()))
                    .optJSONArray("results");
            final StringBuilder out = new StringBuilder();
            for (int i = 0; results != null && i < results.length() && i < max; i++) {
                final JSONObject r = results.getJSONObject(i);
                String content = r.optString("content", "");
                if (content.length() > 300) {
                    content = content.substring(0, 300) + "…";
                }
                out.append(i + 1).append(". ").append(r.optString("title")).append(" - ")
                        .append(r.optString("url")).append(content.isEmpty() ? "" : ": " + content)
                        .append('\n');
            }
            return out.length() == 0 ? "no results" : out.toString();
        } finally {
            c.disconnect();
        }
    }

    private Result webSearch(String query) {
        if (!searx().isEmpty()) {
            try {
                return new Result(searxResults(query, "", 6), s(R.string.chip_search, query));
            } catch (Exception e) {
                Log.w(TAG, "search", e);
            }
        }
        final boolean opened = start(new Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, query));
        return opened ? new Result("the browser shows the results; there are none to read here",
                s(R.string.chip_search, query)) : new Result("error: no browser", null);
    }

    private Result youtubeSearch(String query) {
        if (!searx().isEmpty()) {
            try {
                return new Result(searxResults(query + " site:youtube.com", "videos", 6),
                        s(R.string.chip_search, query));
            } catch (Exception e) {
                Log.w(TAG, "youtube search", e);
            }
        }
        return youtubePlay(new JSONObject() {
            {
                try {
                    put("query", query);
                } catch (JSONException ignored) {
                }
            }
        });
    }

    private Result youtubePlay(JSONObject args) {
        String url = args.optString("url", "");
        final String query = args.optString("query", "");
        if (url.isEmpty() && query.isEmpty()) {
            return new Result("error: give url or query", null);
        }
        if (url.isEmpty()) {
            url = "https://m.youtube.com/results?search_query=" + Uri.encode(query);
        } else {
            // The mobile site: the installed YouTube web app opens it, and it is the faster one.
            url = url.replace("://www.youtube.com/", "://m.youtube.com/")
                    .replace("://youtube.com/", "://m.youtube.com/");
            final Uri u = Uri.parse(url);
            if ("youtu.be".equals(u.getHost()) && u.getLastPathSegment() != null) {
                url = "https://m.youtube.com/watch?v=" + u.getLastPathSegment();
            }
        }
        return view(url)
                ? new Result("opened " + url, s(R.string.chip_opened, "YouTube"))
                : new Result("error: no browser", null);
    }

    // ---------------------------------------------------------------- memory

    private Result remember(String text) {
        if (text.trim().isEmpty()) {
            return new Result("error: empty note", null);
        }
        final long id = Store.get(mContext).addNote(text);
        return new Result("noted (id " + id + ")", s(R.string.chip_noted));
    }

    private Result recall(String query) {
        final Store store = Store.get(mContext);
        final StringBuilder out = new StringBuilder();
        for (Store.Note note : store.relevantNotes(query, 8)) {
            out.append("note ").append(note.id).append(" (").append(Store.day(note.created))
                    .append("): ").append(note.text).append('\n');
        }
        for (Store.Line line : store.search(query, 8)) {
            out.append(Store.day(line.time)).append(' ').append(line.role).append(": ")
                    .append(line.text.length() > 200 ? line.text.substring(0, 200) + "…"
                            : line.text).append('\n');
        }
        return new Result(out.length() == 0 ? "nothing found" : out.toString(), null);
    }

    private Result forget(long id) {
        return Store.get(mContext).deleteNote(id) ? new Result("deleted", s(R.string.chip_forgot))
                : new Result("error: no note " + id, null);
    }

    private Result notifications() {
        final NotificationWatcher watcher = NotificationWatcher.get();
        return watcher == null ? new Result("error: notifications are off", null)
                : new Result(watcher.describe(), s(R.string.chip_notifications));
    }

    // ---------------------------------------------------------------- the screen

    static boolean screenOn(Context context) {
        return Prefs.get(context).getBoolean(Prefs.SCREEN, false) && ScreenAgent.get() != null;
    }

    static java.util.List<String> blocked(Context context) {
        final java.util.List<String> list = new java.util.ArrayList<>();
        for (String p : Prefs.string(context, Prefs.SCREEN_BLOCKED).split(",")) {
            if (!p.trim().isEmpty()) {
                list.add(p.trim());
            }
        }
        return list;
    }

    private interface ScreenAction {
        String run(ScreenAgent agent);
    }

    private Result screen(ScreenAction action) {
        final ScreenAgent agent = ScreenAgent.get();
        if (agent == null) {
            return new Result("error: screen control is off", null);
        }
        return new Result(action.run(agent), null);
    }

    static String zoneCity() {
        final String id = TimeZone.getDefault().getID();
        final int slash = id.lastIndexOf('/');
        return (slash >= 0 ? id.substring(slash + 1) : id).replace('_', ' ');
    }
}
