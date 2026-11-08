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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assist);
        final View sheet = findViewById(R.id.sheet);
        mScroll = findViewById(R.id.scroll);
        mMessages = findViewById(R.id.messages);
        mChipsScroll = findViewById(R.id.chips_scroll);
        mChips = findViewById(R.id.chips);
        mStatus = findViewById(R.id.status);
        mInput = findViewById(R.id.input);
        mMic = findViewById(R.id.mic);
        mLevel = findViewById(R.id.level);

        // On a wide screen the sheet keeps a readable width.
        final int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(720));
        sheet.setLayoutParams(new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        mScroll.setMaxHeight(getResources().getDisplayMetrics().heightPixels * 45 / 100);

        mSession = Session.get(this);
        findViewById(R.id.root).setOnClickListener(v -> close());
        findViewById(R.id.settings).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            close();
        });
        findViewById(R.id.new_conversation).setOnClickListener(v -> mSession.newConversation());
        mMic.setOnClickListener(v -> {
            if (mSession.state == Session.State.IDLE && !haveMic()) {
                return;
            }
            mSession.micPressed();
        });
        mInput.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                mSession.typing();
            }
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
        // Called again while open: listen (again).
        if (!askShared(intent) && mSession.state != Session.State.LISTENING) {
            listenOnOpen();
        }
    }

    /** A text shared to ON-FireAI (or handed over by another app) is the question. */
    private boolean askShared(Intent intent) {
        final String text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        if (TextUtils.isEmpty(text) || !Prefs.configured(this)) {
            return false;
        }
        intent.removeExtra(Intent.EXTRA_TEXT);
        mSession.submitTyped(text.trim());
        return true;
    }

    private void listenOnOpen() {
        if (!Prefs.configured(this)) {
            if (mSession.items.isEmpty()) {
                mSession.items.add(new Session.Item(Session.SETUP,
                        getString(R.string.not_configured)));
                onSessionChanged(true);
            }
            return;
        }
        if (Prefs.flag(this, Prefs.LISTEN_NOW) && !mSession.busy() && haveMic()) {
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

    private void close() {
        mSession.close();
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
            mLast.setText(last.text);
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
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    void showLevel(float rmsDb) {
        // About -2 (silence) to 10 (loud).
        final float s = 1f + Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f)) * 0.35f;
        mLevel.setScaleX(s);
        mLevel.setScaleY(s);
    }

    private void rebuild() {
        mMessages.removeAllViews();
        mLast = null;
        if (mSession.items.isEmpty()) {
            final TextView t = answerView(getString(R.string.greeting));
            t.setTextColor(getColor(R.color.on_background_quiet));
            return;
        }
        for (Session.Item item : mSession.items) {
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
                default:
                    mLast = answerView(item.text);
            }
        }
    }

    private TextView userView(String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(getColor(R.color.on_background_quiet));
        t.setGravity(Gravity.END);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
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
        setup.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            close();
        });
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

    private void addChip(String text) {
        final TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTextColor(getColor(R.color.on_background));
        chip.setBackgroundResource(R.drawable.chip_bg);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
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
