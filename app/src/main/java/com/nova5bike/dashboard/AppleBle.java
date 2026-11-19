package com.nova5bike.dashboard;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class AppleBle {
    static final int PLAY = 0, PAUSE = 1, TOGGLE = 2, NEXT = 3, PREVIOUS = 4;
    static final int MAX_NOTICE_BYTES = 2048;

    static byte[] notificationRequest(int id) {
        return new byte[]{0, (byte) id, (byte) (id >>> 8), (byte) (id >>> 16), (byte) (id >>> 24),
                0, 1, 96, 0, 2, 96, 0, 3, (byte) 232, 0};
    }

    static int notificationId(byte[] data, int offset) {
        return unsigned(data[offset]) | unsigned(data[offset + 1]) << 8
                | unsigned(data[offset + 2]) << 16 | unsigned(data[offset + 3]) << 24;
    }

    static Notice parseNotice(int id, byte[] data) {
        if (data.length < 5 || data[0] != 0 || notificationId(data, 1) != id) return null;
        String[] values = {"", "", "", ""};
        int cursor = 5;
        int previousAttribute = -1;
        while (cursor + 3 <= data.length) {
            int attribute = unsigned(data[cursor]);
            if (attribute <= previousAttribute || attribute >= values.length
                    || (previousAttribute < 0 && attribute != 0)) return null;
            int length = unsigned(data[cursor + 1]) | unsigned(data[cursor + 2]) << 8;
            cursor += 3;
            if (length > data.length - cursor) return null;
            values[attribute] = text(data, cursor, length);
            cursor += length;
            previousAttribute = attribute;
            if (attribute == 3) return new Notice(id, values[0], values[1], values[2], values[3]);
        }
        return null;
    }

    static String text(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.UTF_8).trim();
    }

    static int unsigned(byte value) { return value & 255; }

    static final class Notice {
        final int id;
        final String app;
        final String title;
        final String subtitle;
        final String message;

        Notice(int id, String app, String title, String subtitle, String message) {
            this.id = id;
            this.app = app;
            this.title = title;
            this.subtitle = subtitle;
            this.message = message;
        }

        String source() {
            switch (app.toLowerCase(Locale.ROOT)) {
                case "com.apple.mobilesms": return "信息";
                case "com.apple.mobilemail": return "邮件";
                case "com.apple.mobilecal": return "日历";
                case "com.apple.mobilephone": return "电话";
                case "com.apple.reminders": return "提醒事项";
                case "com.tencent.xin":
                case "com.tencent.mm": return "微信";
                case "com.tencent.mqq": return "QQ";
                case "com.whatsapp.whatsapp": return "WhatsApp";
                case "ph.telegra.telegraph":
                case "org.telegram.messenger":
                case "org.telegram.messenger.web": return "Telegram";
                case "app.swiftgram.ios": return "Swiftgram";
                case "com.alibaba.dingtalk": return "钉钉";
                case "com.ss.iphone.ugc.aweme": return "抖音";
                case "com.xingin.discover": return "小红书";
                default:
                    String shortName = app.substring(app.lastIndexOf('.') + 1);
                    return shortName.isEmpty() ? "通知" : shortName;
            }
        }
    }

    static final class Media {
        String title = "";
        String artist = "";
        String album = "";
        String player = "";
        boolean playing;
        double duration, elapsed, rate;
        long receivedAt;
        int commands;
        private int incompleteFields;

        void markIncomplete(int entity, int field, boolean incomplete) {
            if (entity != 2 || field > 2) return;
            if (incomplete) incompleteFields |= 1 << field;
            else incompleteFields &= ~(1 << field);
        }

        String titleForLookup() { return (incompleteFields & 4) == 0 ? title : ""; }
        String artistForLookup() { return (incompleteFields & 1) == 0 ? artist : ""; }
        String albumForLookup() { return (incompleteFields & 2) == 0 ? album : ""; }

        boolean matchesArtworkAlbum(String candidateAlbum) {
            String expected = albumForLookup();
            return expected.isEmpty() || expected.equalsIgnoreCase(candidateAlbum);
        }

        void supportedCommands(byte[] data) {
            commands = 0;
            for (byte value : data) {
                int command = unsigned(value);
                if (command <= PREVIOUS) commands |= 1 << command;
            }
        }

        boolean supports(int command) { return (commands & (1 << command)) != 0; }

        int playPauseCommand() {
            int command = playing ? PAUSE : PLAY;
            return supports(command) ? command : supports(TOGGLE) ? TOGGLE : -1;
        }

        void update(int entity, int attribute, String value, long now) {
            if (entity == 2) {
                if (attribute == 0) artist = value;
                if (attribute == 1) album = value;
                if (attribute == 2) title = value;
                if (attribute == 3) duration = number(value);
            } else if (entity == 0 && attribute == 0) {
                if (!player.equals(value)) {
                    title = "";
                    artist = "";
                    album = "";
                    duration = elapsed = rate = 0;
                    playing = false;
                }
                player = value;
            } else if (entity == 0 && attribute == 1) {
                String[] fields = value.split(",", -1);
                if (fields.length != 3) return;
                playing = "1".equals(fields[0]);
                rate = number(fields[1]);
                elapsed = number(fields[2]);
                receivedAt = now;
            }
        }

        float progress(long now) {
            if (duration <= 0) return 0;
            double position = elapsed + (playing ? Math.max(0, now - receivedAt) * rate / 1000 : 0);
            return (float) Math.max(0, Math.min(1, position / duration));
        }

        private static double number(String value) {
            try {
                double result = Double.parseDouble(value);
                return Double.isFinite(result) ? result : 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }
}
