package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONObject;

final class VkVideoId {
    private VkVideoId() { }

    static String fromUrl(String url) throws Exception {
        String id = findInText(url);
        Uri uri = Uri.parse(url);
        if (id != null) {
            if (hasAccessKey(id)) return id;
            String accessKey = queryAccessKey(uri);
            return accessKey.isEmpty() ? id : id + "_" + accessKey;
        }

        String owner = uri.getQueryParameter("oid");
        String video = uri.getQueryParameter("id");
        if (isSignedNumber(owner) && isUnsignedNumber(video)) {
            String accessKey = queryAccessKey(uri);
            return owner + "_" + video + (accessKey.isEmpty() ? "" : "_" + accessKey);
        }
        throw new Exception("Не найден ID VK Video");
    }

    static String fromVideo(JSONObject video) {
        String base = video.optLong("owner_id") + "_" + video.optLong("id");
        String accessKey = cleanAccessKey(video.optString("access_key"));
        if (accessKey.isEmpty()) accessKey = cleanAccessKey(video.optString("accessKey"));
        if (!accessKey.isEmpty()) return base + "_" + accessKey;

        String[] urlFields = {"direct_url", "share_url", "player"};
        for (String field : urlFields) {
            try {
                String candidate = fromUrl(video.optString(field));
                if (candidate.startsWith(base + "_")) return candidate;
            } catch (Exception ignored) { }
        }
        return base;
    }

    private static String queryAccessKey(Uri uri) {
        String accessKey = cleanAccessKey(uri.getQueryParameter("access_key"));
        return accessKey.isEmpty()
                ? cleanAccessKey(uri.getQueryParameter("hash")) : accessKey;
    }

    private static boolean hasAccessKey(String id) {
        int first = id.indexOf('_');
        return first >= 0 && id.indexOf('_', first + 1) >= 0;
    }

    private static String findInText(String value) {
        if (value == null || value.isEmpty()) return null;
        int from = 0;
        while (from < value.length()) {
            int marker = value.indexOf("video", from);
            if (marker < 0) return null;
            int start = marker + 5;
            int cursor = start;
            if (cursor < value.length() && value.charAt(cursor) == '-') cursor++;
            int ownerStart = cursor;
            while (cursor < value.length() && isDigit(value.charAt(cursor))) cursor++;
            if (cursor > ownerStart && cursor < value.length() && value.charAt(cursor) == '_') {
                cursor++;
                int videoStart = cursor;
                while (cursor < value.length() && isDigit(value.charAt(cursor))) cursor++;
                if (cursor > videoStart) {
                    if (cursor < value.length() && value.charAt(cursor) == '_') {
                        int accessStart = ++cursor;
                        while (cursor < value.length() && isAccessKeyChar(value.charAt(cursor))) cursor++;
                        if (cursor == accessStart) cursor--;
                    }
                    return value.substring(start, cursor);
                }
            }
            from = marker + 5;
        }
        return null;
    }

    private static String cleanAccessKey(String value) {
        if (value == null || value.isEmpty()) return "";
        int end = 0;
        while (end < value.length() && isAccessKeyChar(value.charAt(end))) end++;
        return end == 0 ? "" : value.substring(0, end);
    }

    private static boolean isSignedNumber(String value) {
        if (value == null || value.isEmpty()) return false;
        int start = value.charAt(0) == '-' ? 1 : 0;
        return start < value.length() && isDigits(value, start);
    }

    private static boolean isUnsignedNumber(String value) {
        return value != null && !value.isEmpty() && isDigits(value, 0);
    }

    private static boolean isDigits(String value, int start) {
        for (int i = start; i < value.length(); i++) if (!isDigit(value.charAt(i))) return false;
        return true;
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isAccessKeyChar(char value) {
        return isDigit(value) || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z' || value == '-' || value == '_';
    }
}
