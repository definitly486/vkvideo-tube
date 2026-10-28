package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class VkWebClient {
    private static final int LIMIT = 12;
    private static final int MAX_PAGES = 4;
    private static final int CHANNEL_LIMIT = 30;
    private static final int TIMEOUT_MS = 10_000;
    private static final String CLIENT_ID = "52461373";
    private static final String API_VERSION = "5.282";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";
    private static String anonymousToken;
    private static long tokenExpiresAt;

    static JSONObject getVideoById(String videoId) throws Exception {
        return getVideoById(videoId, true);
    }

    private static JSONObject getVideoById(String videoId, boolean retryToken) throws Exception {
        String address = "https://api.vk.com/method/video.getByIds"
                + "?v=" + API_VERSION
                + "&client_id=" + CLIENT_ID;
        String body = "access_token=" + Uri.encode(getAnonymousToken())
                + "&videos=" + Uri.encode(videoId)
                + "&video_fields=" + Uri.encode("files");
        JSONObject root = postJson(address, body);
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            if (retryToken && isAuthorizationError(root)) {
                clearAnonymousToken();
                return getVideoById(videoId, false);
            }
            throw new Exception("VK Video: "
                    + error.optString("error_msg", "ошибка получения потока"));
        }
        JSONObject response = root.optJSONObject("response");
        JSONArray items = response == null ? null : response.optJSONArray("items");
        JSONObject video = items == null ? null : items.optJSONObject(0);
        if (video == null) throw new Exception("VK Video не отдал данные ролика");
        return video;
    }

    interface PageListener {
        boolean onPage(List<VideoItem> items);
    }

    List<VideoItem> search(String query, int minWidth, int thumbnailWidth) throws Exception {
        List<VideoItem> result = new ArrayList<>();
        searchPages(query, minWidth, thumbnailWidth, page -> {
            result.addAll(page);
            return true;
        });
        if (minWidth <= 0 || result.isEmpty()) return result;
        return SearchClient.filterByQuality(result, minWidth);
    }

    int searchPages(String query, int minWidth, int thumbnailWidth,
                    PageListener listener) throws Exception {
        String address = "https://api.vkvideo.ru/method/catalog.getVideoSearchWeb2"
                + "?v=" + API_VERSION
                + "&client_id=" + CLIENT_ID
                + "&count=30"
                + "&q=" + Uri.encode(query)
                + "&content_type=video"
                + (minWidth > 0 ? "&hd=1" : "");
        JSONObject root = authorizedJson(address, null);
        JSONObject response = response(root);
        Set<String> seen = new LinkedHashSet<>();
        int total = 0;
        int pages = 0;
        String previousNext = "";
        while (response != null && total < LIMIT && pages++ < MAX_PAGES
                && !Thread.currentThread().isInterrupted()) {
            List<VideoItem> page = parsePage(response, thumbnailWidth, seen, LIMIT - total);
            total += page.size();
            if (!page.isEmpty() && !listener.onPage(page)) break;
            PageCursor cursor = pageCursor(response);
            if (cursor == null || cursor.nextFrom.equals(previousNext) || total >= LIMIT) break;
            previousNext = cursor.nextFrom;
            String body = "section_id=" + Uri.encode(cursor.sectionId)
                    + "&start_from=" + Uri.encode(cursor.nextFrom);
            root = authorizedJson("https://api.vkvideo.ru/method/catalog.getSection"
                    + "?v=" + API_VERSION + "&client_id=" + CLIENT_ID, body);
            response = response(root);
        }
        return total;
    }

    private static JSONObject authorizedJson(String address, String body) throws Exception {
        JSONObject root = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = "access_token=" + Uri.encode(getAnonymousToken());
            root = body == null
                    ? getJson(address + "&" + token)
                    : postJson(address, body + "&" + token);
            if (!isAuthorizationError(root) || attempt == 1) return root;
            clearAnonymousToken();
        }
        return root;
    }

    static boolean isAuthorizationError(JSONObject root) {
        JSONObject error = root == null ? null : root.optJSONObject("error");
        if (error == null) return false;
        if (error.optInt("error_code") == 5) return true;
        return error.optString("error_msg").toLowerCase(Locale.ROOT)
                .contains("authorization failed");
    }

    private static JSONObject response(JSONObject root) throws Exception {
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            throw new Exception("VK Video: "
                    + error.optString("error_msg", "ошибка поиска"));
        }
        JSONObject response = root.optJSONObject("response");
        if (response == null) throw new Exception("VK Video не отдал результаты");
        return response;
    }

    private static List<VideoItem> parsePage(JSONObject response, int thumbnailWidth,
                                             Set<String> seen, int limit) {
        LinkedHashMap<String, JSONObject> videos = new LinkedHashMap<>();
        JSONArray catalogVideos = response.optJSONArray("catalog_videos");
        if (catalogVideos != null) {
            for (int i = 0; i < catalogVideos.length(); i++) {
                JSONObject wrapper = catalogVideos.optJSONObject(i);
                JSONObject video = wrapper == null ? null : wrapper.optJSONObject("video");
                if (video != null) videos.put(key(video), video);
            }
        }

        Set<String> orderedIds = new LinkedHashSet<>();
        JSONObject catalog = response.optJSONObject("catalog");
        collectOrderedIds(catalog == null ? null : catalog.optJSONArray("sections"), orderedIds);
        collectOrderedIds(response.optJSONObject("section"), orderedIds);
        orderedIds.addAll(videos.keySet());

        List<VideoItem> result = new ArrayList<>();
        for (String id : orderedIds) {
            JSONObject video = videos.get(id);
            if (video == null || !seen.add(id)) continue;
            result.add(itemFromVideo(video, thumbnailWidth));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static PageCursor pageCursor(JSONObject response) {
        JSONObject section = response.optJSONObject("section");
        if (section == null) {
            JSONObject catalog = response.optJSONObject("catalog");
            JSONArray sections = catalog == null ? null : catalog.optJSONArray("sections");
            section = sections == null ? null : sections.optJSONObject(0);
        }
        if (section == null) return null;
        String sectionId = section.optString("id");
        String nextFrom = section.optString("next_from");
        return sectionId.isEmpty() || nextFrom.isEmpty()
                ? null : new PageCursor(sectionId, nextFrom);
    }

    private static synchronized String getAnonymousToken() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        if (anonymousToken != null && now + 60 < tokenExpiresAt) return anonymousToken;
        JSONObject root = postJson("https://login.vk.com/?act=get_anonym_token", "client_id=" + CLIENT_ID);
        if (!"okay".equals(root.optString("type"))) throw new Exception("VK Video не выдал анонимную сессию");
        JSONObject data = root.optJSONObject("data");
        anonymousToken = data == null ? null : data.optString("access_token", null);
        tokenExpiresAt = data == null ? 0 : data.optLong("expired_at");
        if (anonymousToken == null || anonymousToken.isEmpty()) throw new Exception("VK Video не выдал анонимную сессию");
        return anonymousToken;
    }

    private static synchronized void clearAnonymousToken() {
        anonymousToken = null;
        tokenExpiresAt = 0;
    }

    private static void collectOrderedIds(JSONArray sections, Set<String> result) {
        if (sections == null) return;
        for (int i = 0; i < sections.length(); i++) {
            collectOrderedIds(sections.optJSONObject(i), result);
        }
    }

    private static void collectOrderedIds(JSONObject section, Set<String> result) {
        JSONArray blocks = section == null ? null : section.optJSONArray("blocks");
        if (blocks == null) return;
        for (int j = 0; j < blocks.length(); j++) {
            JSONObject block = blocks.optJSONObject(j);
            JSONArray ids = block == null ? null : block.optJSONArray("videos_ids");
            if (ids == null) continue;
            for (int k = 0; k < ids.length(); k++) {
                result.add(ids.optString(k));
            }
        }
    }

    private static String key(JSONObject video) {
        return video.optLong("owner_id") + "_" + video.optLong("id");
    }

    private static int[] maxAvailableDimensions(JSONObject video) {
        int bestHeight = 0;
        JSONObject files = video.optJSONObject("files");
        if (files != null) {
            java.util.Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String url = files.optString(key);
                if (!key.startsWith("mp4_")
                        || !(url.startsWith("http") || url.startsWith("//"))) continue;
                try {
                    bestHeight = Math.max(bestHeight, Integer.parseInt(key.substring(4)));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (bestHeight > 0) return new int[]{standardWidth(bestHeight), bestHeight};
        return new int[]{Math.max(0, video.optInt("width")),
                Math.max(0, video.optInt("height"))};
    }

    private static int standardWidth(int height) {
        switch (height) {
            case 144: return 256;
            case 240: return 426;
            case 360: return 640;
            case 480: return 854;
            case 720: return 1280;
            case 1080: return 1920;
            case 1440: return 2560;
            case 2160: return 3840;
            default: return Math.round(height * 16f / 9f);
        }
    }

    private static String bestImage(JSONArray images, int targetWidth) {
        if (images == null) return "";
        String smallestSuitable = "";
        int smallestSuitableWidth = Integer.MAX_VALUE;
        String largestFallback = "";
        int largestFallbackWidth = 0;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            int width = image.optInt("width");
            String url = image.optString("url");
            if (url.isEmpty() || width <= 0) continue;
            if (width > largestFallbackWidth) {
                largestFallbackWidth = width;
                largestFallback = url;
            }
            if (width >= targetWidth && width < smallestSuitableWidth) {
                smallestSuitableWidth = width;
                smallestSuitable = url;
            }
        }
        return smallestSuitable.isEmpty() ? largestFallback : smallestSuitable;
    }

    private static VideoItem itemFromVideo(JSONObject video, int thumbnailWidth) {
        String page = "https://vkvideo.ru/video" + VkVideoId.fromVideo(video);
        String thumbnail = bestImage(video.optJSONArray("image"), thumbnailWidth);
        if (thumbnail.isEmpty()) {
            thumbnail = video.optString("photo_800", video.optString("photo_320", ""));
        }
        VideoItem item = new VideoItem("VK VIDEO", video.optString("title", "Видео VK"),
                "", thumbnail, page, page, video.optLong("duration") * 1000L);
        int[] dimensions = maxAvailableDimensions(video);
        return item.withQuality(dimensions[0], dimensions[1]);
    }

    /**
     * Videos of a public VK Video channel such as https://vkvideo.ru/@be_on_edge.
     * First the anonymous API is asked for the owner's video list; if that is refused,
     * the public channel page is scanned for video IDs, which are then resolved through
     * video.getByIds exactly like a single video.
     */
    List<VideoItem> channelVideos(String handle, int thumbnailWidth) throws Exception {
        Exception apiError = null;
        try {
            Long ownerId = resolveOwnerId(handle);
            if (ownerId != null) {
                List<VideoItem> videos = ownerVideos(ownerId, thumbnailWidth);
                if (!videos.isEmpty()) return videos;
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            apiError = e;
        }
        try {
            List<VideoItem> videos = pageVideos(handle, thumbnailWidth);
            if (!videos.isEmpty()) return videos;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            if (apiError == null) apiError = e;
        }
        throw new Exception("Не удалось загрузить канал @" + handle
                + (apiError == null || apiError.getMessage() == null
                ? "" : ": " + apiError.getMessage()));
    }

    private static Long resolveOwnerId(String handle) throws Exception {
        JSONObject root = authorizedJson("https://api.vkvideo.ru/method/utils.resolveScreenName"
                + "?v=" + API_VERSION + "&client_id=" + CLIENT_ID
                + "&screen_name=" + Uri.encode(handle), null);
        JSONObject response = response(root);
        long objectId = response.optLong("object_id");
        if (objectId <= 0) return null;
        String type = response.optString("type");
        return "user".equals(type) ? objectId : -objectId;
    }

    private static List<VideoItem> ownerVideos(long ownerId, int thumbnailWidth) throws Exception {
        JSONObject root = authorizedJson("https://api.vkvideo.ru/method/video.get"
                + "?v=" + API_VERSION + "&client_id=" + CLIENT_ID
                + "&owner_id=" + ownerId + "&count=" + CHANNEL_LIMIT, null);
        return parseVideoArray(response(root).optJSONArray("items"), thumbnailWidth);
    }

    private static List<VideoItem> pageVideos(String handle, int thumbnailWidth) throws Exception {
        String html = getText("https://vkvideo.ru/@" + Uri.encode(handle));
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("video(-?\\d+)_(\\d+)").matcher(html);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        java.util.HashMap<String, Integer> ownerCounts = new java.util.HashMap<>();
        while (matcher.find() && ids.size() < CHANNEL_LIMIT * 4) {
            if (ids.add(matcher.group(1) + "_" + matcher.group(2))) {
                Integer previous = ownerCounts.get(matcher.group(1));
                ownerCounts.put(matcher.group(1), previous == null ? 1 : previous + 1);
            }
        }
        // Related-video blocks belong to other owners; keep the channel's own videos.
        String owner = null;
        int best = 0;
        for (java.util.Map.Entry<String, Integer> entry : ownerCounts.entrySet()) {
            if (entry.getValue() > best) { best = entry.getValue(); owner = entry.getKey(); }
        }
        if (owner == null) return new ArrayList<>();
        StringBuilder wanted = new StringBuilder();
        int count = 0;
        for (String id : ids) {
            if (!id.startsWith(owner + "_") || count >= CHANNEL_LIMIT) continue;
            if (count++ > 0) wanted.append(',');
            wanted.append(id);
        }
        String address = "https://api.vkvideo.ru/method/video.getByIds"
                + "?v=" + API_VERSION + "&client_id=" + CLIENT_ID;
        JSONObject root = authorizedJson(address, "videos=" + Uri.encode(wanted.toString())
                + "&video_fields=" + Uri.encode("files"));
        JSONObject response = response(root);
        return parseVideoArray(response.optJSONArray("items"), thumbnailWidth);
    }

    private static List<VideoItem> parseVideoArray(JSONArray array, int thumbnailWidth) {
        List<VideoItem> result = new ArrayList<>();
        if (array == null) return result;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject video = array.optJSONObject(i);
            if (video == null || !seen.add(key(video))) continue;
            result.add(itemFromVideo(video, thumbnailWidth));
        }
        return result;
    }

    private static String getText(String address) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure((HttpURLConnection) new URL(address).openConnection());
        connection.setRequestProperty("Accept", "text/html,*/*");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Video: HTTP " + code);
            return BoundedHttpReader.readText(connection.getInputStream(), BoundedHttpReader.PAGE_LIMIT);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject getJson(String address) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure((HttpURLConnection) new URL(address).openConnection());
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Video: HTTP " + code);
            return new JSONObject(read(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject postJson(String address, String body) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure((HttpURLConnection) new URL(address).openConnection());
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("Origin", "https://vkvideo.ru");
        connection.setRequestProperty("Referer", "https://vkvideo.ru/");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Video: HTTP " + code);
            return new JSONObject(read(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection configure(HttpURLConnection connection) {
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json,*/*");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        return BoundedHttpReader.readText(connection.getInputStream(), BoundedHttpReader.JSON_LIMIT);
    }

    private static final class PageCursor {
        final String sectionId;
        final String nextFrom;

        PageCursor(String sectionId, String nextFrom) {
            this.sectionId = sectionId;
            this.nextFrom = nextFrom;
        }
    }

}
