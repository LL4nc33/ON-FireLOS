/*
 * Java counterparts of sherpa-onnx's Kotlin API (Copyright (c) 2024 Xiaomi Corporation,
 * Apache License 2.0), for ON-FireAI's wake word. The native library reads these classes
 * and their fields by name, so both must stay exactly as in sherpa-onnx v2.0.1.
 */

package com.k2fsa.sherpa.onnx;

public class KeywordSpotterResult {
    public final String keyword;
    public final String[] tokens;
    public final float[] timestamps;

    /** Called by the native library. */
    public KeywordSpotterResult(String keyword, String[] tokens, float[] timestamps) {
        this.keyword = keyword;
        this.tokens = tokens;
        this.timestamps = timestamps;
    }
}
