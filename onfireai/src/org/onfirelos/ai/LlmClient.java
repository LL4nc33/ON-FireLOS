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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/**
 * Talks to any OpenAI-compatible server: POST /v1/chat/completions with stream on, read as
 * server-sent events, the answer's text handed on piece by piece and tool calls gathered.
 */
final class LlmClient {

    interface Listener {
        /** A piece of the answer's text, as it arrives. */
        void onText(String piece);
    }

    static final class Result {
        final StringBuilder text = new StringBuilder();
        /** The calls in the OpenAI form, ready to go back into the messages. */
        final JSONArray toolCalls = new JSONArray();
        String finishReason = "";
    }

    static final class ServerException extends IOException {
        ServerException(String message) {
            super(message);
        }
    }

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 90000;

    private static volatile String sAutoModelFor;
    private static volatile String sAutoModel;

    private final String mBase;
    private final String mKey;
    private final String mModel;
    private volatile HttpURLConnection mConnection;
    private volatile boolean mCancelled;

    LlmClient(String base, String key, String model) {
        mBase = base;
        mKey = key;
        mModel = model;
    }

    /** "server:8080", "http://server:8080/" and "http://server:8080/v1" all reach /v1/... */
    static String endpoint(String base, String path) {
        String b = base.trim();
        if (!b.contains("://")) {
            b = "http://" + b;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (!b.endsWith("/v1")) {
            b += "/v1";
        }
        return b + path;
    }

    void cancel() {
        mCancelled = true;
        final HttpURLConnection connection = mConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    boolean cancelled() {
        return mCancelled;
    }

    /** The model to ask: the one set, or the server's first. */
    String model() throws IOException {
        if (!mModel.isEmpty()) {
            return mModel;
        }
        if (mBase.equals(sAutoModelFor) && sAutoModel != null) {
            return sAutoModel;
        }
        final JSONArray models = models();
        final String first = models.length() > 0 ? models.optJSONObject(0).optString("id") : "";
        sAutoModelFor = mBase;
        sAutoModel = first;
        return first;
    }

    /** GET /v1/models: [{"id": ...}, ...]. */
    JSONArray models() throws IOException {
        final HttpURLConnection c = open(endpoint(mBase, "/models"), "GET");
        try {
            final String body = readAll(check(c));
            final JSONArray data = new JSONObject(body).optJSONArray("data");
            return data == null ? new JSONArray() : data;
        } catch (JSONException e) {
            throw new IOException("models: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** One round: the answer streams into the listener; tool calls come back whole. */
    Result chat(JSONArray messages, JSONArray tools, Listener listener) throws IOException {
        final JSONObject body = new JSONObject();
        try {
            body.put("model", model());
            body.put("messages", messages);
            body.put("stream", true);
            body.put("temperature", 0.6);
            body.put("max_tokens", 900);
            // Qwen and others think aloud unless told not to; spoken answers want none of it.
            body.put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        final HttpURLConnection c = open(endpoint(mBase, "/chat/completions"), "POST");
        mConnection = c;
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            final InputStream in = check(c);
            final Result result = new Result();
            final ThinkFilter filter = new ThinkFilter(listener, result.text);
            final TreeMap<Integer, JSONObject> calls = new TreeMap<>();
            final String type = c.getContentType();
            if (type != null && type.contains("application/json")) {
                // A server that ignores "stream" answers in one piece.
                read(new JSONObject(readAll(in)), filter, calls, result, false);
            } else {
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while (!mCancelled && (line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    final String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    read(new JSONObject(data), filter, calls, result, true);
                }
            }
            for (JSONObject call : calls.values()) {
                result.toolCalls.put(call);
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("answer: " + e.getMessage());
        } finally {
            mConnection = null;
            c.disconnect();
        }
    }

    private static void read(JSONObject chunk, ThinkFilter filter,
            TreeMap<Integer, JSONObject> calls, Result result, boolean streamed)
            throws JSONException {
        final JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return;
        }
        final JSONObject choice = choices.getJSONObject(0);
        final JSONObject part = choice.optJSONObject(streamed ? "delta" : "message");
        if (part != null) {
            final Object content = part.opt("content");
            if (content instanceof String) {
                filter.add((String) content);
            }
            final JSONArray toolCalls = part.optJSONArray("tool_calls");
            if (toolCalls != null) {
                for (int i = 0; i < toolCalls.length(); i++) {
                    gather(toolCalls.getJSONObject(i), i, calls);
                }
            }
        }
        final String finish = choice.optString("finish_reason", "");
        if (!finish.isEmpty() && !finish.equals("null")) {
            result.finishReason = finish;
        }
    }

    /** Streamed calls come in pieces: the name first, the arguments a few characters at a time. */
    private static void gather(JSONObject piece, int position, TreeMap<Integer, JSONObject> calls)
            throws JSONException {
        final int index = piece.optInt("index", position);
        JSONObject call = calls.get(index);
        if (call == null) {
            call = new JSONObject()
                    .put("id", "call_" + index)
                    .put("type", "function")
                    .put("function", new JSONObject().put("name", "").put("arguments", ""));
            calls.put(index, call);
        }
        final String id = piece.optString("id", "");
        if (!id.isEmpty()) {
            call.put("id", id);
        }
        final JSONObject function = piece.optJSONObject("function");
        if (function != null) {
            final JSONObject target = call.getJSONObject("function");
            final String name = function.optString("name", "");
            if (!name.isEmpty()) {
                target.put("name", target.optString("name") + name);
            }
            final Object args = function.opt("arguments");
            if (args instanceof String) {
                target.put("arguments", target.optString("arguments") + args);
            } else if (args instanceof JSONObject) {
                target.put("arguments", args.toString());
            }
        }
    }

    private HttpURLConnection open(String address, String method) throws IOException {
        if (mCancelled) {
            throw new IOException("cancelled");
        }
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("User-Agent", "ON-FireAI");
        if (!mKey.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + mKey);
        }
        return c;
    }

    private static InputStream check(HttpURLConnection c) throws IOException {
        final int code = c.getResponseCode();
        if (code / 100 != 2) {
            String message = "";
            final InputStream error = c.getErrorStream();
            if (error != null) {
                message = readAll(error);
                try {
                    final JSONObject json = new JSONObject(message);
                    final JSONObject err = json.optJSONObject("error");
                    message = err != null ? err.optString("message", message) : message;
                } catch (JSONException ignored) {
                    // Not JSON: keep the text.
                }
            }
            if (message.length() > 160) {
                message = message.substring(0, 160);
            }
            throw new ServerException(code + (message.isEmpty() ? "" : " " + message));
        }
        return c.getInputStream();
    }

    static String readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > 4 * 1024 * 1024) {
                throw new IOException("too large");
            }
        }
        return out.toString("UTF-8");
    }

    /** Drops a <think>...</think> block some models write before their answer. */
    private static final class ThinkFilter {
        private final Listener mListener;
        private final StringBuilder mAll;
        private final StringBuilder mPending = new StringBuilder();
        private boolean mThinking;

        ThinkFilter(Listener listener, StringBuilder all) {
            mListener = listener;
            mAll = all;
        }

        void add(String piece) {
            mPending.append(piece);
            while (true) {
                final String s = mPending.toString();
                if (mThinking) {
                    final int end = s.indexOf("</think>");
                    if (end < 0) {
                        // Keep only what could be the start of the closing tag.
                        mPending.setLength(0);
                        mPending.append(s.substring(Math.max(0, s.length() - 8)));
                        return;
                    }
                    mThinking = false;
                    mPending.setLength(0);
                    mPending.append(s.substring(end + 8));
                    continue;
                }
                final int start = s.indexOf("<think>");
                if (start >= 0) {
                    emit(s.substring(0, start));
                    mThinking = true;
                    mPending.setLength(0);
                    mPending.append(s.substring(start + 7));
                    continue;
                }
                // Hold back a possible "<thi" at the end until the next piece.
                final int lt = s.lastIndexOf('<');
                if (lt >= 0 && s.length() - lt < 7 && "<think>".startsWith(s.substring(lt))) {
                    emit(s.substring(0, lt));
                    mPending.setLength(0);
                    mPending.append(s.substring(lt));
                } else {
                    emit(s);
                    mPending.setLength(0);
                }
                return;
            }
        }

        private void emit(String text) {
            if (text.isEmpty()) {
                return;
            }
            mAll.append(text);
            if (mListener != null) {
                mListener.onText(text);
            }
        }
    }
}
