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

/*
 * Echo cancellation for the wake word: what the tablet itself plays is taken out of the
 * microphone (Speex's echo canceller and its residual echo and noise suppression), and the
 * delay between the two is measured (GCC-PHAT). Settings as measured best offline:
 * 10 ms frames, a 64 ms filter, suppression -40/-15 dB, noise -15 dB.
 */

#include <jni.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "config.h"
#include "fftwrap.h"
#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

typedef struct {
    SpeexEchoState *echo;
    SpeexPreprocessState *pre;
    int frame;
} Aec;

JNIEXPORT jlong JNICALL
Java_org_onfirelos_ai_EchoCanceller_nativeCreate(JNIEnv *env, jclass cls, jint frame,
                                                 jint tail, jint rate) {
    Aec *a = calloc(1, sizeof(Aec));
    if (a == NULL) {
        return 0;
    }
    a->frame = frame;
    a->echo = speex_echo_state_init(frame, tail);
    speex_echo_ctl(a->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
    a->pre = speex_preprocess_state_init(frame, rate);
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_ECHO_STATE, a->echo);
    int v = -40;
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &v);
    v = -15;
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &v);
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &v);
    /* The residual echo suppression only acts with denoise on. */
    v = 1;
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_DENOISE, &v);
    v = 0;
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_AGC, &v);
    speex_preprocess_ctl(a->pre, SPEEX_PREPROCESS_SET_DEREVERB, &v);
    return (jlong) (intptr_t) a;
}

/* n samples (a multiple of the frame) of microphone and reference in, the cleaned
 * microphone out. */
JNIEXPORT void JNICALL
Java_org_onfirelos_ai_EchoCanceller_nativeProcess(JNIEnv *env, jclass cls, jlong handle,
                                                  jshortArray mic, jshortArray ref,
                                                  jshortArray out, jint n) {
    Aec *a = (Aec *) (intptr_t) handle;
    if (a == NULL) {
        return;
    }
    jshort *m = (*env)->GetShortArrayElements(env, mic, NULL);
    jshort *r = (*env)->GetShortArrayElements(env, ref, NULL);
    jshort *o = (*env)->GetShortArrayElements(env, out, NULL);
    for (int i = 0; i + a->frame <= n; i += a->frame) {
        speex_echo_cancellation(a->echo, m + i, r + i, o + i);
        speex_preprocess_run(a->pre, o + i);
    }
    (*env)->ReleaseShortArrayElements(env, out, o, 0);
    (*env)->ReleaseShortArrayElements(env, ref, r, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, mic, m, JNI_ABORT);
}

/* Starting over: after a gap in the reference the learned echo path is stale. */
JNIEXPORT void JNICALL
Java_org_onfirelos_ai_EchoCanceller_nativeReset(JNIEnv *env, jclass cls, jlong handle) {
    Aec *a = (Aec *) (intptr_t) handle;
    if (a != NULL) {
        speex_echo_state_reset(a->echo);
    }
}

JNIEXPORT void JNICALL
Java_org_onfirelos_ai_EchoCanceller_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    Aec *a = (Aec *) (intptr_t) handle;
    if (a == NULL) {
        return;
    }
    speex_preprocess_state_destroy(a->pre);
    speex_echo_state_destroy(a->echo);
    free(a);
}

/*
 * The delay of the echo: by how many samples the microphone lags the reference (GCC-PHAT
 * over n samples of each, searched within +-maxLag). result[0] is the lag, result[1] the
 * peak's height over the mean (how sure it is; below about 6 it is a guess).
 */
JNIEXPORT void JNICALL
Java_org_onfirelos_ai_EchoCanceller_nativeLag(JNIEnv *env, jclass cls, jshortArray mic,
                                              jshortArray ref, jint n, jint maxLag,
                                              jfloatArray result) {
    int size = 1;
    while (size < 2 * n) {
        size <<= 1;
    }
    float *x = calloc(size, sizeof(float));
    float *y = calloc(size, sizeof(float));
    float *fx = malloc(size * sizeof(float));
    float *fy = malloc(size * sizeof(float));
    void *table = spx_fft_init(size);
    jshort *m = (*env)->GetShortArrayElements(env, mic, NULL);
    jshort *r = (*env)->GetShortArrayElements(env, ref, NULL);
    for (int i = 0; i < n; i++) {
        x[i] = m[i];
        y[i] = r[i];
    }
    (*env)->ReleaseShortArrayElements(env, ref, r, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, mic, m, JNI_ABORT);
    spx_fft_float(table, x, fx);
    spx_fft_float(table, y, fy);
    /* Packed real spectrum: [0] DC, then (re, im) pairs, [size-1] Nyquist. Cross spectrum
     * mic * conj(ref), whitened. */
    fx[0] = fx[0] * fy[0] >= 0 ? 1.f : -1.f;
    for (int k = 1; k < size - 1; k += 2) {
        const float re = fx[k] * fy[k] + fx[k + 1] * fy[k + 1];
        const float im = fx[k + 1] * fy[k] - fx[k] * fy[k + 1];
        const float mag = sqrtf(re * re + im * im) + 1e-9f;
        fx[k] = re / mag;
        fx[k + 1] = im / mag;
    }
    fx[size - 1] = fx[size - 1] * fy[size - 1] >= 0 ? 1.f : -1.f;
    spx_ifft_float(table, fx, x);
    float best = -1e30f;
    int lag = 0;
    double sum = 0;
    int count = 0;
    for (int k = -maxLag; k <= maxLag; k++) {
        const float v = x[(k + size) % size];
        sum += fabsf(v);
        count++;
        if (v > best) {
            best = v;
            lag = k;
        }
    }
    jfloat out[2] = {(jfloat) lag, (jfloat) (best / (sum / count + 1e-12))};
    (*env)->SetFloatArrayRegion(env, result, 0, 2, out);
    spx_fft_destroy(table);
    free(x);
    free(y);
    free(fx);
    free(fy);
}
