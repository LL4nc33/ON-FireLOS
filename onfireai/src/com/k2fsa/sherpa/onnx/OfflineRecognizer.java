/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class OfflineRecognizer {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;

    /** With an asset manager the model paths are paths inside the app's assets. */
    public OfflineRecognizer(AssetManager assets, OfflineRecognizerConfig config) {
        ptr = assets != null ? newFromAsset(assets, config) : newFromFile(config);
        if (ptr == 0) {
            throw new IllegalStateException("Invalid OfflineRecognizerConfig");
        }
    }

    public OfflineStream createStream() {
        return new OfflineStream(createStream(ptr));
    }

    public void decode(OfflineStream s) {
        decode(ptr, s.getPtr());
    }

    public OfflineRecognizerResult getResult(OfflineStream s) {
        return getResult(s.getPtr());
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native void delete(long ptr);

    private native long newFromAsset(AssetManager assets, OfflineRecognizerConfig config);

    private native long newFromFile(OfflineRecognizerConfig config);

    private native long createStream(long ptr);

    private native void decode(long ptr, long streamPtr);

    private native OfflineRecognizerResult getResult(long streamPtr);
}
