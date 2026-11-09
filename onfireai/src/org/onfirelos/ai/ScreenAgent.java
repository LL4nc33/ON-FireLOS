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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.PixelFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The screen agent: reads what is on screen as a short numbered list and acts on it - tap,
 * type, scroll, back, home - the way one would over adb, but on the tablet itself, for the
 * model to work through a task step by step. Off until the user turns it on; never reads
 * password fields nor the apps on the user's block list.
 */
public class ScreenAgent extends AccessibilityService {

    private static final int MAX_NODES = 160;
    private static volatile ScreenAgent sInstance;

    private final List<AccessibilityNodeInfo> mNodes = new ArrayList<>();
    private String mPackage = "";

    static ScreenAgent get() {
        return sInstance;
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, ScreenAgent.class);
    }

    static boolean enabled(Context context) {
        final String list = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && list.contains(component(context).flattenToString());
    }

    /** Turns the service on or off; ON-FireAI may change secure settings as a system app. */
    static void setEnabled(Context context, boolean on) {
        final String me = component(context).flattenToString();
        String list = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        list = list == null ? "" : list;
        final List<String> parts = new ArrayList<>();
        for (String p : list.split(":")) {
            if (!p.isEmpty() && !p.equals(me)) {
                parts.add(p);
            }
        }
        if (on) {
            parts.add(me);
        }
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", parts));
        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, parts.isEmpty() ? 0 : 1);
    }

    @Override
    protected void onServiceConnected() {
        sInstance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        // Turned off: nothing of it stays on screen or in memory.
        hidePill();
        synchronized (this) {
            mNodes.clear();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------------------------------------------------------- the pill

    private LinearLayout mPill;
    private TextView mPillText;
    private LinearLayout mPillButtons;
    private final Runnable mHidePill = this::hidePill;

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /**
     * Main thread. While ON-FireAI works in another app, a pill at the top says what it does
     * and, at the end, what it found; its cross stops it.
     */
    void showPill(CharSequence text) {
        if (mPill == null) {
            final LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackgroundResource(R.drawable.pill_surface);
            pill.setElevation(dp(6));
            pill.setPadding(dp(16), dp(6), dp(6), dp(6));
            final ImageView spark = new ImageView(this);
            spark.setImageResource(R.drawable.ic_spark);
            pill.addView(spark, new LinearLayout.LayoutParams(dp(20), dp(20)));
            mPillText = new TextView(this);
            mPillText.setTextColor(getColor(R.color.on_background));
            mPillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mPillText.setMaxLines(3);
            mPillText.setMaxWidth(dp(560));
            final LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textLp.setMarginStart(dp(10));
            textLp.setMarginEnd(dp(4));
            pill.addView(mPillText, textLp);
            mPillButtons = new LinearLayout(this);
            mPillButtons.setVisibility(View.GONE);
            pill.addView(mPillButtons);
            final ImageButton stop = new ImageButton(this);
            stop.setImageResource(R.drawable.ic_close);
            stop.setBackgroundResource(android.R.drawable.list_selector_background);
            stop.setContentDescription(getString(R.string.stop));
            stop.setOnClickListener(v -> {
                hidePill();
                Session.get(this).stopFromPill();
            });
            pill.addView(stop, new LinearLayout.LayoutParams(dp(40), dp(40)));
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(32);
            try {
                getSystemService(WindowManager.class).addView(pill, lp);
            } catch (RuntimeException e) {
                // No window for it now (the service is going away): no pill then.
                return;
            }
            mPill = pill;
            // It glides in from above rather than popping up.
            pill.setAlpha(0f);
            pill.setTranslationY(-dp(16));
            pill.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        }
        mPillText.setText(text);
        mPillButtons.removeAllViews();
        mPillButtons.setVisibility(View.GONE);
        mPill.removeCallbacks(mHidePill);
    }

    /**
     * Main thread. A question before a risky step while the sheet is away: yes and no on the
     * pill, so it can be answered where the work goes on.
     */
    void showConfirm(CharSequence question, java.util.function.Consumer<Boolean> answer) {
        showPill(question);
        for (boolean yes : new boolean[] { true, false }) {
            final android.widget.Button b = new android.widget.Button(this, null, 0,
                    yes ? R.style.Pill : R.style.Pill_Tonal);
            b.setText(yes ? R.string.yes : R.string.no);
            b.setOnClickListener(v -> {
                mPillButtons.removeAllViews();
                mPillButtons.setVisibility(View.GONE);
                answer.accept(yes);
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            mPillButtons.addView(b, lp);
        }
        mPillButtons.setVisibility(View.VISIBLE);
    }

    /** Main thread. The question is over without an answer from the pill. */
    void clearConfirm() {
        if (mPillButtons != null && mPillButtons.getChildCount() > 0) {
            mPillButtons.removeAllViews();
            mPillButtons.setVisibility(View.GONE);
            hidePillLater(1500);
        }
    }

    void hidePillLater(long ms) {
        if (mPill != null) {
            mPill.removeCallbacks(mHidePill);
            mPill.postDelayed(mHidePill, ms);
        }
    }

    void hidePill() {
        if (mPill != null) {
            mPill.removeCallbacks(mHidePill);
            try {
                getSystemService(WindowManager.class).removeView(mPill);
            } catch (RuntimeException ignored) {
                // Already gone with the service's window token.
            }
            mPill = null;
            mPillText = null;
            mPillButtons = null;
        }
    }

    // ---------------------------------------------------------------- reading

    /** The app's window under the sheet: the topmost application window that is not ours. */
    private AccessibilityNodeInfo appRoot() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            final AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null
                    && !getPackageName().contentEquals(root.getPackageName())) {
                return root;
            }
        }
        return getRootInActiveWindow();
    }

    /** What is on screen, one line per element worth knowing, numbered for the actions. */
    synchronized String read(List<String> blocked) {
        mNodes.clear();
        mPackage = "";
        final AccessibilityNodeInfo root = appRoot();
        if (root == null) {
            return "error: no screen to read";
        }
        mPackage = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (blocked.contains(mPackage)) {
            final String app = mPackage;
            // Nothing may act on it either.
            mPackage = "";
            return "error: the user does not allow reading " + app;
        }
        final StringBuilder out = new StringBuilder("app: ").append(mPackage).append('\n');
        walk(root, 0, out);
        if (mNodes.size() >= MAX_NODES) {
            out.append("(more below; scroll to see it)\n");
        }
        return out.toString();
    }

    private void walk(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        if (node == null || mNodes.size() >= MAX_NODES || !node.isVisibleToUser()) {
            return;
        }
        final CharSequence text = node.isPassword() ? "(password)" : node.getText();
        final CharSequence desc = node.getContentDescription();
        final boolean actionable = node.isClickable() || node.isEditable()
                || node.isScrollable() || node.isCheckable() || node.isLongClickable();
        final boolean says = !TextUtils.isEmpty(text) || !TextUtils.isEmpty(desc);
        if (actionable || says) {
            final int n = mNodes.size();
            mNodes.add(node);
            out.append("[").append(n).append("] ").append(kind(node));
            if (!TextUtils.isEmpty(text)) {
                out.append(" \"").append(shorten(text)).append('"');
            }
            if (!TextUtils.isEmpty(desc) && !TextUtils.equals(desc, text)) {
                out.append(" (").append(shorten(desc)).append(')');
            }
            if (node.isCheckable()) {
                out.append(node.isChecked() ? " [on]" : " [off]");
            }
            if (node.isEditable()) {
                out.append(" editable");
            }
            if (node.isScrollable()) {
                out.append(" scrollable");
            }
            if (!node.isEnabled()) {
                out.append(" disabled");
            }
            out.append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), depth + 1, out);
        }
    }

    private static String kind(AccessibilityNodeInfo node) {
        final CharSequence cls = node.getClassName();
        String k = cls == null ? "View" : cls.toString();
        k = k.substring(k.lastIndexOf('.') + 1);
        if (node.isEditable()) {
            return "Field";
        }
        if (k.contains("Button")) {
            return "Button";
        }
        if (k.contains("Switch") || k.contains("CheckBox")) {
            return "Switch";
        }
        if (k.contains("Image")) {
            return node.isClickable() ? "Button" : "Image";
        }
        if (k.contains("Text")) {
            return node.isClickable() ? "Link" : "Text";
        }
        return node.isClickable() ? "Item" : k;
    }

    private static String shorten(CharSequence s) {
        final String t = s.toString().replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    // ---------------------------------------------------------------- acting

    private AccessibilityNodeInfo node(int n) {
        return n >= 0 && n < mNodes.size() ? mNodes.get(n) : null;
    }

    /**
     * What a tap on an element would hit, for the safety check before tapping: the clickable
     * row it belongs to with all the text in it ("Buy" can sit in a child of the row).
     */
    synchronized String label(int n) {
        AccessibilityNodeInfo node = node(n);
        if (node == null) {
            return "";
        }
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        final StringBuilder out = new StringBuilder();
        collect(target != null ? target : node, 0, out);
        return out.toString().trim();
    }

    private static void collect(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        if (node == null || depth > 7 || out.length() > 1000) {
            return;
        }
        final CharSequence t = node.isPassword() ? null : node.getText();
        final CharSequence d = node.getContentDescription();
        if (!TextUtils.isEmpty(t)) {
            out.append(t).append(' ');
        }
        if (!TextUtils.isEmpty(d) && !TextUtils.equals(d, t)) {
            out.append(d).append(' ');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), depth + 1, out);
        }
    }

    /**
     * Before acting: the app read is still the one on screen. Another app (perhaps one on the
     * block list) may have come up since, and the old elements would act on it blindly.
     */
    private String changed() {
        final AccessibilityNodeInfo root = appRoot();
        final CharSequence now = root == null ? null : root.getPackageName();
        if (mPackage.isEmpty() || now == null || !mPackage.contentEquals(now)) {
            mNodes.clear();
            return "error: the screen changed; read it again";
        }
        return null;
    }

    synchronized String tap(int n) {
        final String changed = changed();
        if (changed != null) {
            return changed;
        }
        AccessibilityNodeInfo node = node(n);
        if (node == null) {
            return "error: no element " + n + "; read the screen again";
        }
        // The text itself is often not clickable, its row is.
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            settle();
            return "tapped [" + n + "]";
        }
        final Rect r = new Rect();
        node.getBoundsInScreen(r);
        return gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 60)
                ? "tapped [" + n + "] by position" : "error: could not tap [" + n + "]";
    }

    synchronized String type(int n, String text) {
        final String changed = changed();
        if (changed != null) {
            return changed;
        }
        final AccessibilityNodeInfo node = node(n);
        if (node == null || !node.isEditable()) {
            return "error: [" + n + "] is not a field";
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        final Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        final boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        settle();
        return ok ? "typed into [" + n + "]" : "error: could not type into [" + n + "]";
    }

    synchronized String scroll(int n, boolean down) {
        final String changed = changed();
        if (changed != null) {
            return changed;
        }
        AccessibilityNodeInfo node = node(n);
        if (node == null) {
            // The first thing on screen that scrolls.
            for (AccessibilityNodeInfo candidate : mNodes) {
                if (candidate.isScrollable()) {
                    node = candidate;
                    break;
                }
            }
        }
        if (node != null && node.performAction(down
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            settle();
            return "scrolled " + (down ? "down" : "up");
        }
        // Nothing says it scrolls: swipe over the middle of the screen.
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        return gesture(w / 2, down ? h * 3 / 5 : h / 4, w / 2, down ? h / 4 : h * 3 / 5, 300)
                ? "swiped " + (down ? "down" : "up") : "error: could not scroll";
    }

    synchronized String global(String what) {
        final int action;
        switch (what.toLowerCase(Locale.ROOT)) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            default: return "error: unknown " + what;
        }
        final boolean ok = performGlobalAction(action);
        settle();
        return ok ? what + " done" : "error: " + what;
    }

    /**
     * A web video opened by the assistant starts muted (YouTube does so without a touch):
     * once the page is there, one real tap on the player turns the sound on. Worker thread.
     */
    void unmuteWebVideo() {
        for (int attempt = 0; attempt < 12; attempt++) {
            SystemClock.sleep(1000);
            final AccessibilityNodeInfo root = getRootInActiveWindow();
            // Only the browser, where the video was opened; anything else in front is left be.
            if (root == null || root.getPackageName() == null
                    || !root.getPackageName().toString().startsWith("org.lineageos.jelly")) {
                continue;
            }
            final AccessibilityNodeInfo web = findWebView(root);
            if (web == null) {
                continue;
            }
            final android.graphics.Rect r = new android.graphics.Rect();
            web.getBoundsInScreen(r);
            if (r.width() <= 0) {
                continue;
            }
            // The player sits at the top of the page, 16:9 over the full width.
            SystemClock.sleep(2500);
            final int x = r.centerX();
            final int y = r.top + r.width() * 9 / 32;
            gesture(x, y, x, y, 60);
            return;
        }
    }

    private static AccessibilityNodeInfo findWebView(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.getClassName() != null && node.getClassName().toString().contains("WebView")) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final AccessibilityNodeInfo found = findWebView(node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean gesture(int x1, int y1, int x2, int y2, long ms) {
        final Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        final boolean ok = dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build(),
                null, null);
        settle();
        return ok;
    }

    /** Lets the app react before the next look. */
    private static void settle() {
        SystemClock.sleep(700);
    }
}
