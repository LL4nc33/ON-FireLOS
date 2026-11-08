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

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets ON-FireAI read the notifications when asked ("what's new?"). Off until the user turns
 * it on; nothing is read in the background.
 */
public class NotificationWatcher extends NotificationListenerService {

    private static volatile NotificationWatcher sInstance;

    static NotificationWatcher get() {
        return sInstance;
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, NotificationWatcher.class);
    }

    static boolean enabled(Context context) {
        final String list = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        return list != null && list.contains(component(context).flattenToString());
    }

    static void setEnabled(Context context, boolean on) {
        final String me = component(context).flattenToString();
        String list = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        list = list == null ? "" : list;
        final List<String> parts = new ArrayList<>();
        for (String p : list.split(":")) {
            if (!p.isEmpty() && !p.equals(me)) {
                parts.add(p);
            }
        }
        if (on) {
            parts.add(me);
        }
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                TextUtils.join(":", parts));
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
    }

    @Override
    public void onListenerDisconnected() {
        sInstance = null;
    }

    /** The notifications on the tablet now, newest first, as "app: title - text" lines. */
    String describe() {
        final StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (RuntimeException e) {
            return "error: notifications unavailable";
        }
        if (all == null || all.length == 0) {
            return "no notifications";
        }
        final PackageManager pm = getPackageManager();
        final List<StatusBarNotification> list = new ArrayList<>();
        for (StatusBarNotification n : all) {
            // Not ongoing things (music, downloads, charging) and not group summaries.
            if (n.isOngoing() || (n.getNotification().flags
                    & Notification.FLAG_GROUP_SUMMARY) != 0
                    || getPackageName().equals(n.getPackageName())) {
                continue;
            }
            list.add(n);
        }
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size() && i < 20; i++) {
            final StatusBarNotification n = list.get(i);
            final Bundle extras = n.getNotification().extras;
            final CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (TextUtils.isEmpty(text)) {
                text = extras.getCharSequence(Notification.EXTRA_TEXT);
            }
            String app;
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(n.getPackageName(), 0))
                        .toString();
            } catch (PackageManager.NameNotFoundException e) {
                app = n.getPackageName();
            }
            String body = TextUtils.isEmpty(text) ? "" : text.toString().replace('\n', ' ');
            if (body.length() > 240) {
                body = body.substring(0, 240) + "…";
            }
            out.append(app).append(": ").append(TextUtils.isEmpty(title) ? "" : title)
                    .append(body.isEmpty() ? "" : " - " + body).append('\n');
        }
        return out.length() == 0 ? "no notifications" : out.toString();
    }
}
