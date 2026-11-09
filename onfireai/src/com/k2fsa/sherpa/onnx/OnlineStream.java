/*
 * Java counterparts of sherpa-onnx's Kotlin API (Copyright (c) 2024 Xiaomi Corporation,
 * Apache License 2.0), for ON-FireAI's wake word. The native library reads these classes
 * and their fields by name, so both must stay exactly as in sherpa-onnx v2.0.1.
 */

package com.k2fsa.sherpa.onnx;

public class OnlineStream {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    long ptr;

    OnlineStream(long ptr) {
        if (ptr == 0) {
            throw new IllegalStateException("Failed to create native OnlineStream");
        }
        this.ptr = ptr;
    }

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(ptr, samples, sampleRate);
    }

    public void inputFinished() {
        inputFinished(ptr);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);

    private native void inputFinished(long ptr);

    private native void delete(long ptr);
}
