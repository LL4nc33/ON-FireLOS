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

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One conversation: what the user says goes to the model with the tablet's context and the
 * tools; the answer streams back sentence by sentence; tools run and the model goes on. The
 * conversation stays open for 30 minutes, so follow-ups ("and tomorrow?") keep their thread.
 */
final class Agent {

    private static final String TAG = "ONFireAI";
    private static final long CONVERSATION_GAP = 30 * 60 * 1000;
    private static final int MAX_ROUNDS = 5;
    /** Operating an app takes more steps: read, tap, read again ... */
    private static final int MAX_SCREEN_ROUNDS = 24;
    private static final java.util.regex.Pattern RISKY = java.util.regex.Pattern.compile(
            "(?i).*(senden|absenden|send|kaufen|kauf|buy|bestell|order|bezahl|pay|lösch|delete|"
                    + "entfern|remove|überweis|transfer|post|veröffentlich|publish|abonn|"
                    + "subscribe|install|deinstall|uninstall|zurücksetzen|reset).*");
    private static final int MAX_HISTORY = 24;

    interface Callback {
        /** A piece of the answer for the screen. */
        void onText(String piece);

        /** A whole sentence, cleaned for the voice. */
        void onSentence(String sentence);

        void onChip(String chip);

        /** A tool round finished; the model writes on. */
        void onToolRound();

        void onDone(String answer);

        void onError(String message, boolean needsSetup);

        /** A tool is about to run (for the pill outside the sheet). */
        void onStep(String tool, String detail);

        /** Asks the user before a risky step; the answer goes to the callback. */
        void onConfirm(String question, java.util.function.Consumer<Boolean> answer);
    }

    private static final List<JSONObject> sHistory = new ArrayList<>();
    private static long sLastActive;

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile LlmClient mClient;
    private volatile boolean mCancelled;

