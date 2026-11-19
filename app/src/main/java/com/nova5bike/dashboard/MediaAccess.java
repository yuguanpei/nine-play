package com.nova5bike.dashboard;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.List;

public final class MediaAccess extends NotificationListenerService {
    interface NoticeListener {
        void posted(String app, String title, String conversation, String message, Drawable icon);
    }

    private static NoticeListener noticeListener;

    static void setNoticeListener(NoticeListener listener) { noticeListener = listener; }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        NoticeListener listener = noticeListener;
        if (listener == null || posted == null || getPackageName().equals(posted.getPackageName())) return;
        Notification notification = posted.getNotification();
        if ((notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0
                || Notification.CATEGORY_TRANSPORT.equals(notification.category)) return;
        Bundle fields = notification.extras;
        if (fields == null) return;
        CharSequence title = fields.getCharSequence(Notification.EXTRA_TITLE, "");
        CharSequence conversation = fields.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "");
        CharSequence message = fields.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(message)) message = fields.getCharSequence(Notification.EXTRA_TEXT, "");
        Parcelable[] entries = fields.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (entries != null && Build.VERSION.SDK_INT >= 30) {
            List<Notification.MessagingStyle.Message> messages =
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(entries);
            if (!messages.isEmpty()) {
                Notification.MessagingStyle.Message latest = messages.get(messages.size() - 1);
                Person sender = latest.getSenderPerson();
                if (sender != null && !TextUtils.isEmpty(sender.getName())) title = sender.getName();
                if (!TextUtils.isEmpty(latest.getText())) message = latest.getText();
            }
        } else if (entries != null) {
            // Android 8–10 expose MessagingStyle bundles without the public decoder.
            for (int i = entries.length - 1; i >= 0; i--) {
                if (!(entries[i] instanceof Bundle)) continue;
                Bundle latest = (Bundle) entries[i];
                CharSequence recent = latest.getCharSequence("text");
                CharSequence sender = latest.getCharSequence("sender");
                if (Build.VERSION.SDK_INT >= 28) {
                    Parcelable person = latest.getParcelable("sender_person");
                    if (person instanceof Person) sender = ((Person) person).getName();
                }
                if (!TextUtils.isEmpty(sender)) title = sender;
                if (!TextUtils.isEmpty(recent)) message = recent;
                break;
            }
        }
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(message)) return;
        String app = "本机通知";
        Drawable icon = null;
        try {
            PackageManager packages = getPackageManager();
            ApplicationInfo info = packages.getApplicationInfo(posted.getPackageName(), 0);
            app = packages.getApplicationLabel(info).toString();
            icon = info.loadIcon(packages);
        } catch (PackageManager.NameNotFoundException ignored) {}
        if (icon == null && notification.getSmallIcon() != null) icon = notification.getSmallIcon().loadDrawable(this);
        listener.posted(app, text(title), text(conversation), text(message), icon);
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString().trim(); }

    static Drawable applicationIcon(Context context, String packageName) {
        try {
            return context.getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }
}
