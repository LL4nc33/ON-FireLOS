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
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A client for the Model Context Protocol over Streamable HTTP: the MCP servers the user
 * entered lend their tools to the model, next to the built-in ones. Tools a server marks as
 * destructive ask before they run.
 */
final class Mcp {

    private static final String TAG = "ONFireAI";
    private static final String PROTOCOL = "2025-03-26";
    private static final long CACHE = 10 * 60 * 1000;
    /** A server that did not answer is left alone this long, so it does not slow each turn. */
    private static final long RETRY = 2 * 60 * 1000;

    static final class Server {
        String url;
        String key;
        boolean on = true;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("url", url).put("key", key == null ? "" : key)
                    .put("on", on);
        }
    }

    static final class Tool {
        final Server server;
        final String name;
        final String exposedName;
        final JSONObject definition;
        final boolean destructive;

        Tool(Server server, String name, String exposedName, JSONObject definition,
                boolean destructive) {
            this.server = server;
            this.name = name;
            this.exposedName = exposedName;
            this.definition = definition;
            this.destructive = destructive;
        }
    }

    /** A server that failed shortly before and is left alone for now. */
    static final class Resting extends IOException {
        Resting(String message) {
            super(message);
        }
    }

    private static final Map<String, String> sSessions = new HashMap<>();
    private static final Map<String, List<Tool>> sTools = new HashMap<>();
    private static final Map<String, Long> sFetched = new HashMap<>();
    private static final Map<String, String> sErrors = new HashMap<>();

    private Mcp() {
    }

    // ---------------------------------------------------------------- the list of servers

    static List<Server> servers(Context context) {
        final List<Server> list = new ArrayList<>();
        try {
            final JSONArray a = new JSONArray(Prefs.get(context).getString("mcp_servers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                final Server s = new Server();
                s.url = o.optString("url");
                s.key = o.optString("key");
                s.on = o.optBoolean("on", true);
                if (!s.url.isEmpty()) {
                    list.add(s);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "mcp servers", e);
        }
        return list;
    }

    static void save(Context context, List<Server> servers) {
        final JSONArray a = new JSONArray();
        try {
            for (Server s : servers) {
                a.put(s.toJson());
            }
        } catch (JSONException ignored) {
        }
        Prefs.get(context).edit().putString("mcp_servers", a.toString()).apply();
        synchronized (sTools) {
            sTools.clear();
            sFetched.clear();
        }
    }

    static String lastError(Server server) {
        synchronized (sTools) {
            return sErrors.get(server.url);
        }
    }

    // ---------------------------------------------------------------- tools

    /** The tools of all servers that are on, fetched at most every ten minutes. */
    static List<Tool> tools(Context context) {
        final List<Tool> all = new ArrayList<>();
        int index = 0;
        for (Server server : servers(context)) {
            index++;
            if (!server.on) {
                continue;
            }
            try {
                all.addAll(tools(server, index));
            } catch (Resting e) {
                // Still left alone after its last failure.
            } catch (IOException | JSONException e) {
                Log.w(TAG, "mcp " + server.url + ": " + e.getMessage());
                synchronized (sTools) {
                    sErrors.put(server.url, e.getMessage());
                    sTools.put(server.url, new ArrayList<>());
                    sFetched.put(server.url, System.currentTimeMillis() - CACHE + RETRY);
                }
            }
        }
        return all;
    }

    static List<Tool> tools(Server server, int index) throws IOException, JSONException {
        synchronized (sTools) {
            final Long at = sFetched.get(server.url);
            if (at != null && System.currentTimeMillis() - at < CACHE) {
                final String error = sErrors.get(server.url);
                if (error != null) {
                    // Failed a moment ago: the same answer, without asking it again.
                    throw new Resting(error);
                }
                return sTools.get(server.url);
            }
        }
        ensureSession(server);
        final JSONObject result = call(server, "tools/list", new JSONObject());
        final JSONArray list = result.optJSONArray("tools");
        final List<Tool> tools = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            final JSONObject t = list.getJSONObject(i);
            final String name = t.optString("name");
            // Names the model sees: unique across servers, within the API's limits.
            String exposed = ("mcp" + index + "_" + name).replaceAll("[^a-zA-Z0-9_-]", "_");
            if (exposed.length() > 64) {
                exposed = exposed.substring(0, 64);
            }
            final JSONObject schema = t.optJSONObject("inputSchema") != null
                    ? t.optJSONObject("inputSchema")
                    : new JSONObject().put("type", "object").put("properties", new JSONObject());
            final JSONObject definition = new JSONObject().put("type", "function")
                    .put("function", new JSONObject().put("name", exposed)
                            .put("description", t.optString("description", name))
                            .put("parameters", schema));
            // As the MCP spec has it: a tool that does not say it only reads, and does not say
            // it is harmless, may destroy, and asks first.
            final JSONObject notes = t.optJSONObject("annotations");
            final boolean readOnly = notes != null && notes.optBoolean("readOnlyHint", false);
            final boolean destructive = !readOnly
                    && (notes == null || notes.optBoolean("destructiveHint", true));
            tools.add(new Tool(server, name, exposed, definition, destructive));
        }
        synchronized (sTools) {
            sTools.put(server.url, tools);
            sFetched.put(server.url, System.currentTimeMillis());
            sErrors.remove(server.url);
        }
        return tools;
    }

    /** Runs a tool and gives its text back for the model. */
    static String run(Tool tool, String arguments) {
        try {
            final JSONObject args = arguments == null || arguments.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(arguments);
            ensureSession(tool.server);
            final JSONObject result = call(tool.server, "tools/call",
                    new JSONObject().put("name", tool.name).put("arguments", args));
            final StringBuilder out = new StringBuilder();
            final JSONArray content = result.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                final JSONObject c = content.getJSONObject(i);
                if ("text".equals(c.optString("type"))) {
                    out.append(c.optString("text")).append('\n');
                } else {
                    out.append('[').append(c.optString("type")).append("]\n");
                }
            }
            String text = out.toString().trim();
            if (text.length() > 6000) {
                text = text.substring(0, 6000) + "…";
            }
            return (result.optBoolean("isError") ? "error: " : "") + text;
        } catch (IOException | JSONException e) {
            return "error: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- the protocol

    private static void ensureSession(Server server) throws IOException, JSONException {
        synchronized (sSessions) {
            if (sSessions.containsKey(server.url)) {
                return;
            }
        }
        final JSONObject params = new JSONObject()
                .put("protocolVersion", PROTOCOL)
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "ON-FireAI").put("version", "1"));
        final String[] session = new String[1];
        request(server, new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", "initialize").put("params", params), session, null);
        synchronized (sSessions) {
            sSessions.put(server.url, session[0] == null ? "" : session[0]);
        }
        request(server, new JSONObject().put("jsonrpc", "2.0")
                .put("method", "notifications/initialized"), null, sessionOf(server));
    }

    private static String sessionOf(Server server) {
        synchronized (sSessions) {
            final String s = sSessions.get(server.url);
            return s == null || s.isEmpty() ? null : s;
        }
    }

    private static int sNextId = 2;

    private static JSONObject call(Server server, String method, JSONObject params)
            throws IOException, JSONException {
        final int id;
        synchronized (Mcp.class) {
            id = sNextId++;
        }
        final JSONObject response = request(server, new JSONObject().put("jsonrpc", "2.0")
                .put("id", id).put("method", method).put("params", params), null,
                sessionOf(server));
        if (response == null) {
            throw new IOException("no answer to " + method);
        }
        final JSONObject error = response.optJSONObject("error");
        if (error != null) {
            if (error.optInt("code") == -32001 || error.optString("message").contains("session")) {
                // The server forgot the session: start a new one next time.
                synchronized (sSessions) {
                    sSessions.remove(server.url);
                }
            }
            throw new IOException(error.optString("message", "error " + error.optInt("code")));
        }
        return response.optJSONObject("result") != null ? response.optJSONObject("result")
                : new JSONObject();
    }

    /** One POST; the answer comes as JSON or as an event stream with the answer in it. */
    private static JSONObject request(Server server, JSONObject message, String[] sessionOut,
            String session) throws IOException, JSONException {
        final HttpURLConnection c = (HttpURLConnection) new URL(server.url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json, text/event-stream");
            c.setRequestProperty("MCP-Protocol-Version", PROTOCOL);
            if (session != null) {
                c.setRequestProperty("Mcp-Session-Id", session);
            }
            if (server.key != null && !server.key.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + server.key);
            }
            try (OutputStream out = c.getOutputStream()) {
                out.write(message.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int code = c.getResponseCode();
            if (sessionOut != null) {
                sessionOut[0] = c.getHeaderField("Mcp-Session-Id");
            }
            if (code == 202 || code == 204) {
                return null;
            }
            if (code / 100 != 2) {
                if (code == 404 && session != null) {
                    synchronized (sSessions) {
                        sSessions.remove(server.url);
                    }
                }
                throw new IOException("HTTP " + code);
            }
            final String type = c.getContentType();
            if (type != null && type.contains("text/event-stream")) {
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                final StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty() && data.length() > 0) {
                        final JSONObject event = new JSONObject(data.toString());
                        data.setLength(0);
                        // Requests and notifications of the server pass by; the answer has our id.
                        if (event.has("id") && (event.has("result") || event.has("error"))) {
                            return event;
                        }
                    }
                }
                return data.length() > 0 ? new JSONObject(data.toString()) : null;
            }
            final String body = LlmClient.readAll(c.getInputStream()).trim();
            return body.isEmpty() ? null : new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }
}