    Agent(Context context, Callback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    static void newConversation() {
        synchronized (sHistory) {
            sHistory.clear();
            sLastActive = 0;
        }
    }

    static boolean conversationOpen() {
        synchronized (sHistory) {
            return !sHistory.isEmpty()
                    && System.currentTimeMillis() - sLastActive < CONVERSATION_GAP;
        }
    }

    /** The last messages of the open conversation with text, for the sheet to show again. */
    static List<JSONObject> recentMessages(int max) {
        final List<JSONObject> out = new ArrayList<>();
        synchronized (sHistory) {
            for (int i = sHistory.size() - 1; i >= 0 && out.size() < max; i--) {
                final JSONObject m = sHistory.get(i);
                final String role = m.optString("role");
                if (("user".equals(role) || "assistant".equals(role))
                        && !m.optString("content", "").trim().isEmpty()) {
                    out.add(0, m);
                }
            }
        }
        return out;
    }

    void cancel() {
        mCancelled = true;
        final LlmClient client = mClient;
        if (client != null) {
            client.cancel();
        }
    }

    /** Main thread. */
    void ask(String text) {
        mCancelled = false;
        final String fast = FastTrack.handle(mContext, text, mCallback);
        if (fast != null) {
            log(text, fast);
            return;
        }
        if (!Prefs.configured(mContext)) {
            mCallback.onError(mContext.getString(R.string.not_configured), true);
            return;
        }
        new Thread(() -> run(text), "ONFireAI-agent").start();
    }

    private void run(String text) {
        final LlmClient client = new LlmClient(Prefs.string(mContext, Prefs.URL),
                Prefs.string(mContext, Prefs.API_KEY), Prefs.string(mContext, Prefs.MODEL));
        mClient = client;
        final Tools tools = new Tools(mContext);
        final JSONArray toolDefs = Tools.definitions(mContext);
        // The tools of the user's MCP servers join the built-in ones.
        final java.util.Map<String, Mcp.Tool> mcp = new java.util.HashMap<>();
        for (Mcp.Tool t : Mcp.tools(mContext)) {
            mcp.put(t.exposedName, t);
            toolDefs.put(t.definition);
        }
        final StringBuilder answer = new StringBuilder();
        try {
            final List<JSONObject> turn = new ArrayList<>();
            turn.add(new JSONObject().put("role", "user")
                    .put("content", contextBlock(text) + text));
            int limit = MAX_ROUNDS;
            for (int round = 0; round < limit && !mCancelled; round++) {
                final JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
                synchronized (sHistory) {
                    if (System.currentTimeMillis() - sLastActive > CONVERSATION_GAP) {
                        sHistory.clear();
                    }
                    for (JSONObject m : sHistory) {
                        messages.put(m);
                    }
                }
                for (JSONObject m : turn) {
                    messages.put(m);
                }
                final Sentences sentences = new Sentences();
                final LlmClient.Result result = client.chat(messages,
                        round < limit - 1 ? toolDefs : null, piece -> {
                            post(() -> mCallback.onText(piece));
                            for (String s : sentences.add(piece)) {
                                post(() -> mCallback.onSentence(s));
                            }
                        });
                final String rest = sentences.rest();
                if (!rest.isEmpty()) {
                    post(() -> mCallback.onSentence(rest));
                }
                answer.append(result.text);
                if (result.toolCalls.length() == 0 || mCancelled) {
                    turn.add(new JSONObject().put("role", "assistant")
                            .put("content", result.text.toString()));
                    break;
                }
                final JSONObject call = new JSONObject().put("role", "assistant")
                        .put("content", result.text.toString())
                        .put("tool_calls", result.toolCalls);
                turn.add(call);
                for (int i = 0; i < result.toolCalls.length(); i++) {
                    final JSONObject tc = result.toolCalls.getJSONObject(i);
                    final JSONObject fn = tc.getJSONObject("function");
                    final String toolName = fn.optString("name");
                    final Mcp.Tool external = mcp.get(toolName);
                    final Tools.Result r;
                    if (toolName.startsWith("screen_")) {
                        limit = MAX_SCREEN_ROUNDS;
                    }
                    final String detail = stepDetail(toolName, fn.optString("arguments"));
                    post(() -> mCallback.onStep(toolName, detail));
                    final String tapLabel = "screen_tap".equals(toolName)
                            ? screenLabel(fn.optString("arguments")) : "";
                    if (external == null && !tapLabel.isEmpty()
                            && RISKY.matcher(tapLabel).matches()
                            && !confirm(mContext.getString(R.string.confirm_tap, tapLabel))) {
                        r = new Tools.Result("the user said no; stop and tell them", null);
                    } else if (external == null) {
                        r = tools.run(toolName, fn.optString("arguments"));
                    } else if (external.destructive && !confirm(mContext.getString(
                            R.string.confirm_tool, readable(external.name)))) {
                        r = new Tools.Result("the user said no; do not try again", null);
                    } else {
                        r = new Tools.Result(Mcp.run(external, fn.optString("arguments")),
                                mContext.getString(R.string.chip_tool, readable(external.name)));
                    }
                    Log.i(TAG, "tool " + fn.optString("name") + " " + fn.optString("arguments")
                            + " -> " + r.content.replace('\n', ' '));
                    if (r.chip != null) {
                        post(() -> mCallback.onChip(r.chip));
                    }
                    turn.add(new JSONObject().put("role", "tool")
                            .put("tool_call_id", tc.optString("id"))
                            .put("name", fn.optString("name"))
                            .put("content", r.content));
                }
                post(mCallback::onToolRound);
            }
            if (mCancelled) {
                return;
            }
            // The turn joins the conversation without its context block: the next question
            // brings a fresh one.
            turn.set(0, new JSONObject().put("role", "user").put("content", text));
            synchronized (sHistory) {
                sHistory.addAll(turn);
                trim();
                sLastActive = System.currentTimeMillis();
            }
            final String done = answer.toString().trim();
            log(text, done);
            post(() -> mCallback.onDone(done));
        } catch (LlmClient.ServerException e) {
            if (!mCancelled) {
                post(() -> mCallback.onError(mContext.getString(R.string.error_server,
                        e.getMessage()), false));
            }
        } catch (IOException e) {
            Log.w(TAG, "model", e);
            if (!mCancelled) {
                post(() -> mCallback.onError(mContext.getString(R.string.error_network), false));
            }
        } catch (JSONException e) {
            Log.e(TAG, "model", e);
            post(() -> mCallback.onError(mContext.getString(R.string.error_server,
                    e.getMessage()), false));
        } finally {
            mClient = null;
        }
    }

    /** What a step is about: the element tapped, the text typed, the app opened. */
    private static String stepDetail(String tool, String arguments) {
        try {
            final JSONObject a = new JSONObject(arguments == null || arguments.isEmpty()
                    ? "{}" : arguments);
            switch (tool) {
                case "screen_tap": return screenLabel(arguments);
                case "screen_type": return a.optString("text");
                case "screen_key": return a.optString("key");
                case "open_app": return a.optString("name");
                default: return "";
            }
        } catch (JSONException e) {
            return "";
        }
    }

    /** "clear_shopping_list" as "Clear shopping list". */
    static String readable(String name) {
        final String s = name.replaceAll("[_-]+", " ").trim();
        return s.isEmpty() ? name : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String screenLabel(String arguments) {
        final ScreenAgent agent = ScreenAgent.get();
        if (agent == null) {
            return "";
        }
        try {
            return agent.label(new JSONObject(arguments).optInt("n", -1));
        } catch (JSONException e) {
            return "";
        }
    }

    /** Worker thread: waits for the user's yes or no, at most a minute. */
    private boolean confirm(String question) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final boolean[] yes = new boolean[1];
        post(() -> mCallback.onConfirm(question, answer -> {
            yes[0] = answer;
            latch.countDown();
        }));
        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
        return yes[0] && !mCancelled;
    }

    /** Keeps the newest turns, cut only before a user message so no tool answer is orphaned. */
    private static void trim() {
        while (sHistory.size() > MAX_HISTORY) {
            int cut = 1;
            while (cut < sHistory.size()
                    && !"user".equals(sHistory.get(cut).optString("role"))) {
                cut++;
            }
            sHistory.subList(0, cut).clear();
        }
    }

    private void log(String question, String answer) {
        if (!Prefs.flag(mContext, Prefs.KEEP_LOG)) {
            return;
        }
        final Store store = Store.get(mContext);
        store.log("user", question);
        store.log("assistant", answer);
    }

    private void post(Runnable r) {
        if (!mCancelled) {
            mMain.post(() -> {
                if (!mCancelled) {
                    r.run();
                }
            });
        }
    }

    // ---------------------------------------------------------------- what the model gets

    /** Fixed for a device and language, so the server can keep it cached between questions. */
    private String systemPrompt() {
        final String language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH);
        final boolean learn = Prefs.flag(mContext, Prefs.LEARN);
        return "You are ON-FireAI, the voice assistant on a FireHD10 tablet running ON-FireLOS."
                + " The user talks to you through speech recognition, which can mishear, and"
                + " your answers are read aloud.\n"
                + "- Answer in " + language + " unless the user speaks another language. Be"
                + " brief and natural, as in a conversation: usually one to three sentences."
                + " No lists, no markdown, no emoji, no links read out.\n"
                + "- Do things with the tools instead of explaining how. After a tool, confirm"
                + " in a few words.\n"
                + "- For current facts you are unsure of, use web_search; for weather, weather;"
                + " for music, play_music; for videos, youtube_search then youtube_play.\n"
                + "- If a request is unclear, ask one short question back.\n"
                + "- Write numbers, times and units the way they are spoken.\n"
                + "- Keep the thread of the conversation: the user may refer to earlier turns.\n"
                + "- Each message starts with [context] about the tablet and notes about the"
                + " user; use it when it helps, never read it out.\n"
                + (Tools.screenOn(mContext) ? "- To do something in an app: open it, then"
                        + " screen_read, act on the numbered elements and screen_read again to"
                        + " check, step by step until done. Say briefly what you do. Ask before"
                        + " sending, buying, paying or deleting.\n" : "")
                + (learn ? "- When the user tells you something worth keeping (names, wishes,"
                        + " habits), keep it with remember, without asking.\n"
                        : "- Keep notes with remember only when the user asks.\n");
    }

