/*
 * Java counterparts of sherpa-onnx's Kotlin API (Copyright (c) 2024 Xiaomi Corporation,
 * Apache License 2.0), for ON-FireAI's wake word. The native library reads these classes
 * and their fields by name, so both must stay exactly as in sherpa-onnx v2.0.1.
 */

package com.k2fsa.sherpa.onnx;

public class KeywordSpotterConfig {
    public FeatureConfig featConfig = new FeatureConfig();
    public OnlineModelConfig modelConfig = new OnlineModelConfig();
    public int maxActivePaths = 4;
    public String keywordsFile = "";
    public float keywordsScore = 1.5f;
    public float keywordsThreshold = 0.25f;
    public int numTrailingBlanks = 2;
}
