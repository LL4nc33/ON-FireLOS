/*
 * Copyright (C) 2026 ON-FireLOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onfirelos.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Meaning instead of words for the memory: with an embeddings server set (any OpenAI-compatible
 * /v1/embeddings, e.g. llama.cpp started with --embeddings), each note gets a vector and the
 * notes closest to a question come along with it. The vectors stay in the app's database.
 */
final class Embeddings {

    private Embeddings() {
    }

    static boolean configured(Context context) {
        return !Prefs.string(context, Prefs.EMBED_URL).isEmpty();
    }

    /** Worker thread. Null when no server is set or it does not answer. */
    static float[] of(Context context, String text) {
        if (!configured(context)) {
            return null;
        }
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL(LlmClient.endpoint(
                    Prefs.string(context, Prefs.EMBED_URL), "/embeddings")).openConnection();
            try {
                c.setConnectTimeout(4000);
                c.setReadTimeout(10000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                final String model = Prefs.string(context, Prefs.EMBED_MODEL);
                final JSONObject body = new JSONObject().put("input", text)
                        .put("model", model.isEmpty() ? "default" : model);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (c.getResponseCode() != 200) {
                    return null;
                }
                final JSONArray v = new JSONObject(LlmClient.readAll(c.getInputStream()))
                        .getJSONArray("data").getJSONObject(0).getJSONArray("embedding");
                final float[] out = new float[v.length()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (float) v.getDouble(i);
                }
                return out;
            } finally {
                c.disconnect();
            }
        } catch (IOException | org.json.JSONException e) {
            return null;
        }
    }

    static byte[] pack(float[] v) {
        final ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            b.putFloat(f);
        }
        return b.array();
    }

    static float[] unpack(byte[] bytes) {
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        return v;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? -1 : dot / Math.sqrt(na * nb);
    }
}
