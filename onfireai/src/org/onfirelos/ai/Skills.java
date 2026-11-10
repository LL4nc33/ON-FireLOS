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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skills: know-how for a kind of task (looking something up well, filling in a form,
 * cooking along a recipe), each a folder with a SKILL.md - a name and a one-line
 * description at the top, the instructions below - as Anthropic's Agent Skills have it.
 *
 * The model sees only the names and descriptions; it loads a skill's instructions with
 * use_skill when a task fits, so even many skills keep the prompt short. Built in: the
 * app's assets/skills; one's own: /sdcard/ON-FireAI/skills (over USB or a file manager,
 * or written by ON-FireAI itself with save_skill), which win over a built-in one of the
 * same name.
 */
final class Skills {

    private static final String TAG = "ONFireAI";
    private static final String ASSETS = "skills";
    private static final String FILE = "SKILL.md";
    /** What one skill may bring into the conversation, instructions or a file. */
    private static final int MAX_TEXT = 12_000;
    private static final int MAX_LISTED = 30;

    static final class Skill {
        final String name;
        final String description;
        final String body;
        /** Its folder for one's own skills; null for a built-in one. */
        final File dir;

        Skill(String name, String description, String body, File dir) {
            this.name = name;
            this.description = description;
            this.body = body;
            this.dir = dir;
        }
    }

    private Skills() {
    }

    static File userDir() {
        return new File(Environment.getExternalStorageDirectory(), "ON-FireAI/skills");
    }

    /** All skills by name, one's own over the built-in ones. */
    static Map<String, Skill> all(Context context) {
        final Map<String, Skill> skills = new LinkedHashMap<>();
        try {
            final String[] names = context.getAssets().list(ASSETS);
            for (String name : names == null ? new String[0] : names) {
                try (InputStream in = context.getAssets().open(ASSETS + "/" + name + "/" + FILE)) {
                    final Skill s = parse(name, read(in), null);
                    if (s != null) {
                        skills.put(s.name, s);
                    }
                } catch (IOException ignored) {
                    // A folder without SKILL.md is not a skill.
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "skills: " + e.getMessage());
        }
        if (storage(context)) {
            final File[] dirs = userDir().listFiles(File::isDirectory);
            for (File dir : dirs == null ? new File[0] : dirs) {
                final File file = new File(dir, FILE);
                if (!file.isFile()) {
                    continue;
                }
                try {
                    final Skill s = parse(dir.getName(), new String(Files.readAllBytes(
                            file.toPath()), StandardCharsets.UTF_8), dir);
                    if (s != null) {
                        skills.put(s.name, s);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "skill " + dir.getName() + ": " + e.getMessage());
                }
            }
        }
        return skills;
    }

    /** For the system prompt: what skills there are, one line each; "" without any. */
    static String promptBlock(Context context) {
        final Map<String, Skill> skills = all(context);
        if (skills.isEmpty()) {
            return "";
        }
        // Said plainly: a model left to judge skips them for anything that looks small.
        final StringBuilder out = new StringBuilder("- Skills: when a request fits one of"
                + " these, your first step is use_skill with its name (it is quick), then you"
                + " follow its instructions - also for a short request:\n");
        int n = 0;
        for (Skill s : skills.values()) {
            if (n++ == MAX_LISTED) {
                break;
            }
            out.append("  - ").append(s.name).append(": ").append(s.description).append('\n');
        }
        return out.toString();
    }

    /** A skill's instructions, or one of the files it brings. */
    static String use(Context context, String name, String file) {
        final Skill s = all(context).get(name.trim());
        if (s == null) {
            return "error: no skill " + name;
        }
        if (file == null || file.trim().isEmpty() || FILE.equals(file.trim())) {
            final List<String> files = files(context, s);
            return "SKILL " + s.name + " (follow these instructions):\n" + cut(s.body)
                    + (files.isEmpty() ? "" : "\nIts files (use_skill with file to read one): "
                    + String.join(", ", files));
        }
        final String wanted = file.trim();
        // Only a file of the skill itself, never a path out of it.
        if (wanted.contains("/") || wanted.contains("\\") || wanted.startsWith(".")) {
            return "error: only a file name of this skill";
        }
        try {
            if (s.dir == null) {
                try (InputStream in = context.getAssets().open(ASSETS + "/" + s.name + "/"
                        + wanted)) {
                    return cut(read(in));
                }
            }
            return cut(new String(Files.readAllBytes(new File(s.dir, wanted).toPath()),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "error: no file " + wanted + " in skill " + s.name;
        }
    }

    /** A new skill of one's own, from what the user taught; written to the user folder. */
    static String save(Context context, String name, String description, String instructions) {
        final String id = name.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (id.isEmpty() || description.trim().isEmpty() || instructions.trim().isEmpty()) {
            return "error: give a name, a one-line description and the instructions";
        }
        if (!storage(context)) {
            return "error: no access to the tablet's storage";
        }
        final File dir = new File(userDir(), id);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "error: could not create the folder";
        }
        final String text = "---\nname: " + id + "\ndescription: "
                + description.trim().replace('\n', ' ') + "\n---\n\n" + instructions.trim()
                + "\n";
        try (FileOutputStream out = new FileOutputStream(new File(dir, FILE))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
        return "saved skill " + id + " (in ON-FireAI/skills on the tablet)";
    }

    // ---------------------------------------------------------------- reading

    /** Front matter between --- lines: name and description; the rest is the body. */
    private static Skill parse(String folder, String text, File dir) {
        String name = folder;
        String description = "";
        String body = text;
        if (text.startsWith("---")) {
            final int end = text.indexOf("\n---", 3);
            if (end > 0) {
                for (String line : text.substring(3, end).split("\n")) {
                    final int colon = line.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    final String key = line.substring(0, colon).trim();
                    final String value = line.substring(colon + 1).trim()
                            .replaceAll("^[\"']|[\"']$", "");
                    if ("name".equals(key) && !value.isEmpty()) {
                        name = value;
                    } else if ("description".equals(key)) {
                        description = value;
                    }
                }
                body = text.substring(text.indexOf('\n', end + 1) + 1);
            }
        }
        if (description.isEmpty()) {
            return null;
        }
        if (description.length() > 200) {
            description = description.substring(0, 200);
        }
        return new Skill(name, description, body.trim(), dir);
    }

    private static List<String> files(Context context, Skill s) {
        final List<String> files = new ArrayList<>();
        try {
            final String[] names = s.dir == null
                    ? context.getAssets().list(ASSETS + "/" + s.name) : s.dir.list();
            for (String n : names == null ? new String[0] : names) {
                if (!FILE.equals(n)) {
                    files.add(n);
                }
            }
        } catch (IOException ignored) {
        }
        return files;
    }

    private static String read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String cut(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "\n(cut)" : text;
    }

    /** The shared storage, granted to itself the first time (a system app may). */
    private static boolean storage(Context context) {
        for (String p : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                try {
                    context.getPackageManager().grantRuntimePermission(
                            context.getPackageName(), p, Process.myUserHandle());
                } catch (RuntimeException e) {
                    Log.w(TAG, "skills storage: " + e.getMessage());
                    return false;
                }
            }
        }
        return true;
    }
}
