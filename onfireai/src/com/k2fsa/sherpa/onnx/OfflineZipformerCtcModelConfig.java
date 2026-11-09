/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

public class OfflineZipformerCtcModelConfig {
    private final String model;
    private final QnnConfig qnnConfig;

    private OfflineZipformerCtcModelConfig(Builder builder) {
        this.model = builder.model;
        this.qnnConfig = builder.qnnConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return model;
    }

    public QnnConfig getQnnConfig() {
        return qnnConfig;
    }

    public static class Builder {
        private String model = "";
        private QnnConfig qnnConfig = new QnnConfig();

        public OfflineZipformerCtcModelConfig build() {
            return new OfflineZipformerCtcModelConfig(this);
        }

        public Builder setModel(String model) {
            this.model = model;
            return this;
        }

        public Builder setQnnConfig(QnnConfig qnnConfig) {
            this.qnnConfig = qnnConfig;
            return this;
        }
    }
}
