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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The memory, only on the tablet: notes the user or the model asked to keep, and every
 * conversation, one list per day.
 */
final class Store extends SQLiteOpenHelper {

    static final class Note {
        final long id;
        final String text;
        final long created;

        Note(long id, String text, long created) {
            this.id = id;
            this.text = text;
            this.created = created;
        }
    }

    static final class Line {
        final long time;
        final String role;
        final String text;

        Line(long time, String role, String text) {
            this.time = time;
            this.role = role;
            this.text = text;
        }
    }

    static final class Reminder {
        final long id;
        final String text;
        final long at;
        final boolean fired;

        Reminder(long id, String text, long at, boolean fired) {
            this.id = id;
            this.text = text;
            this.at = at;
            this.fired = fired;
        }
    }

    static final class Item {
        final long id;
        final String text;
        final boolean done;

        Item(long id, String text, boolean done) {
            this.id = id;
            this.text = text;
            this.done = done;
        }
    }

    private static Store sInstance;
    private final Context mContext;

    static synchronized Store get(Context context) {
        if (sInstance == null) {
            sInstance = new Store(context.getApplicationContext());
        }
        return sInstance;
    }

    private Store(Context context) {
        super(context, "onfireai.db", null, 3);
        mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL,"
                + " created INTEGER NOT NULL, vec BLOB)");
        db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL,"
                + " time INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX log_day ON log (day)");
        createVersion3(db);
    }

    /** Version 3: reminders, and lists (shopping, to do ...). */
    private static void createVersion3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " text TEXT NOT NULL, at INTEGER NOT NULL, fired INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, list TEXT NOT NULL,"
                + " text TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX items_list ON items (list)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Version 2: a vector per note, for the search by meaning.
            db.execSQL("ALTER TABLE notes ADD COLUMN vec BLOB");
        }
        if (oldVersion < 3) {
            createVersion3(db);
        }
    }

    // ---------------------------------------------------------------- reminders

    long addReminder(String text, long at) {
        final ContentValues values = new ContentValues();
        values.put("text", text);
        values.put("at", at);
        return getWritableDatabase().insert("reminders", null, values);
    }

    /** Pending ones (not yet said), soonest first; or all. */
    List<Reminder> reminders(boolean pendingOnly) {
        final List<Reminder> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("reminders",
                new String[] { "id", "text", "at", "fired" }, pendingOnly ? "fired = 0" : null,
                null, null, null, "at ASC")) {
            while (c.moveToNext()) {
                out.add(new Reminder(c.getLong(0), c.getString(1), c.getLong(2),
                        c.getInt(3) != 0));
            }
        }
        return out;
    }

    Reminder reminder(long id) {
        try (Cursor c = getReadableDatabase().query("reminders",
                new String[] { "id", "text", "at", "fired" }, "id = ?",
                new String[] { String.valueOf(id) }, null, null, null)) {
            return c.moveToFirst() ? new Reminder(c.getLong(0), c.getString(1), c.getLong(2),
                    c.getInt(3) != 0) : null;
        }
    }

    void markFired(long id) {
        final ContentValues values = new ContentValues();
        values.put("fired", 1);
        getWritableDatabase().update("reminders", values, "id = ?",
                new String[] { String.valueOf(id) });
    }

    void deleteReminder(long id) {
        getWritableDatabase().delete("reminders", "id = ?", new String[] { String.valueOf(id) });
    }

    // ---------------------------------------------------------------- lists

    long addItem(String list, String text) {
        final ContentValues values = new ContentValues();
        values.put("list", list);
        values.put("text", text);
        values.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("items", null, values);
    }

    List<Item> items(String list) {
        final List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("items",
                new String[] { "id", "text", "done" }, "list = ?", new String[] { list },
                null, null, "done ASC, created ASC")) {
            while (c.moveToNext()) {
                out.add(new Item(c.getLong(0), c.getString(1), c.getInt(2) != 0));
            }
        }
        return out;
    }

    /** The names of the lists there are, with how many open items each has. */
    List<String[]> lists() {
        final List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT list, SUM(done = 0) FROM items"
                + " GROUP BY list ORDER BY MAX(created) DESC", null)) {
            while (c.moveToNext()) {
                out.add(new String[] { c.getString(0), String.valueOf(c.getInt(1)) });
            }
        }
        return out;
    }

    boolean setDone(long id, boolean done) {
        final ContentValues values = new ContentValues();
        values.put("done", done ? 1 : 0);
        return getWritableDatabase().update("items", values, "id = ?",
                new String[] { String.valueOf(id) }) > 0;
    }

    boolean deleteItem(long id) {
        return getWritableDatabase().delete("items", "id = ?",
                new String[] { String.valueOf(id) }) > 0;
    }

    int clearList(String list, boolean doneOnly) {
        return getWritableDatabase().delete("items", doneOnly ? "list = ? AND done = 1"
                : "list = ?", new String[] { list });
    }

    // ---------------------------------------------------------------- notes

    /** Worker thread: with an embeddings server the note also gets its vector. */
    long addNote(String text) {
        final ContentValues values = new ContentValues();
        values.put("text", text.trim());
        values.put("created", System.currentTimeMillis());
        final float[] vec = Embeddings.of(mContext, text.trim());
        if (vec != null) {
            values.put("vec", Embeddings.pack(vec));
        }
        return getWritableDatabase().insert("notes", null, values);
    }

    boolean deleteNote(long id) {
        return getWritableDatabase().delete("notes", "id = ?",
                new String[] { String.valueOf(id) }) > 0;
    }

    List<Note> notes() {
        final List<Note> notes = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("notes",
                new String[] { "id", "text", "created" }, null, null, null, null,
                "created DESC")) {
            while (c.moveToNext()) {
                notes.add(new Note(c.getLong(0), c.getString(1), c.getLong(2)));
            }
        }
        return notes;
    }

    /**
     * The notes that share the most words with the question - enough for a few hundred
     * notes; without a match, the newest.
     */
    List<Note> relevantNotes(String question, int max) {
        final List<Note> all = notes();
        if (all.size() <= max) {
            return all;
        }
        final List<Note> byMeaning = closestNotes(question, max);
        if (byMeaning != null) {
            return byMeaning;
        }
        final Set<String> words = words(question);
        final List<Note> scored = new ArrayList<>(all);
        final java.util.Map<Note, Integer> score = new java.util.HashMap<>();
        for (Note note : all) {
            int s = 0;
            for (String w : words(note.text)) {
                if (words.contains(w)) {
                    s++;
                }
            }
            score.put(note, s);
        }
        scored.sort((a, b) -> {
            final int d = score.get(b) - score.get(a);
            return d != 0 ? d : Long.compare(b.created, a.created);
        });
        return scored.subList(0, max);
    }

    /** The notes nearest in meaning, or null without vectors to compare. */
    private List<Note> closestNotes(String question, int max) {
        if (!Embeddings.configured(mContext)) {
            return null;
        }
        final float[] q = Embeddings.of(mContext, question);
        if (q == null) {
            return null;
        }
        final List<Note> notes = new ArrayList<>();
        final java.util.Map<Note, Double> score = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().query("notes",
                new String[] { "id", "text", "created", "vec" }, "vec IS NOT NULL", null,
                null, null, null)) {
            while (c.moveToNext()) {
                final Note n = new Note(c.getLong(0), c.getString(1), c.getLong(2));
                score.put(n, Embeddings.cosine(q, Embeddings.unpack(c.getBlob(3))));
                notes.add(n);
            }
        }
        if (notes.isEmpty()) {
            return null;
        }
        notes.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return notes.subList(0, Math.min(max, notes.size()));
    }

    private static Set<String> words(String text) {
        final Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() > 2) {
                words.add(w);
            }
        }
        return words;
    }

    // ---------------------------------------------------------------- conversations

    static String day(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(time));
    }

    void log(String role, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final ContentValues values = new ContentValues();
        values.put("day", day(now));
        values.put("time", now);
        values.put("role", role);
        values.put("text", text.trim());
        getWritableDatabase().insert("log", null, values);
    }

    /** The days with a conversation, newest first. */
    List<String> days() {
        final List<String> days = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT day FROM log GROUP BY day ORDER BY day DESC", null)) {
            while (c.moveToNext()) {
                days.add(c.getString(0));
            }
        }
        return days;
    }

    List<Line> lines(String day) {
        final List<Line> lines = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("log", new String[] { "time", "role", "text" },
                "day = ?", new String[] { day }, null, null, "time")) {
            while (c.moveToNext()) {
                lines.add(new Line(c.getLong(0), c.getString(1), c.getString(2)));
            }
        }
        return lines;
    }

    /** Lines of earlier conversations that share words with the question, newest first. */
    List<Line> search(String query, int max) {
        final List<Line> found = new ArrayList<>();
        final Set<String> words = words(query);
        if (words.isEmpty()) {
            return found;
        }
        final StringBuilder where = new StringBuilder();
        final List<String> args = new ArrayList<>();
        for (String w : words) {
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append("text LIKE ?");
            args.add("%" + w + "%");
        }
        try (Cursor c = getReadableDatabase().query("log", new String[] { "time", "role", "text" },
                where.toString(), args.toArray(new String[0]), null, null, "time DESC",
                String.valueOf(max))) {
            while (c.moveToNext()) {
                found.add(new Line(c.getLong(0), c.getString(1), c.getString(2)));
            }
        }
        return found;
    }

    void clear() {
        final SQLiteDatabase db = getWritableDatabase();
        db.delete("notes", null, null);
        db.delete("log", null, null);
    }
}