    private String contextBlock(String question) {
        final StringBuilder c = new StringBuilder("[context]\n");
        final Date now = new Date();
        c.append("Now: ").append(new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
                .format(now)).append(" (").append(TimeZone.getDefault().getID()).append(")\n");
        final Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            final boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            c.append("Battery: ").append(level * 100 / Math.max(1, scale)).append('%')
                    .append(plugged ? ", charging" : "").append('\n');
        }
        final AlarmManager alarms = mContext.getSystemService(AlarmManager.class);
        final AlarmManager.AlarmClockInfo next = alarms.getNextAlarmClock();
        if (next != null) {
            c.append("Next alarm: ").append(new SimpleDateFormat("EEE HH:mm", Locale.ENGLISH)
                    .format(new Date(next.getTriggerTime()))).append('\n');
        }
        final String playing = Tools.describePlaying(mContext);
        if (!playing.isEmpty()) {
            c.append("Playing: ").append(playing).append('\n');
        }
        final List<Store.Note> notes = Store.get(mContext).relevantNotes(question, 6);
        if (!notes.isEmpty()) {
            c.append("Notes about the user:\n");
            for (Store.Note note : notes) {
                c.append("- ").append(note.text).append('\n');
            }
        }
        return c.append("[/context]\n").toString();
    }

    // ---------------------------------------------------------------- sentences

    /** Cuts the stream into sentences for the voice, as soon as each one is complete. */
    static final class Sentences {
        private static final Pattern END = Pattern.compile("[.!?…:;](?=\\s)|\\n");
        private final StringBuilder mBuffer = new StringBuilder();

        List<String> add(String piece) {
            final List<String> out = new ArrayList<>();
            mBuffer.append(piece);
            while (true) {
                final Matcher m = END.matcher(mBuffer);
                int cut = -1;
                while (m.find()) {
                    // Not after a lone number ("3. November") or a short abbreviation.
                    final int at = m.end();
                    final String before = mBuffer.substring(0, m.start()).trim();
                    if (m.group().equals(".") && before.matches(".*(\\d|\\b\\p{L}{1,2})$")) {
                        continue;
                    }
                    cut = at;
                    break;
                }
                if (cut < 0 && mBuffer.length() > 240) {
                    final int comma = mBuffer.lastIndexOf(", ");
                    cut = comma > 40 ? comma + 1 : -1;
                }
                if (cut < 0) {
                    return out;
                }
                final String s = speakable(mBuffer.substring(0, cut));
                mBuffer.delete(0, cut);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }

        String rest() {
            final String s = speakable(mBuffer.toString());
            mBuffer.setLength(0);
            return s;
        }
    }

    /** What a voice should not read: markdown, links, list marks. */
    static String speakable(String text) {
        return text.replaceAll("https?://\\S+", "")
                .replaceAll("[*_#`>|]", "")
                .replaceAll("(?m)^\\s*[-•]\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** What the screen should not show: markdown marks. */
    static String displayable(String text) {
        return text.replace("**", "").replaceAll("(?m)^#+\\s*", "");
    }
}
