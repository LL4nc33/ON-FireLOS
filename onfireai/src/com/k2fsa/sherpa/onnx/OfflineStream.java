/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

public class OfflineStream {
    private long ptr = 0;

    public OfflineStream() {
        this.ptr = 0;
    }

    public OfflineStream(long ptr) {
        this.ptr = ptr;
    }

    public long getPtr() {
        return ptr;
    }

    public void setPtr(long ptr) {
        this.ptr = ptr;
    }

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(this.ptr, samples, sampleRate);
    }

    public void setOption(String key, String value) {
        setOption(this.ptr, key, value);
    }

    public String getOption(String key) {
        return getOption(this.ptr, key);
    }

    public boolean hasOption(String key) {
        return hasOption(this.ptr, key);
    }

    public void release() {
        // stream object must be release after used
        if (this.ptr == 0) {
            return;
        }
        delete(this.ptr);
        this.ptr = 0;
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);

    private native void setOption(long ptr, String key, String value);

    private native String getOption(long ptr, String key);

    private native boolean hasOption(long ptr, String key);

    private native void delete(long ptr);
}
