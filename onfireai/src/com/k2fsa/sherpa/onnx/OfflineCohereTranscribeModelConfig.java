/*
 * From sherpa-onnx v2.0.1 (Copyright (c) 2024 Xiaomi Corporation, Apache License 2.0),
 * for ON-FireAI's speech recognition on the tablet. The native library reads these
 * classes and their fields by name, so both must stay exactly as in that version.
 */

package com.k2fsa.sherpa.onnx;

public class OfflineCohereTranscribeModelConfig {
    private final String encoder;
    private final String decoder;
    private final String language;
    private final boolean usePunct;
    private final boolean useItn;

    private OfflineCohereTranscribeModelConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.language = builder.language;
        this.usePunct = builder.usePunct;
        this.useItn = builder.useItn;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEncoder() {
        return encoder;
    }

    public String getDecoder() {
        return decoder;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isUsePunct() {
        return usePunct;
    }

    public boolean isUseItn() {
        return useItn;
    }

    public static class Builder {
        private String encoder = "";
        private String decoder = "";
        private String language = "";
        private boolean usePunct = true;
        private boolean useItn = true;

        public OfflineCohereTranscribeModelConfig build() {
            return new OfflineCohereTranscribeModelConfig(this);
        }

        public Builder setEncoder(String encoder) {
            this.encoder = encoder;
            return this;
        }

        public Builder setDecoder(String decoder) {
            this.decoder = decoder;
            return this;
        }

        public Builder setLanguage(String language) {
            this.language = language;
            return this;
        }

        public Builder setUsePunct(boolean usePunct) {
            this.usePunct = usePunct;
            return this;
        }

        public Builder setUseItn(boolean useItn) {
            this.useItn = useItn;
            return this;
        }
    }
}
