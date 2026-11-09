/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

public class OfflineRecognizerResult {
    private final String text;
    private final String[] tokens;
    private final float[] timestamps;
    private final String lang;
    private final String emotion;
    private final String event;
    private final float[] durations;
    // The decoded word IDs. Empty for greedy search decoding, non-empty when
    // an HLG graph is used, i.e. when ctcFstDecoderConfig.graph is set.
    private final int[] words;

    public OfflineRecognizerResult(String text, String[] tokens, float[] timestamps, String lang, String emotion, String event, float[] durations, int[] words) {
        this.text = text;
        this.tokens = tokens;
        this.timestamps = timestamps;
        this.lang = lang;
        this.emotion = emotion;
        this.event = event;
        this.durations = durations;
        this.words = words;
    }

    public String getText() {
        return text;
    }

    public String[] getTokens() {
        return tokens;
    }

    public float[] getTimestamps() {
        return timestamps;
    }

    public String getLang() {
        return lang;
    }

    public String getEmotion() {
        return emotion;
    }

    public String getEvent() {
        return event;
    }

    public float[] getDurations() {
        return durations;
    }

    public int[] getWords() {
        return words;
    }
}
