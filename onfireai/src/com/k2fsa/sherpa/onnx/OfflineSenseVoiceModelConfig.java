/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

public class OfflineSenseVoiceModelConfig {
    private final String model;
    private final String language;
    private final boolean useInverseTextNormalization;
    private final QnnConfig qnnConfig;

    private OfflineSenseVoiceModelConfig(Builder builder) {
        this.model = builder.model;
        this.language = builder.language;
        this.useInverseTextNormalization = builder.useInverseTextNormalization;
        this.qnnConfig = builder.qnnConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return model;
    }

    public String getLanguage() {
        return language;
    }

    public boolean getUseInverseTextNormalization() {
        return useInverseTextNormalization;
    }

    public QnnConfig getQnnConfig() {
        return qnnConfig;
    }

    public static class Builder {
        private String model = "";
        private String language = "";
        private boolean useInverseTextNormalization = true;
        private QnnConfig qnnConfig = new QnnConfig();

        public OfflineSenseVoiceModelConfig build() {
            return new OfflineSenseVoiceModelConfig(this);
        }

        public Builder setModel(String model) {
            this.model = model;
            return this;
        }

        public Builder setLanguage(String language) {
            this.language = language;
            return this;
        }

        public Builder setInverseTextNormalization(boolean useInverseTextNormalization) {
            this.useInverseTextNormalization = useInverseTextNormalization;
            return this;
        }

        public Builder setQnnConfig(QnnConfig qnnConfig) {
            this.qnnConfig = qnnConfig;
            return this;
        }
    }
}
