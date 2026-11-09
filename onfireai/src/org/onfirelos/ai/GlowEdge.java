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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The sheet's glowing edge: a light along its rounded top, from the brand's green into ember.
 * It breathes with the voice while listening, travels across while thinking, rests softly
 * while answering, and goes out when the assistant is idle. Drawn as a few strokes of
 * falling strength instead of a blur, so it stays on the GPU.
 */
public class GlowEdge extends View {

    static final int IDLE = 0;
    static final int LISTENING = 1;
    static final int THINKING = 2;
    static final int ANSWERING = 3;

    /** Space above the sheet for the light to spill into. */
    static final int SPILL_DP = 14;

    private static final float RADIUS_DP = 28f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Matrix mMatrix = new Matrix();
    private final float mDensity;
    private final ValueAnimator mTravel;
    private LinearGradient mGradient;
    private int mMode = IDLE;
    /** 0..1: how strongly it shows, eased towards the mode's target. */
    private float mStrength;
    /** 0..1: the voice's loudness while listening. */
    private float mLevel;
    private float mPhase;

    public GlowEdge(Context context) {
        this(context, null);
    }

    public GlowEdge(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
        setOutlineProvider(null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mTravel = ValueAnimator.ofFloat(0f, 1f);
        mTravel.setInterpolator(new LinearInterpolator());
        mTravel.setRepeatCount(ValueAnimator.INFINITE);
        mTravel.setDuration(2200);
        mTravel.addUpdateListener(a -> {
            mPhase = (float) a.getAnimatedValue();
            step();
            invalidate();
        });
    }

    void setMode(int mode) {
        if (mode == mMode) {
            return;
        }
        mMode = mode;
        // Thinking travels faster than the calm of an answer.
        mTravel.setDuration(mode == THINKING ? 1600 : 3600);
        if (!mTravel.isStarted()) {
            mTravel.start();
        }
    }

    /** About -2 (silence) to 10 (loud), as the recognizers report it. */
    void setLevel(float rmsDb) {
        mLevel = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
    }

    private float target() {
        switch (mMode) {
            case LISTENING: return 0.45f + 0.55f * mLevel;
            case THINKING: return 1f;
            case ANSWERING: return 0.5f;
            default: return 0f;
        }
    }

    /** Eases the strength a little each frame; stops the animation once it has gone out. */
    private void step() {
        mStrength += (target() - mStrength) * 0.12f;
        if (mMode == IDLE && mStrength < 0.01f) {
            mStrength = 0f;
            mTravel.cancel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mTravel.cancel();
        super.onDetachedFromWindow();
    }

    /**
     * It takes the sheet's size, never sets it: asked to fit (the frame is still measuring
     * the sheet) it asks for no height, and is measured again at the frame's exact size.
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(heightSpec) : 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        final float spill = SPILL_DP * mDensity;
        final float r = RADIUS_DP * mDensity;
        final float inset = 1.5f * mDensity;
        // Down the sides a little, round the corners, along the top.
        final float side = r + 36 * mDensity;
        mPath.reset();
        mPath.moveTo(inset, spill + side);
        mPath.lineTo(inset, spill + r);
        mPath.arcTo(new RectF(inset, spill + inset, inset + 2 * r, spill + 2 * r), 180, 90);
        mPath.lineTo(w - r - inset, spill + inset);
        mPath.arcTo(new RectF(w - 2 * r - inset, spill + inset, w - inset, spill + 2 * r), 270,
                90);
        mPath.lineTo(w - inset, spill + side);
        final int green = getContext().getColor(R.color.glow_green);
        final int light = getContext().getColor(R.color.glow_light);
        final int ember = getContext().getColor(R.color.glow_ember);
        // One width of colour, repeated: the matrix moves it along.
        mGradient = new LinearGradient(0, 0, w, 0,
                new int[] { green, light, ember, light, green },
                new float[] { 0f, 0.3f, 0.5f, 0.7f, 1f }, Shader.TileMode.MIRROR);
        mPaint.setShader(mGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mStrength <= 0f || mGradient == null) {
            return;
        }
        final float w = getWidth();
        // Thinking travels; listening and answering drift slowly round the middle.
        final float shift = mMode == THINKING ? mPhase * 2 * w
                : (float) Math.sin(mPhase * 2 * Math.PI) * w * 0.25f;
        mMatrix.setTranslate(shift, 0);
        mGradient.setLocalMatrix(mMatrix);
        // Wide and faint outside, narrow and bright at the edge.
        final float[] widths = { 12f, 7f, 3.5f, 1.8f };
        final float[] alphas = { 0.10f, 0.18f, 0.45f, 0.95f };
        for (int i = 0; i < widths.length; i++) {
            mPaint.setStrokeWidth(widths[i] * mDensity);
            mPaint.setAlpha(Math.round(255 * alphas[i] * mStrength));
            canvas.drawPath(mPath, mPaint);
        }
    }
}
