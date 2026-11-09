/*
 * Java counterparts of sherpa-onnx's Kotlin API (Copyright (c) 2024 Xiaomi Corporation,
 * Apache License 2.0), for ON-FireAI's wake word. The native library reads these classes
 * and their fields by name, so both must stay exactly as in sherpa-onnx v2.0.1.
 */

package com.k2fsa.sherpa.onnx;

public class OnlineModelConfig {
    public OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
    public OnlineParaformerModelConfig paraformer = new OnlineParaformerModelConfig();
    public OnlineZipformer2CtcModelConfig zipformer2Ctc = new OnlineZipformer2CtcModelConfig();
    public OnlineNeMoCtcModelConfig neMoCtc = new OnlineNeMoCtcModelConfig();
    public OnlineToneCtcModelConfig toneCtc = new OnlineToneCtcModelConfig();
    public String tokens = "";
    public int numThreads = 1;
    public boolean debug = false;
    public String provider = "cpu";
    public String modelType = "";
    public String modelingUnit = "";
    public String bpeVocab = "";
}
