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

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The sheet that comes up for the assistant: what was heard, the answer as it is written and
 * spoken, chips for what was done, a field to type and the microphone. It shows the Session,
 * which goes on when the sheet is rebuilt or covered.
 */
public class AssistActivity extends Activity implements Session.Ui {

    private static final int REQUEST_MIC = 1;

    private Session mSession;
    private MaxScrollView mScroll;
    private LinearLayout mMessages;
    private HorizontalScrollView mChipsScroll;
    private LinearLayout mChips;
    private TextView mStatus;
    private EditText mInput;
    private ImageButton mMic;
    private View mLevel;
    /** The text view of the last item, updated as it grows. */
    private TextView mLast;
    private int mShownChips = -1;
    private GlowEdge mGlow;
    private View mSpark;
    private android.animation.ObjectAnimator mSparkPulse;
    /** How many items are on screen already; newer ones come in softly. */
    private int mShownItems;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assist);
        final View frame = findViewById(R.id.sheet_frame);
        mScroll = findViewById(R.id.scroll);
        mMessages = findViewById(R.id.messages);
        mChipsScroll = findViewById(R.id.chips_scroll);
        mChips = findViewById(R.id.chips);
        mStatus = findViewById(R.id.status);
        mInput = findViewById(R.id.input);
        mMic = findViewById(R.id.mic);
        mLevel = findViewById(R.id.level);
        mGlow = findViewById(R.id.glow);
        mSpark = findViewById(R.id.spark);

        // On a wide screen the sheet keeps a readable width.
        final int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(720));
        frame.setLayoutParams(new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        mScroll.setMaxHeight(getResources().getDisplayMetrics().heightPixels * 45 / 100);

        mSession = Session.get(this);
        findViewById(R.id.root).setOnClickListener(v -> close());
        findViewById(R.id.settings).setOnClickListener(v -> unlockThen(() -> {
            startActivity(new Intent(this, SettingsActivity.class));
            close();
        }));
        findViewById(R.id.new_conversation).setOnClickListener(v -> mSession.newConversation());
        mMic.setOnClickListener(v -> {
            if (mSession.state == Session.State.IDLE && !haveMic()) {
                return;
            }
            mSession.micPressed();
        });
        // Only a finger on the field means typing. The field also gets the focus on its own
        // when the sheet rebuilds (a heard word appearing), and that must not end listening.
        mInput.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                mSession.typing();
            }
            return false;
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                final String text = mInput.getText().toString().trim();
                if (!text.isEmpty()) {
                    mInput.setText("");
                    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(
                            mInput.getWindowToken(), 0);
                    mSession.submitTyped(text);
                }
                return true;
            }
            return false;
        });

        mSession.attach(this);
        mSession.setVisible(true);
        onSessionChanged(true);
        // Rebuilt (a change of design): the conversation simply goes on.
        if (savedInstanceState == null && !askShared(getIntent())) {
            listenOnOpen();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Called again while open: listen (again). The wake word cuts an answer short.
        if (askShared(intent) || mSession.state == Session.State.LISTENING) {
            return;
        }
        if (Intent.ACTION_VOICE_COMMAND.equals(intent.getAction()) && mSession.busy()
                && haveMic()) {
            // Called from behind another app: this comes before onStart, and listening
            // needs the sheet seen.
            mSession.setVisible(true);
            mSession.bargeIn();
        } else {
            listenOnOpen();
        }
    }

    /**
     * A text shared to ON-FireAI goes into the field, for the user to send: any app can
     * share, and what it shares must not run tools on its own. Only ON-FireAI's own ".Ask"
     * asks at once.
     */
    private boolean askShared(Intent intent) {
        final String text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        if (TextUtils.isEmpty(text) || !Prefs.configured(this)) {
            return false;
        }
        intent.removeExtra(Intent.EXTRA_TEXT);
        final boolean own = intent.getComponent() != null
                && intent.getComponent().getClassName().endsWith(".Ask");
        if (own) {
            mSession.submitTyped(text.trim());
        } else {
            mSession.typing();
            mInput.setText(text.trim());
            mInput.setSelection(mInput.length());
            mInput.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(mInput, 0);
        }
        return true;
    }

    private void listenOnOpen() {
        if (!Prefs.configured(this)) {
            // The very first time: straight into the guided start, unless the tablet is locked.
            if (!Prefs.get(this).getBoolean(Prefs.SETUP_DONE, false) && !Tools.locked(this)) {
                Prefs.put(this, Prefs.SETUP_DONE, true);
                startActivity(new Intent(this, SetupActivity.class));
                close();
                return;
            }
            if (mSession.items.isEmpty()) {
                mSession.items.add(new Session.Item(Session.SETUP,
                        getString(R.string.not_configured)));
                onSessionChanged(true);
            }
            return;
        }
        if (Prefs.flag(this, Prefs.LISTEN_NOW) && !mSession.busy() && mSession.canListen()
                && haveMic()) {
            mSession.startListening(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mSession.setVisible(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) {
            return;
        }
        mSession.setVisible(false);
        // Something else came up (an app a tool opened, home): when nothing is said any
        // more, the sheet is done.
        if (!mSession.busy()) {
            WakeWord.setPaused(false);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        mSession.detach(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    /** At the lock screen the user unlocks first (settings are personal); else at once. */
    private void unlockThen(Runnable then) {
        final android.app.KeyguardManager keyguard =
                getSystemService(android.app.KeyguardManager.class);
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            then.run();
            return;
        }
        keyguard.requestDismissKeyguard(this,
                new android.app.KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        then.run();
                    }
                });
    }

    private void close() {
        mSession.close();
        WakeWord.setPaused(false);
        finish();
    }

    @Override
    public void onSessionClosed() {
        finish();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ---------------------------------------------------------------- showing the session

    @Override
    public void onSessionChanged(boolean whole) {
        if (whole || mLast == null) {
            rebuild();
        } else if (!mSession.items.isEmpty()) {
            final Session.Item last = mSession.items.get(mSession.items.size() - 1);
            if (last.type != Session.CARD) {
                mLast.setText(last.text);
            }
        }
        if (mSession.chips.size() != mShownChips) {
            mChips.removeAllViews();
            for (String chip : mSession.chips) {
                addChip(chip);
            }
            mShownChips = mSession.chips.size();
            mChipsScroll.setVisibility(mShownChips == 0 ? View.GONE : View.VISIBLE);
        }
        mStatus.setText(mSession.status);
        mStatus.setVisibility(TextUtils.isEmpty(mSession.status) ? View.GONE : View.VISIBLE);
        final boolean idle = mSession.state == Session.State.IDLE;
        mMic.setImageResource(idle ? R.drawable.ic_mic : R.drawable.ic_stop);
        if (mSession.state == Session.State.LISTENING) {
            mLevel.animate().alpha(1f).setDuration(150);
        } else {
            mLevel.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(150);
        }
        showActivity();
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** The glowing edge and the spark say what the assistant does, without words. */
    private void showActivity() {
        final int mode;
        switch (mSession.state) {
            case LISTENING: mode = GlowEdge.LISTENING; break;
            case THINKING: mode = GlowEdge.THINKING; break;
            case ANSWERING: mode = GlowEdge.ANSWERING; break;
            default: mode = mSession.busy() ? GlowEdge.ANSWERING : GlowEdge.IDLE;
        }
        mGlow.setMode(mode);
        final boolean thinking = mSession.state == Session.State.THINKING;
        if (thinking && mSparkPulse == null) {
            mSparkPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(mSpark,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f),
                    android.animation.PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 45f));
            mSparkPulse.setDuration(650);
            mSparkPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            mSparkPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            mSparkPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            mSparkPulse.start();
        } else if (!thinking && mSparkPulse != null) {
            mSparkPulse.cancel();
            mSparkPulse = null;
            mSpark.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(200);
        }
    }

    void showLevel(float rmsDb) {
        mGlow.setLevel(rmsDb);
        // About -2 (silence) to 10 (loud).
        final float s = 1f + Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f)) * 0.35f;
        mLevel.setScaleX(s);
        mLevel.setScaleY(s);
    }

    private void rebuild() {
        mMessages.removeAllViews();
        mLast = null;
        if (mSession.items.isEmpty()) {
            mShownItems = 0;
            showWelcome();
            return;
        }
        if (mShownItems > mSession.items.size()) {
            mShownItems = 0;
        }
        for (int i = 0; i < mSession.items.size(); i++) {
            final Session.Item item = mSession.items.get(i);
            final int before = mMessages.getChildCount();
            switch (item.type) {
                case Session.USER:
                    mLast = userView(item.text);
                    break;
                case Session.NOTE:
                    mLast = answerView(item.text);
                    mLast.setTextColor(getColor(R.color.on_background_quiet));
                    break;
                case Session.SETUP:
                    mLast = answerView(item.text);
                    addSetupButton();
                    break;
                case Session.CONFIRM:
                    mLast = answerView(item.text);
                    addConfirmButtons();
                    break;
                case Session.CARD: {
                    final View card = Cards.build(this, item.text);
                    if (card != null) {
                        mMessages.addView(card);
                    }
                    // Not a text to grow: the next change rebuilds.
                    mLast = null;
                    break;
                }
                default:
                    mLast = answerView(item.text);
            }
            if (i >= mShownItems) {
                for (int v = before; v < mMessages.getChildCount(); v++) {
                    comeIn(mMessages.getChildAt(v));
                }
            }
        }
        mShownItems = mSession.items.size();
    }

    /** A new line rises a little and fades in. */
    private void comeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(10));
        v.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
    }

    /** The empty sheet: a greeting for the time of day and a few things to try. */
    private void showWelcome() {
        final int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        final int greeting = hour < 5 ? R.string.greeting_night
                : hour < 11 ? R.string.greeting_morning
                : hour < 18 ? R.string.greeting_day
                : hour < 23 ? R.string.greeting_evening : R.string.greeting_night;
        final TextView hello = new TextView(this);
        hello.setText(greeting);
        hello.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        hello.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        hello.setTextColor(getColor(R.color.on_background));
        final LinearLayout.LayoutParams helloLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helloLp.topMargin = dp(4);
        mMessages.addView(hello, helloLp);
        final TextView sub = new TextView(this);
        sub.setText(R.string.greeting);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sub.setTextColor(getColor(R.color.on_background_quiet));
        mMessages.addView(sub);
        if (!Prefs.configured(this)) {
            comeIn(hello);
            comeIn(sub);
            return;
        }
        final LinearLayout row = new LinearLayout(this);
        final boolean locked = Tools.locked(this);
        final boolean news = !locked && Prefs.get(this).getBoolean(Prefs.NOTIFICATIONS, false)
                && NotificationWatcher.get() != null;
        // At the lock screen nothing personal is on offer.
        final int[][] ideas = {
            { R.string.suggest_weather, R.string.suggest_weather_ask },
            locked ? new int[] { R.string.suggest_time, R.string.suggest_time_ask }
                    : news ? new int[] { R.string.suggest_news, R.string.suggest_news_ask }
                    : new int[] { R.string.suggest_memory, R.string.suggest_memory_ask },
            { R.string.suggest_fact, R.string.suggest_fact_ask },
        };
        for (int[] idea : ideas) {
            final TextView chip = new TextView(this);
            chip.setText(idea[0]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTextColor(getColor(R.color.on_background));
            chip.setBackgroundResource(R.drawable.suggestion_bg);
            chip.setPadding(dp(16), dp(9), dp(16), dp(9));
            final String ask = getString(idea[1]);
            chip.setOnClickListener(v -> mSession.submitTyped(ask));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
        }
        final HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        final LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(16);
        mMessages.addView(scroller, rowLp);
        comeIn(hello);
        comeIn(sub);
        comeIn(scroller);
    }

    /** What the user said or typed: a bubble on the right, as wide as it needs. */
    private TextView userView(String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(getColor(R.color.on_background));
        t.setBackgroundResource(R.drawable.bubble_user);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setMaxWidth(dp(520));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(16);
        lp.setMarginStart(dp(48));
        mMessages.addView(t, lp);
        return t;
    }

    private TextView answerView(String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(getColor(R.color.on_background));
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        t.setDefaultFocusHighlightEnabled(false);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.setMarginEnd(dp(24));
        mMessages.addView(t, lp);
        return t;
    }

    private void addSetupButton() {
        final Button setup = new Button(this, null, 0, R.style.Pill);
        setup.setText(R.string.setup);
        // Not set up yet: the guided first start; else the settings.
        setup.setOnClickListener(v -> unlockThen(() -> {
            startActivity(new Intent(this, Prefs.configured(this) ? SettingsActivity.class
                    : SetupActivity.class));
            close();
        }));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        mMessages.addView(setup, lp);
    }

    private void addConfirmButtons() {
        final LinearLayout row = new LinearLayout(this);
        final Button yes = new Button(this, null, 0, R.style.Pill);
        yes.setText(R.string.yes);
        yes.setOnClickListener(v -> mSession.answerConfirm(true));
        final Button no = new Button(this, null, 0, R.style.Pill_Tonal);
        no.setText(R.string.no);
        no.setOnClickListener(v -> mSession.answerConfirm(false));
        row.addView(yes);
        final LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMarginStart(dp(12));
        row.addView(no, gap);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        mMessages.addView(row, lp);
    }

    /** Something that was done: a tonal chip with a tick. */
    private void addChip(String text) {
        final TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTextColor(getColor(R.color.on_background));
        chip.setBackgroundResource(R.drawable.chip_bg);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0);
        chip.setCompoundDrawablePadding(dp(6));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(7), dp(16), dp(7));
        comeIn(chip);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        mChips.addView(chip, lp);
    }

    // ---------------------------------------------------------------- the microphone

    boolean haveMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        try {
            // The system's assistant: it may give itself the microphone.
            getPackageManager().grantRuntimePermission(getPackageName(),
                    Manifest.permission.RECORD_AUDIO, Process.myUserHandle());
            return true;
        } catch (RuntimeException e) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_MIC);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                mSession.startListening(false);
            } else {
                mStatus.setText(R.string.mic_permission);
                mStatus.setVisibility(View.VISIBLE);
            }
        }
    }
}
