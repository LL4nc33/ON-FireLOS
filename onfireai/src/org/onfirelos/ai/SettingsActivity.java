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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Settings > System > ON-FireAI: the language model, the voice, how it starts, the memory. */
public class SettingsActivity extends Activity {

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Page mPage;
    private TextView mUrl;
    private TextView mKey;
    private TextView mModel;
    private TextView mTest;
    private TextView mSearch;
    private TextView mBlocked;
    private TextView mEmbed;
    private TextView mWakeWord;
    private TextView mStt;
    private TextView mSttUrl;
    private TextView mSttModel;
    private TextView mMemory;
    private Switch mAssistant;
    private Page.Status mStatus;
    /** Counts the checks, so an old answer does not overwrite a newer one. */
    private int mCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPage = new Page(this, getString(R.string.app_name));

        mStatus = mPage.status(R.drawable.ic_spark);
        final android.widget.Button tryIt = new android.widget.Button(this, null, 0,
                R.style.Pill);
        tryIt.setText(R.string.status_try);
        tryIt.setOnClickListener(v -> startActivity(new Intent(this, AssistActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        mStatus.actions.addView(tryIt);

        mPage.section(R.string.section_model);
        mUrl = mPage.row(getString(R.string.pref_url), "", true, v -> editUrl());
        mKey = mPage.row(getString(R.string.pref_key), "", true, v -> editKey());
        mModel = mPage.row(getString(R.string.pref_model), "", true, v -> pickModel());
        mTest = mPage.row(getString(R.string.test_connection), "", false, v -> test());

        mPage.section(R.string.section_voice);
        mPage.toggle(getString(R.string.pref_speak), getString(R.string.pref_speak_summary),
                Prefs.flag(this, Prefs.SPEAK), (b, on) -> Prefs.put(this, Prefs.SPEAK, on));
        mPage.toggle(getString(R.string.pref_followup), getString(R.string.pref_followup_summary),
                Prefs.flag(this, Prefs.FOLLOW_UP), (b, on) -> Prefs.put(this, Prefs.FOLLOW_UP, on));
        mPage.toggle(getString(R.string.pref_listen_now),
                getString(R.string.pref_listen_now_summary), Prefs.flag(this, Prefs.LISTEN_NOW),
                (b, on) -> Prefs.put(this, Prefs.LISTEN_NOW, on));
        mPage.row(getString(R.string.pref_tts), getString(R.string.pref_tts_summary), false,
                v -> startActivity(new Intent("com.android.settings.TTS_SETTINGS")));

        mPage.section(R.string.section_listen);
        mStt = mPage.row(getString(R.string.pref_stt), "", true, v -> pickStt());
        mSttUrl = mPage.row(getString(R.string.pref_stt_url), "", true, v -> ask(
                R.string.pref_stt_url, getString(R.string.pref_stt_url_help),
                Prefs.string(this, Prefs.STT_URL),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
                    Prefs.put(this, Prefs.STT_URL, text);
                    refresh();
                }));
        mSttModel = mPage.row(getString(R.string.pref_stt_model), "", true, v -> ask(
                R.string.pref_stt_model, null, Prefs.string(this, Prefs.STT_MODEL),
                InputType.TYPE_CLASS_TEXT, text -> {
                    Prefs.put(this, Prefs.STT_MODEL, text);
                    refresh();
                }));

        mPage.section(R.string.section_wake);
        mPage.toggle(getString(R.string.pref_wake), getString(
                WakeWord.available() ? R.string.pref_wake_summary : R.string.pref_wake_missing),
                WakeWord.wanted(this), (b, on) -> {
                    Prefs.put(this, Prefs.WAKE, on);
                    WakeWord.apply(this);
                });
        mWakeWord = mPage.row(getString(R.string.pref_wake_word), "", true, v -> pickWakeWord());
        mPage.toggle(getString(R.string.pref_wake_charging),
                getString(R.string.pref_wake_charging_summary),
                Prefs.get(this).getBoolean(Prefs.WAKE_CHARGING_ONLY, true), (b, on) -> {
                    Prefs.put(this, Prefs.WAKE_CHARGING_ONLY, on);
                });

        mPage.section(R.string.section_start);
        mAssistant = mPage.toggle(getString(R.string.pref_assistant), "", isAssistant(this),
                (b, on) -> setAssistant(this, on));

        mPage.section(R.string.section_screen);
        mPage.toggle(getString(R.string.pref_screen), getString(R.string.pref_screen_summary),
                Prefs.get(this).getBoolean(Prefs.SCREEN, false) && ScreenAgent.enabled(this),
                (b, on) -> {
                    Prefs.put(this, Prefs.SCREEN, on);
                    ScreenAgent.setEnabled(this, on);
                });
        mBlocked = mPage.row(getString(R.string.pref_screen_blocked), "", false,
                v -> pickBlocked());
        mPage.toggle(getString(R.string.pref_notifications),
                getString(R.string.pref_notifications_summary),
                Prefs.get(this).getBoolean(Prefs.NOTIFICATIONS, false)
                        && NotificationWatcher.enabled(this),
                (b, on) -> {
                    Prefs.put(this, Prefs.NOTIFICATIONS, on);
                    if (on && !NotificationWatcher.enabled(this)) {
                        // Only the user may grant it, on Android's own page.
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    }
                });

        mPage.section(R.string.section_tools);
        mSearch = mPage.row(getString(R.string.pref_search), "", true, v -> editSearch());

        mPage.section(R.string.section_memory);
        mMemory = mPage.row(getString(R.string.pref_memory), "", false,
                v -> startActivity(new Intent(this, MemoryActivity.class)));
        mEmbed = mPage.row(getString(R.string.pref_embed), "", true, v -> ask(
                R.string.pref_embed, getString(R.string.pref_embed_help),
                Prefs.string(this, Prefs.EMBED_URL),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
                    Prefs.put(this, Prefs.EMBED_URL, text);
                    Embeddings.reset();
                    refresh();
                }));
        mPage.toggle(getString(R.string.pref_learn), getString(R.string.pref_learn_summary),
                Prefs.flag(this, Prefs.LEARN), (b, on) -> Prefs.put(this, Prefs.LEARN, on));
        mPage.toggle(getString(R.string.pref_keep_log), getString(R.string.pref_keep_log_summary),
                Prefs.flag(this, Prefs.KEEP_LOG), (b, on) -> Prefs.put(this, Prefs.KEEP_LOG, on));

        // MCP servers: each one's tools are lent to the model.
        mPage.section(R.string.section_mcp);
        final java.util.List<Mcp.Server> servers = Mcp.servers(this);
        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            final Mcp.Server server = servers.get(i);
            final TextView summary = mPage.row(server.url,
                    getString(server.on ? R.string.testing : R.string.mcp_off), server.on,
                    v -> editServer(index));
            if (server.on) {
                countTools(server, index + 1, summary);
            }
        }
        mPage.row(getString(R.string.mcp_add), "", false, v -> editServer(-1));
    }

    private void countTools(Mcp.Server server, int index, TextView summary) {
        new Thread(() -> {
            String text;
            boolean ok;
            try {
                text = getString(R.string.mcp_tools, Mcp.tools(server, index).size());
                ok = true;
            } catch (Exception e) {
                text = getString(R.string.mcp_error, LlmClient.describe(this, e));
                ok = false;
            }
            final String shown = text;
            final boolean good = ok;
            mMain.post(() -> {
                Page.setSummary(summary, shown);
                summary.setTextColor(getColor(good ? R.color.accent
                        : R.color.on_background_quiet));
            });
        }).start();
    }

    /** Adds (index -1) or changes a server: its address, its key, on/off, remove. */
    private void editServer(int index) {
        final java.util.List<Mcp.Server> servers = Mcp.servers(this);
        final Mcp.Server server = index >= 0 ? servers.get(index) : new Mcp.Server();
        final EditText url = new EditText(this);
        url.setHint("http://server:8000/mcp");
        url.setText(server.url);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText key = new EditText(this);
        key.setHint(R.string.mcp_key);
        key.setText(server.key);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Switch on = new Switch(this);
        on.setText(R.string.mcp_on);
        on.setChecked(server.on);
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(mPage.dp(24), mPage.dp(8), mPage.dp(24), 0);
        box.addView(url);
        box.addView(key);
        if (index >= 0) {
            box.addView(on);
        }
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? R.string.section_mcp : R.string.mcp_add)
                .setMessage(R.string.mcp_add_help)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    server.url = url.getText().toString().trim();
                    server.key = key.getText().toString().trim();
                    server.on = index < 0 || on.isChecked();
                    if (index < 0 && !server.url.isEmpty()) {
                        servers.add(server);
                    } else if (index >= 0 && server.url.isEmpty()) {
                        servers.remove(index);
                    }
                    Mcp.save(this, servers);
                    recreate();
                });
        if (index >= 0) {
            dialog.setNeutralButton(R.string.mcp_remove, (d, w) -> {
                servers.remove(index);
                Mcp.save(this, servers);
                recreate();
            });
        }
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        checkStatus();
    }

    /** The card at the top: ready (and with which model), or what is missing. */
    private void checkStatus() {
        final int check = ++mCheck;
        final String wake = WakeWord.wanted(this) && WakeWord.available()
                ? getString(R.string.status_wake, Prefs.get(this).getString(Prefs.WAKE_WORD,
                        WakeWord.PRESETS[0][0]))
                : getString(R.string.status_no_wake);
        if (!Prefs.configured(this)) {
            mStatus.title.setText(R.string.status_setup);
            mStatus.line.setText(R.string.status_setup_line);
            mStatus.actions.setVisibility(android.view.View.GONE);
            return;
        }
        mStatus.title.setText(R.string.status_checking);
        mStatus.line.setText(wake);
        mStatus.actions.setVisibility(android.view.View.VISIBLE);
        final LlmClient client = client();
        new Thread(() -> {
            String title;
            String line;
            try {
                final String model = client.model();
                title = getString(R.string.status_ready);
                line = (model.isEmpty() ? "" : shortModel(model) + " · ") + wake;
            } catch (Exception e) {
                title = getString(R.string.status_offline);
                line = LlmClient.describe(this, e);
            }
            final String t = title;
            final String l = line;
            mMain.post(() -> {
                if (check == mCheck && !isDestroyed()) {
                    mStatus.title.setText(t);
                    mStatus.line.setText(l);
                }
            });
        }).start();
    }

    /** "Qwen3.8-27B-UD-Q4_K_M.gguf" reads as "Qwen3.8-27B". */
    static String shortModel(String model) {
        String m = model.replaceAll("(?i)\\.gguf$", "");
        m = m.substring(m.lastIndexOf('/') + 1);
        m = m.replaceAll("(?i)[-_.](UD-)?(Q\\d.*|IQ\\d.*|F16|BF16|FP16|GGUF)$", "");
        return m.isEmpty() ? model : m;
    }

    private void refresh() {
        final String url = Prefs.string(this, Prefs.URL);
        Page.setSummary(mUrl, url.isEmpty() ? getString(R.string.pref_url_empty) : url);
        Page.setSummary(mKey, getString(Prefs.string(this, Prefs.API_KEY).isEmpty()
                ? R.string.key_none : R.string.key_set));
        final String model = Prefs.string(this, Prefs.MODEL);
        Page.setSummary(mModel, model.isEmpty() ? getString(R.string.model_auto) : model);
        final String mode = Prefs.get(this).getString(Prefs.STT_MODE, "auto");
        Page.setSummary(mStt, getString("server".equals(mode) ? R.string.stt_server
                : "google".equals(mode) ? R.string.stt_google : R.string.stt_auto));
        final String sttUrl = Prefs.string(this, Prefs.STT_URL);
        Page.setSummary(mSttUrl, sttUrl.isEmpty() ? getString(R.string.pref_stt_url_empty)
                : sttUrl);
        final String sttModel = Prefs.string(this, Prefs.STT_MODEL);
        Page.setSummary(mSttModel, sttModel.isEmpty() ? getString(R.string.stt_model_default)
                : sttModel);
        Page.setSummary(mWakeWord, Prefs.get(this).getString(Prefs.WAKE_WORD,
                WakeWord.PRESETS[0][0]));
        final String embed = Prefs.string(this, Prefs.EMBED_URL);
        Page.setSummary(mEmbed, embed.isEmpty() ? getString(R.string.pref_embed_empty) : embed);
        final int blocked = Tools.blocked(this).size();
        Page.setSummary(mBlocked, blocked == 0 ? getString(R.string.pref_screen_blocked_none)
                : getString(R.string.pref_screen_blocked_some, blocked));
        final String search = Prefs.string(this, Prefs.SEARCH_URL);
        Page.setSummary(mSearch, search.isEmpty() ? getString(R.string.pref_search_empty)
                : search);
        final Store store = Store.get(this);
        final int notes = store.notes().size();
        final int days = store.days().size();
        Page.setSummary(mMemory, getResources().getQuantityString(R.plurals.memory_notes, notes,
                notes) + ", " + getResources().getQuantityString(R.plurals.memory_days, days,
                days));
        final boolean assistant = isAssistant(this);
        if (mAssistant.isChecked() != assistant) {
            mAssistant.setChecked(assistant);
        }
    }

    // ---------------------------------------------------------------- the assistant

    static ComponentName component(Context context) {
        return new ComponentName(context, AssistActivity.class);
    }

    static boolean isAssistant(Context context) {
        final String current = Settings.Secure.getString(context.getContentResolver(),
                "assistant");
        return component(context).flattenToString().equals(current);
    }

    static void setAssistant(Context context, boolean on) {
        Settings.Secure.putString(context.getContentResolver(), "assistant",
                on ? component(context).flattenToString() : "");
        Prefs.put(context, Prefs.ASSISTANT_SET, true);
    }

    // ---------------------------------------------------------------- dialogs

    private interface OnText {
        void done(String text);
    }

    private void ask(int title, String message, String value, int inputType, OnText done) {
        final EditText field = new EditText(this);
        field.setText(value);
        field.setSelection(field.getText().length());
        field.setInputType(inputType);
        field.setSingleLine(true);
        final FrameLayout box = new FrameLayout(this);
        box.setPadding(mPage.dp(24), mPage.dp(8), mPage.dp(24), 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> done.done(field.getText().toString()))
                .show();
        field.requestFocus();
    }

    private void editUrl() {
        ask(R.string.pref_url, getString(R.string.pref_url_help), Prefs.string(this, Prefs.URL),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
                    Prefs.put(this, Prefs.URL, text);
                    refresh();
                    if (!text.trim().isEmpty()) {
                        test();
                    }
                });
    }

    private void editKey() {
        ask(R.string.pref_key, null, Prefs.string(this, Prefs.API_KEY),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, text -> {
                    Prefs.put(this, Prefs.API_KEY, text);
                    refresh();
                });
    }

    private void editSearch() {
        ask(R.string.pref_search, getString(R.string.pref_search_help),
                Prefs.string(this, Prefs.SEARCH_URL),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
                    Prefs.put(this, Prefs.SEARCH_URL, text);
                    refresh();
                });
    }

    /** The wake word: one of the tried ones, or one's own (English sounds work best). */
    private void pickWakeWord() {
        final String[] names = new String[WakeWord.PRESETS.length + 1];
        for (int i = 0; i < WakeWord.PRESETS.length; i++) {
            names[i] = WakeWord.PRESETS[i][0];
        }
        names[names.length - 1] = getString(R.string.wake_own);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pref_wake_word)
                .setItems(names, (d, which) -> {
                    if (which < WakeWord.PRESETS.length) {
                        setWakeWord(names[which]);
                    } else {
                        ask(R.string.wake_own, getString(R.string.wake_own_help),
                                Prefs.get(this).getString(Prefs.WAKE_WORD, ""),
                                InputType.TYPE_CLASS_TEXT, this::setWakeWord);
                    }
                })
                .show();
    }

    private void setWakeWord(String word) {
        if (word.trim().isEmpty()) {
            return;
        }
        Prefs.get(this).edit().putString(Prefs.WAKE_WORD, word.trim()).apply();
        // The service picks the new word up when it starts again.
        stopService(new Intent(this, WakeWord.class));
        WakeWord.apply(this);
        refresh();
    }

    /** The apps the screen agent never reads, picked from the installed ones. */
    private void pickBlocked() {
        final android.content.pm.PackageManager pm = getPackageManager();
        final java.util.List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.sort((a, b) -> a.loadLabel(pm).toString()
                .compareToIgnoreCase(b.loadLabel(pm).toString()));
        final java.util.List<String> blocked = Tools.blocked(this);
        final String[] names = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).loadLabel(pm).toString();
            checked[i] = blocked.contains(apps.get(i).activityInfo.packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pref_screen_blocked)
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final java.util.List<String> list = new java.util.ArrayList<>();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) {
                            list.add(apps.get(i).activityInfo.packageName);
                        }
                    }
                    Prefs.put(this, Prefs.SCREEN_BLOCKED, android.text.TextUtils.join(",", list));
                    refresh();
                })
                .show();
    }

    private void pickStt() {
        final String[] modes = { "auto", "google", "server" };
        final String[] names = { getString(R.string.stt_auto), getString(R.string.stt_google),
                getString(R.string.stt_server) };
        final String current = Prefs.get(this).getString(Prefs.STT_MODE, "auto");
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pref_stt)
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    Prefs.get(this).edit().putString(Prefs.STT_MODE, modes[which]).apply();
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private LlmClient client() {
        return new LlmClient(Prefs.string(this, Prefs.URL), Prefs.string(this, Prefs.API_KEY),
                Prefs.string(this, Prefs.MODEL));
    }

    private void pickModel() {
        if (!Prefs.configured(this)) {
            editUrl();
            return;
        }
        Page.setSummary(mModel, getString(R.string.testing));
        new Thread(() -> {
            String[] names;
            try {
                final JSONArray models = client().models();
                names = new String[models.length() + 1];
                names[0] = getString(R.string.model_auto);
                for (int i = 0; i < models.length(); i++) {
                    names[i + 1] = models.getJSONObject(i).optString("id");
                }
            } catch (Exception e) {
                names = null;
                final String why = e.getMessage();
                mMain.post(() -> Page.setSummary(mModel, getString(R.string.test_fail, why)));
                return;
            }
            final String[] list = names;
            mMain.post(() -> {
                if (isFinishing()) {
                    return;
                }
                refresh();
                new AlertDialog.Builder(this)
                        .setTitle(R.string.pref_model)
                        .setItems(list, (d, which) -> {
                            Prefs.put(this, Prefs.MODEL, which == 0 ? "" : list[which]);
                            refresh();
                        })
                        .show();
            });
        }).start();
    }

    /** Asks the model for a word and shows how long the whole answer took. */
    private void test() {
        if (!Prefs.configured(this)) {
            editUrl();
            return;
        }
        Page.setSummary(mTest, getString(R.string.testing));
        mTest.setTextColor(getColor(R.color.on_background_quiet));
        final LlmClient client = client();
        new Thread(() -> {
            final long start = SystemClock.elapsedRealtime();
            String result;
            boolean ok;
            try {
                final JSONArray messages = new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "Reply with the single word: OK"));
                client.chat(messages, null, null);
                result = getString(R.string.test_ok, SystemClock.elapsedRealtime() - start,
                        client.model());
                ok = true;
            } catch (Exception e) {
                result = getString(R.string.test_fail, LlmClient.describe(this, e));
                ok = false;
            }
            final String text = result;
            final boolean good = ok;
            mMain.post(() -> {
                Page.setSummary(mTest, text);
                mTest.setTextColor(getColor(good ? R.color.accent : R.color.on_background_quiet));
            });
        }).start();
    }
}
