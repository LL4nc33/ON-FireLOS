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
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * A settings page in the system's design (docs/DESIGN.md) without a preference library: the
 * slim bar with the back arrow, the large title, section names in the accent, rows on
 * rounded cards with fine lines between them, values in the accent.
 */
final class Page {

    private final Activity mActivity;
    private final LinearLayout mColumn;
    private LinearLayout mCard;

    Page(Activity activity, CharSequence title) {
        mActivity = activity;
        final ScrollView scroll = new ScrollView(activity);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);
        final LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final ImageButton back = new ImageButton(activity);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(resolve(android.R.attr.selectableItemBackgroundBorderless));
        back.setContentDescription(activity.getString(android.R.string.cancel));
        back.setOnClickListener(v -> activity.onBackPressed());
        final LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        backLp.setMarginStart(dp(4));
        page.addView(back, backLp);

        // On a wide screen the column keeps a readable width, centred.
        final int width = activity.getResources().getDisplayMetrics().widthPixels;
        final int side = Math.max(dp(16), (width - dp(760)) / 2);
        mColumn = new LinearLayout(activity);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        mColumn.setPadding(side, 0, side, dp(32));
        page.addView(mColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView large = text(title, 34, R.color.on_background);
        large.setPadding(dp(8), dp(40), dp(8), dp(20));
        mColumn.addView(large);
        activity.setContentView(scroll);
    }

    private int resolve(int attr) {
        final TypedValue v = new TypedValue();
        mActivity.getTheme().resolveAttribute(attr, v, true);
        return v.resourceId;
    }

    int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                mActivity.getResources().getDisplayMetrics()));
    }

    TextView text(CharSequence s, int sp, int color) {
        final TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(mActivity.getColor(color));
        return t;
    }

    /** A section: its name in the accent above a new card. */
    void section(int title) {
        final TextView t = text(mActivity.getString(title), 14, R.color.accent);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(20), dp(mCard == null ? 4 : 20), dp(20), dp(8));
        mColumn.addView(t);
        newCard();
    }

    void newCard() {
        mCard = new LinearLayout(mActivity);
        mCard.setOrientation(LinearLayout.VERTICAL);
        mCard.setBackgroundResource(R.drawable.card_bg);
        mCard.setClipToOutline(true);
        mColumn.addView(mCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Free space for a view of one's own under the cards (a pill, a note). */
    void add(View view) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        mColumn.addView(view, lp);
    }

    private LinearLayout rowShell(View.OnClickListener click) {
        if (mCard.getChildCount() > 0) {
            final View line = new View(mActivity);
            line.setBackgroundColor(mActivity.getColor(R.color.divider));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2 + 1));
            lp.setMarginStart(dp(20));
            lp.setMarginEnd(dp(20));
            mCard.addView(line, lp);
        }
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        if (click != null) {
            row.setBackgroundResource(resolve(android.R.attr.selectableItemBackground));
            row.setOnClickListener(click);
        }
        mCard.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** A row; returns its second line to update. A value shows in the accent. */
    TextView row(CharSequence title, CharSequence summary, boolean summaryIsValue,
            View.OnClickListener click) {
        final LinearLayout row = rowShell(click);
        final LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(text(title, 18, R.color.on_background));
        final TextView second = text(summary, 14,
                summaryIsValue ? R.color.accent : R.color.on_background_quiet);
        second.setPadding(0, dp(2), 0, 0);
        second.setMaxLines(4);
        second.setEllipsize(TextUtils.TruncateAt.END);
        second.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
        texts.addView(second);
        return second;
    }

    static void setSummary(TextView second, CharSequence summary) {
        second.setText(summary);
        second.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
    }

    /** A row with a switch at its end. */
    Switch toggle(CharSequence title, CharSequence summary, boolean on,
            CompoundButton.OnCheckedChangeListener change) {
        final Switch sw = new Switch(mActivity);
        final LinearLayout row = rowShell(v -> sw.toggle());
        final LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(text(title, 18, R.color.on_background));
        if (!TextUtils.isEmpty(summary)) {
            final TextView second = text(summary, 14, R.color.on_background_quiet);
            second.setPadding(0, dp(2), 0, 0);
            texts.addView(second);
        }
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(change);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(16));
        row.addView(sw, lp);
        return sw;
    }
}
