package ru.tubetv.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Channel bookmarks shown on the "Каналы" tab. */
final class Channels {
    static final class Bookmark {
        final String title;
        final String handle;
        final String url;

        Bookmark(String title, String handle) {
            this.title = title;
            this.handle = handle;
            this.url = "https://vkvideo.ru/@" + handle;
        }
    }

    static final List<Bookmark> ALL = Collections.unmodifiableList(Arrays.asList(
            new Bookmark("@be_on_edge", "be_on_edge"),
            new Bookmark("@36nfl", "36nfl"),
            new Bookmark("@36hockey", "36hockey")));

    static Bookmark byUrl(String url) {
        if (url == null) return null;
        for (Bookmark bookmark : ALL) if (bookmark.url.equals(url)) return bookmark;
        return null;
    }

    private Channels() { }
}
