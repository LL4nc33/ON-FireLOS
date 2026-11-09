/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

public class OfflineCtcFstDecoderConfig {
    private final String graph;
    private final int maxActive;

    private OfflineCtcFstDecoderConfig(Builder builder) {
        this.graph = builder.graph;
        this.maxActive = builder.maxActive;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getGraph() {
        return graph;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public static class Builder {
        private String graph = "";
        private int maxActive = 3000;

        public OfflineCtcFstDecoderConfig build() {
            return new OfflineCtcFstDecoderConfig(this);
        }

        public Builder setGraph(String graph) {
            this.graph = graph;
            return this;
        }

        public Builder setMaxActive(int maxActive) {
            this.maxActive = maxActive;
            return this;
        }
    }
}
