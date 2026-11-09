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
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Lists kept on the tablet: shopping, to do, packing or any other the user names. Items go
 * on, get ticked off and are cleared; several at once ("milk, eggs and bread").
 */
final class Lists {

    private Lists() {
    }

    /** "Einkaufsliste", "shopping" and "Einkauf" are one list: the name, plain and small. */
    static String name(String list) {
        String n = list == null ? "" : list.trim().toLowerCase(Locale.ROOT);
        n = n.replaceAll("(?i)^(die |meine |my |the )", "").replaceAll("(?i)\\s*(liste|list)$", "");
        if (n.isEmpty() || n.matches("einkauf|einkaufs|einkaufen|shopping|groceries")) {
            return "einkauf";
        }
        if (n.matches("to ?do|to-do|aufgaben|erledigen|todo")) {
            return "todo";
        }
        return n;
    }

    /** The items as the model gives them: a list, or one text ("milk, eggs and bread"). */
    private static JSONArray items(JSONObject args) {
        final JSONArray given = args.optJSONArray("items");
        if (given != null) {
            return given;
        }
        final JSONArray split = new JSONArray();
        for (String part : args.optString("items").split("\\s*(,|;|\\bund\\b|\\band\\b)\\s*")) {
            if (!part.trim().isEmpty()) {
                split.put(part.trim());
            }
        }
        return split;
    }

    static Tools.Result add(Context context, JSONObject args) {
        final String list = name(args.optString("list"));
        final JSONArray items = items(args);
        final Store store = Store.get(context);
        int n = 0;
        for (int i = 0; items != null && i < items.length(); i++) {
            final String text = items.optString(i).trim();
            if (!text.isEmpty()) {
                store.addItem(list, text);
                n++;
            }
        }
        if (n == 0) {
            return new Tools.Result("error: no items given", null);
        }
        // At the lock screen anyone may add to a list, but not see what is on it.
        if (Tools.locked(context)) {
            return new Tools.Result(n + " added to " + list,
                    context.getString(R.string.chip_list_added, n));
        }
        return new Tools.Result(n + " added to " + list + "; now:\n" + describe(context, list),
                context.getString(R.string.chip_list_added, n), card(context, list));
    }

    static Tools.Result show(Context context, JSONObject args) {
        final String given = args.optString("list");
        if (given.trim().isEmpty() && !args.has("list")) {
            final List<String[]> lists = Store.get(context).lists();
            if (lists.isEmpty()) {
                return new Tools.Result("no lists yet", null);
            }
            final StringBuilder out = new StringBuilder("lists (open items):\n");
            for (String[] l : lists) {
                out.append(l[0]).append(" (").append(l[1]).append(")\n");
            }
            return new Tools.Result(out.toString(), null);
        }
        final String list = name(given);
        return new Tools.Result(describe(context, list), context.getString(R.string.chip_list,
                title(context, list)), card(context, list));
    }

    /** Ticks items off (or deletes them): by id, or by their words. */
    static Tools.Result check(Context context, JSONObject args) {
        final String list = name(args.optString("list"));
        final boolean delete = "delete".equals(args.optString("action"));
        final JSONArray which = items(args);
        final Store store = Store.get(context);
        final List<Store.Item> all = store.items(list);
        int n = 0;
        for (int i = 0; i < which.length(); i++) {
            final Store.Item item = match(all, which.optString(i).trim().toLowerCase(Locale.ROOT));
            if (item == null) {
                continue;
            }
            all.remove(item);
            if (delete) {
                store.deleteItem(item.id);
            } else {
                store.setDone(item.id, true);
            }
            n++;
        }
        if (n == 0) {
            return new Tools.Result("error: none of those on " + list + "; show_list first", null);
        }
        return new Tools.Result(n + (delete ? " deleted" : " ticked off") + "; now:\n"
                + describe(context, list), context.getString(R.string.chip_list_checked, n),
                card(context, list));
    }

    /**
     * The one item meant: a number is its id; else the item of exactly these words, or the
     * only one containing them as whole words. Unsure means none ("milk" never takes
     * "chocolate milk" along with "milk").
     */
    private static Store.Item match(List<Store.Item> items, String w) {
        if (w.isEmpty()) {
            return null;
        }
        if (w.matches("\\d+")) {
            for (Store.Item item : items) {
                if (w.equals(String.valueOf(item.id))) {
                    return item;
                }
            }
            return null;
        }
        Store.Item only = null;
        for (Store.Item item : items) {
            final String text = item.text.toLowerCase(Locale.ROOT);
            if (text.equals(w)) {
                return item;
            }
            if ((" " + text + " ").contains(" " + w + " ")) {
                if (only != null) {
                    return null;
                }
                only = item;
            }
        }
        return only;
    }

    static Tools.Result clear(Context context, JSONObject args) {
        final String list = name(args.optString("list"));
        final boolean all = args.optBoolean("all", false);
        final int n = Store.get(context).clearList(list, !all);
        return new Tools.Result(n + " removed from " + list,
                context.getString(R.string.chip_list_cleared, title(context, list)));
    }

    private static String describe(Context context, String list) {
        final List<Store.Item> items = Store.get(context).items(list);
        if (items.isEmpty()) {
            return list + " is empty";
        }
        final StringBuilder out = new StringBuilder(list).append(":\n");
        for (Store.Item i : items) {
            out.append('[').append(i.id).append("] ").append(i.done ? "(done) " : "")
                    .append(i.text).append('\n');
        }
        return out.toString();
    }

    /** "einkauf" as the user reads it: "Einkaufsliste" / "Shopping list". */
    static String title(Context context, String list) {
        if ("einkauf".equals(list)) {
            return context.getString(R.string.list_shopping);
        }
        if ("todo".equals(list)) {
            return context.getString(R.string.list_todo);
        }
        return Character.toUpperCase(list.charAt(0)) + list.substring(1);
    }

    private static JSONObject card(Context context, String list) {
        try {
            final JSONArray rows = new JSONArray();
            for (Store.Item i : Store.get(context).items(list)) {
                rows.put(new JSONObject().put("text", i.text).put("done", i.done));
            }
            return new JSONObject().put("type", "checklist").put("title", title(context, list))
                    .put("rows", rows);
        } catch (JSONException e) {
            return null;
        }
    }
}
