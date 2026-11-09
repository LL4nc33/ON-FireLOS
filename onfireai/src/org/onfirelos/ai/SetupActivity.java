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
import android.content.Intent;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The first start, in three steps instead of a page of settings: a welcome, the language
 * model (found on the home network, or typed in, and checked at once), then listening and
 * the wake word. Everything stays changeable in the settings afterwards.
 */
public class SetupActivity extends Activity {

    /** Ports of the usual OpenAI-compatible servers: Ollama, LM Studio, llama.cpp, vLLM, ... */
    private static final int[] PORTS = { 11434, 1234, 8080, 8000, 5001 };

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private FrameLayout mStage;
    private int mStep;
    private EditText mUrl;
    private EditText mKey;
    private TextView mUrlState;
    private LinearLayout mFound;
    private Button mNext;
    private ExecutorService mScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.background));
        mStage = new FrameLayout(this);
        root.addView(mStage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1));
        setContentView(root);
        show(0);
    }

    @Override
    protected void onDestroy() {
        if (mScan != null) {
            mScan.shutdownNow();
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(CharSequence s, float sp, int color) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        return t;
    }

    /** One step: a column of readable width, centred, that slides in. */
    private LinearLayout page() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        final LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48),
                dp(560));
        outer.addView(col, new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.setPadding(0, dp(48), 0, dp(48));
        scroll.addView(outer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mStage.removeAllViews();
        mStage.addView(scroll);
        scroll.setAlpha(0f);
        scroll.setTranslationX(dp(40));
        scroll.animate().alpha(1f).translationX(0f).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        return col;
    }

    private void heading(LinearLayout col, int step, int title, int line) {
        final TextView dots = text(getString(R.string.setup_step, step, 2), 13, R.color.accent);
        dots.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(dots);
        final TextView t = text(getString(title), 32, R.color.on_background);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
        final TextView l = text(getString(line), 17, R.color.on_background_quiet);
        l.setPadding(0, dp(10), 0, dp(24));
        l.setLineSpacing(dp(3), 1f);
        col.addView(l);
    }

    private EditText field(int hint, String value, int type) {
        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        // A password field falls back to monospace; keep the page's type.
        e.setTypeface(Typeface.DEFAULT);
        e.setBackgroundResource(R.drawable.field_bg);
        e.setPadding(dp(20), 0, dp(20), 0);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(getColor(R.color.on_background));
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)));
        return e;
    }

    private Button pill(int label, boolean primary, View.OnClickListener click) {
        final Button b = new Button(this, null, 0, primary ? R.style.Pill : R.style.Pill_Tonal);
        b.setText(label);
        b.setOnClickListener(click);
        return b;
    }

    private void buttons(LinearLayout col, Button... bs) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        for (int i = 0; i < bs.length; i++) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.setMarginStart(dp(12));
            }
            row.addView(bs[i], lp);
        }
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(32);
        col.addView(row, lp);
    }

    // ---------------------------------------------------------------- the steps

    private void show(int step) {
        mStep = step;
        switch (step) {
            case 0: welcome(); break;
            case 1: model(); break;
            case 2: listening(); break;
            default: done();
        }
    }

    @Override
    public void onBackPressed() {
        if (mStep > 0) {
            show(mStep - 1);
        } else {
            super.onBackPressed();
        }
    }

    private void welcome() {
        final LinearLayout col = page();
        final ImageView spark = new ImageView(this);
        spark.setImageResource(R.drawable.ic_spark);
        spark.setBackgroundResource(R.drawable.status_circle);
        spark.setPadding(dp(22), dp(22), dp(22), dp(22));
        col.addView(spark, new LinearLayout.LayoutParams(dp(88), dp(88)));
        final TextView t = text(getString(R.string.setup_hello), 36, R.color.on_background);
        t.setPadding(0, dp(28), 0, 0);
        col.addView(t);
        final TextView l = text(getString(R.string.setup_hello_line), 18,
                R.color.on_background_quiet);
        l.setPadding(0, dp(12), 0, 0);
        l.setLineSpacing(dp(4), 1f);
        col.addView(l);
        buttons(col, pill(R.string.setup_start, true, v -> show(1)));
    }

    private void model() {
        final LinearLayout col = page();
        heading(col, 1, R.string.setup_model, R.string.setup_model_line);
        mUrl = field(R.string.setup_url_hint, Prefs.string(this, Prefs.URL),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(mUrl);
        mKey = field(R.string.setup_key_hint, Prefs.string(this, Prefs.API_KEY),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ((LinearLayout.LayoutParams) mKey.getLayoutParams()).topMargin = dp(12);
        col.addView(mKey);
        mUrlState = text("", 15, R.color.on_background_quiet);
        mUrlState.setPadding(dp(4), dp(14), 0, 0);
        col.addView(mUrlState);
        mFound = new LinearLayout(this);
        mFound.setOrientation(LinearLayout.VERTICAL);
        col.addView(mFound);
        mNext = pill(R.string.setup_connect, true, v -> connect());
        buttons(col, pill(R.string.setup_search, false, v -> search()), mNext);
        if (Prefs.string(this, Prefs.URL).isEmpty()) {
            search();
        }
    }

    /** Looks for OpenAI-compatible servers on the usual ports of the home network. */
    private void search() {
        if (mScan != null) {
            mScan.shutdownNow();
        }
        mFound.removeAllViews();
        mUrlState.setText(R.string.setup_searching);
        final WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        final int ip = wifi == null ? 0 : wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) {
            mUrlState.setText(R.string.setup_no_wifi);
            return;
        }
        final String prefix = String.format(Locale.ROOT, "%d.%d.%d.", ip & 0xff,
                ip >> 8 & 0xff, ip >> 16 & 0xff);
        final ExecutorService scan = Executors.newFixedThreadPool(48);
        mScan = scan;
        final List<String> found = new ArrayList<>();
        for (int host = 1; host < 255; host++) {
            for (int port : PORTS) {
                final String base = "http://" + prefix + host + ":" + port;
                scan.execute(() -> {
                    final String model = probe(base, 700);
                    if (model != null) {
                        synchronized (found) {
                            found.add(base);
                        }
                        mMain.post(() -> {
                            if (scan == mScan) {
                                addFound(base, model);
                            }
                        });
                    }
                });
            }
        }
        scan.shutdown();
        new Thread(() -> {
            try {
                scan.awaitTermination(40, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            mMain.post(() -> {
                if (scan == mScan && !isDestroyed()) {
                    mUrlState.setText(found.isEmpty() ? R.string.setup_none_found
                            : R.string.setup_pick);
                }
            });
        }).start();
    }

    /** The first model of a server at that address, or null when none answers there. */
    private static String probe(String base, int timeout) {
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL(base + "/v1/models")
                    .openConnection();
            try {
                c.setConnectTimeout(timeout);
                c.setReadTimeout(timeout * 3);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                final JSONArray data = new JSONObject(LlmClient.readAll(c.getInputStream()))
                        .optJSONArray("data");
                return data == null || data.length() == 0 ? ""
                        : data.optJSONObject(0).optString("id");
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void addFound(String base, String model) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.suggestion_bg);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        row.addView(text(base.replace("http://", ""), 16, R.color.on_background));
        if (!model.isEmpty()) {
            row.addView(text(SettingsActivity.shortModel(model), 14, R.color.accent));
        }
        row.setOnClickListener(v -> {
            mUrl.setText(base);
            connect();
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        mFound.addView(row, lp);
    }

    /** Takes the address, checks it at once, and goes on when it answers. */
    private void connect() {
        final String url = mUrl.getText().toString().trim();
        if (url.isEmpty()) {
            mUrlState.setText(R.string.setup_url_missing);
            return;
        }
        Prefs.put(this, Prefs.URL, url.startsWith("http") ? url : "http://" + url);
        Prefs.put(this, Prefs.API_KEY, mKey.getText().toString().trim());
        mUrlState.setText(R.string.status_checking);
        mNext.setEnabled(false);
        final LlmClient client = new LlmClient(Prefs.string(this, Prefs.URL),
                Prefs.string(this, Prefs.API_KEY), Prefs.string(this, Prefs.MODEL));
        new Thread(() -> {
            String error = null;
            String model = "";
            try {
                model = client.model();
            } catch (Exception e) {
                error = LlmClient.describe(this, e);
            }
            final String failed = error;
            final String name = model;
            mMain.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                mNext.setEnabled(true);
                if (failed != null) {
                    mUrlState.setText(getString(R.string.setup_failed, failed));
                    return;
                }
                mUrlState.setText(getString(R.string.setup_connected,
                        SettingsActivity.shortModel(name)));
                mMain.postDelayed(() -> show(2), 700);
            });
        }).start();
    }

    private void listening() {
        final LinearLayout col = page();
        heading(col, 2, R.string.setup_listen, LocalSpeech.available(this)
                ? R.string.setup_listen_local : R.string.setup_listen_line);
        final EditText stt = field(R.string.setup_whisper_hint,
                Prefs.string(this, Prefs.STT_URL),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(stt);
        final TextView sttHelp = text(getString(R.string.setup_whisper_help), 14,
                R.color.on_background_quiet);
        sttHelp.setPadding(dp(4), dp(10), 0, dp(20));
        col.addView(sttHelp);
        // Without the keyword model in this build, the row would only be a dead switch.
        final Switch wake = WakeWord.available() ? new Switch(this) : null;
        if (wake != null) {
            wake.setText(R.string.setup_wake);
            wake.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            wake.setTextColor(getColor(R.color.on_background));
            wake.setChecked(WakeWord.wanted(this));
            col.addView(wake);
            final TextView wakeHelp = text(getString(R.string.setup_wake_help), 14,
                    R.color.on_background_quiet);
            wakeHelp.setPadding(0, dp(6), 0, 0);
            col.addView(wakeHelp);
        }
        buttons(col, pill(R.string.setup_back, false, v -> show(1)),
                pill(R.string.setup_done, true, v -> {
                    final String address = stt.getText().toString().trim();
                    Prefs.put(this, Prefs.STT_URL, address.isEmpty() || address.startsWith("http")
                            ? address : "http://" + address);
                    if (wake != null) {
                        Prefs.put(this, Prefs.WAKE, wake.isChecked());
                        WakeWord.apply(this);
                    }
                    show(3);
                }));
    }

    /** Done: straight into a first conversation. */
    private void done() {
        Prefs.put(this, Prefs.SETUP_DONE, true);
        startActivity(new Intent(this, AssistActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
