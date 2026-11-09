/*
 * Java counterparts of sherpa-onnx's Kotlin API (Copyright (c) 2024 Xiaomi Corporation,
 * Apache License 2.0), for ON-FireAI's wake word. The native library reads these classes
 * and their fields by name, so both must stay exactly as in sherpa-onnx v2.0.1.
 */

package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class KeywordSpotter {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;

    /** With an asset manager the model paths are paths inside the app's assets. */
    public KeywordSpotter(AssetManager assets, KeywordSpotterConfig config) {
        ptr = assets != null ? newFromAsset(assets, config) : newFromFile(config);
        if (ptr == 0) {
            throw new IllegalStateException("Invalid KeywordSpotterConfig");
        }
    }

    /** The keywords as tokens, one per line ("\u2581HE Y \u2581FI RE @HEY_FIRE"). */
    public OnlineStream createStream(String keywords) {
        return new OnlineStream(createStream(ptr, keywords));
    }

    public boolean isReady(OnlineStream stream) {
        return isReady(ptr, stream.ptr);
    }

    public void decode(OnlineStream stream) {
        decode(ptr, stream.ptr);
    }

    public void reset(OnlineStream stream) {
        reset(ptr, stream.ptr);
    }

    public KeywordSpotterResult getResult(OnlineStream stream) {
        return getResult(ptr, stream.ptr);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native void delete(long ptr);

    private native long newFromAsset(AssetManager assetManager, KeywordSpotterConfig config);

    private native long newFromFile(KeywordSpotterConfig config);

    private native long createStream(long ptr, String keywords);

    private native boolean isReady(long ptr, long streamPtr);

    private native void decode(long ptr, long streamPtr);

    private native void reset(long ptr, long streamPtr);

    private native KeywordSpotterResult getResult(long ptr, long streamPtr);
}
