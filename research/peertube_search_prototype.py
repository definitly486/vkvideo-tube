#!/usr/bin/env python3
"""Probe Sepia Search and evaluate a conservative PeerTube title filter.

The script intentionally stays outside the Android application. It fetches search
JSON, video metadata and at most the beginning of an HLS master playlist. It does
not download video segments.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


SEARCH_ENDPOINT = "https://sepiasearch.org/api/v1/search/videos"
USER_AGENT = "0W-Tube PeerTube research prototype/1"
DEFAULT_CACHE = Path("/tmp/owtube-peertube-prototype-cache")
MIN_FILM_DURATION = 40 * 60
VIDEO_CODEC_PREFIXES = ("av01", "avc1", "avc3", "hev1", "hvc1", "theora", "vp8", "vp9", "vp09")
AUDIO_CODEC_PREFIXES = ("ac-3", "alac", "ec-3", "flac", "mp4a", "opus", "vorbis")
STOP_WORDS = {
    "а", "без", "в", "во", "для", "до", "за", "и", "из", "или", "к", "ко",
    "на", "не", "о", "об", "от", "по", "под", "при", "про", "с", "со", "у",
    "a", "an", "and", "for", "from", "in", "of", "on", "or", "the", "to",
}
UNWANTED = {
    "тизер", "трейлер", "обзор", "фрагмент", "отрывок", "реакция", "short",
    "shorts", "teaser", "trailer", "review", "reaction", "clip",
}
TRANSLIT = str.maketrans({
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "yo",
    "ж": "zh", "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
})


@dataclass(frozen=True)
class Film:
    rank: int
    title: str
    year: int


@dataclass(frozen=True)
class Evaluation:
    accepted: bool
    score: int
    confidence: str
    reason: str


class HttpClient:
    def __init__(
        self,
        cache_dir: Path,
        delay: float,
        use_cache: bool,
        video_password: str | None = None,
    ) -> None:
        self.cache_dir = cache_dir
        self.delay = max(0.0, delay)
        self.use_cache = use_cache
        self.video_password = video_password
        self.last_request_at = 0.0
        cache_dir.mkdir(parents=True, exist_ok=True)

    def get_bytes(
        self,
        url: str,
        *,
        limit: int | None = None,
        cache: bool = True,
        media_request: bool = False,
    ) -> bytes:
        headers = {
            "Accept": "application/json, application/vnd.apple.mpegurl, */*",
            "User-Agent": USER_AGENT,
        }
        if media_request and self.video_password:
            # PeerTube requires this header for metadata and every protected format request.
            headers["x-peertube-video-password"] = self.video_password
        cache_identity = json.dumps(
            {"url": url, "limit": limit, "headers": headers},
            sort_keys=True,
        ).encode()
        cache_path = self.cache_dir / (hashlib.sha256(cache_identity).hexdigest() + ".bin")
        if cache and self.use_cache and cache_path.exists():
            return cache_path.read_bytes()

        wait = self.delay - (time.monotonic() - self.last_request_at)
        if wait > 0:
            time.sleep(wait)
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                data = response.read(limit if limit is not None else -1)
        finally:
            self.last_request_at = time.monotonic()
        if cache and self.use_cache:
            cache_path.write_bytes(data)
        return data

    def get_json(self, url: str, *, media_request: bool = False) -> dict[str, Any]:
        return json.loads(self.get_bytes(url, media_request=media_request).decode("utf-8"))


def normalize(value: str | None) -> str:
    value = unicodedata.normalize("NFKC", (value or "").lower().replace("ё", "е"))
    return " ".join(re.findall(r"[^\W_]+", value, flags=re.UNICODE))


def transliterate(value: str) -> str:
    return normalize(normalize(value).translate(TRANSLIT))


def significant_words(value: str) -> list[str]:
    words = normalize(value).split()
    useful = [word for word in words if word not in STOP_WORDS and len(word) >= 2]
    return useful or words


def edit_distance(left: str, right: str) -> int:
    if len(left) < len(right):
        left, right = right, left
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, 1):
        current = [i]
        for j, b in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (a != b)))
        previous = current
    return previous[-1]


def token_matches(query_word: str, title_word: str) -> bool:
    if query_word == title_word:
        return True
    shorter = min(len(query_word), len(title_word))
    if shorter < 5:
        return False
    allowed = 1 if max(len(query_word), len(title_word)) <= 8 else 2
    return edit_distance(query_word, title_word) <= allowed


def ordered_matches(query_words: list[str], title_words: list[str]) -> int:
    matched = 0
    start = 0
    for query_word in query_words:
        for index in range(start, len(title_words)):
            if token_matches(query_word, title_words[index]):
                matched += 1
                start = index + 1
                break
    return matched


def contiguous_match_start(query_words: list[str], title_words: list[str]) -> int | None:
    if not query_words or len(query_words) > len(title_words):
        return None
    for start in range(len(title_words) - len(query_words) + 1):
        if all(token_matches(word, title_words[start + offset]) for offset, word in enumerate(query_words)):
            return start
    return None


def evaluate_view(query: str, title: str, duration: int, year: int) -> Evaluation:
    query_words = significant_words(query)
    title_words = significant_words(title)
    phrase_query_words = normalize(query).split()
    phrase_title_words = normalize(title).split()
    if not query_words or not title_words:
        return Evaluation(False, 0, "none", "empty title")

    unwanted = sorted(set(title_words) & UNWANTED - set(query_words))
    if unwanted:
        return Evaluation(False, -10_000, "none", "unwanted: " + ", ".join(unwanted))

    title_years = {int(value) for value in re.findall(r"\b(?:19|20)\d{2}\b", normalize(title))}
    if title_years and year not in title_years:
        return Evaluation(False, -10_000, "none", "conflicting year")
    if duration < MIN_FILM_DURATION:
        return Evaluation(False, -10_000, "none", "shorter than 40 minutes")

    ordered = ordered_matches(query_words, title_words)
    coverage = ordered / len(query_words)
    contiguous_start = contiguous_match_start(phrase_query_words, phrase_title_words)
    contiguous = contiguous_start is not None
    exact = len(phrase_query_words) == len(phrase_title_words) and contiguous
    long_form = duration >= MIN_FILM_DURATION

    score = int(coverage * 5_000)
    if contiguous:
        score += 8_000
    if exact:
        score += 8_000
    if str(year) in normalize(title).split():
        score += 500
    if long_form:
        score += min(duration // 60, 240)

    if len(query_words) == 1 and exact:
        return Evaluation(True, score, "high", "exact single-word film title")
    if len(query_words) == 1 and contiguous and str(year) in normalize(title).split():
        return Evaluation(True, score, "high", "single-word title with matching year")
    if len(query_words) == 1:
        return Evaluation(False, score, "none", "single-word title is not exact")
    if contiguous and (contiguous_start <= 1 or year in title_years):
        return Evaluation(True, score, "high", "query words are contiguous in title")
    if contiguous:
        return Evaluation(False, score, "none", "matching phrase is buried in a different title")
    return Evaluation(False, score, "none", f"matched only {ordered}/{len(query_words)} words")


def evaluate(query: str, title: str, duration: int, year: int) -> Evaluation:
    views = {
        (normalize(query), normalize(title)),
        (transliterate(query), transliterate(title)),
    }
    evaluations = [evaluate_view(q, t, duration, year) for q, t in views]
    return max(evaluations, key=lambda item: (item.accepted, item.score))


def search_url(query: str, count: int) -> str:
    params = urllib.parse.urlencode({
        "search": query,
        "count": count,
        "start": 0,
        "nsfw": "false",
        "isLive": "false",
        "durationMin": MIN_FILM_DURATION,
    })
    return SEARCH_ENDPOINT + "?" + params


def search(client: HttpClient, query: str, count: int) -> list[dict[str, Any]]:
    return list(client.get_json(search_url(query, count)).get("data") or [])


def safe_search(client: HttpClient, query: str, count: int, errors: list[str]) -> list[dict[str, Any]]:
    try:
        return search(client, query, count)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        errors.append(f"{query}: {type(error).__name__}: {error}")
        return []


def unique_results(groups: Iterable[Iterable[dict[str, Any]]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for group in groups:
        for item in group:
            key = str(item.get("url") or item.get("uuid") or "")
            if not key or key in seen:
                continue
            seen.add(key)
            result.append(item)
    return result


def http_url(value: Any, base_url: str = "") -> str | None:
    if not isinstance(value, str) or not value:
        return None
    url = urllib.parse.urljoin(base_url, value)
    parsed = urllib.parse.urlparse(url)
    return url if parsed.scheme in {"http", "https"} and parsed.hostname else None


def int_value(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def parse_m3u8_attributes(value: str) -> dict[str, str]:
    attributes: dict[str, str] = {}
    for match in re.finditer(r'(?:^|,)\s*([A-Z0-9-]+)=("(?:[^"\\]|\\.)*"|[^,]*)', value):
        raw = match.group(2).strip()
        if len(raw) >= 2 and raw[0] == raw[-1] == '"':
            raw = raw[1:-1].replace(r'\"', '"')
        attributes[match.group(1)] = raw
    return attributes


def codec_roles(value: str | None) -> tuple[str | None, str | None]:
    video_codec = None
    audio_codec = None
    for codec in (value or "").split(","):
        codec = codec.strip()
        lowered = codec.lower()
        if lowered.startswith(VIDEO_CODEC_PREFIXES):
            video_codec = codec
        elif lowered.startswith(AUDIO_CODEC_PREFIXES):
            audio_codec = codec
    return video_codec, audio_codec


def resolution_from_label(label: str) -> tuple[int, int]:
    match = re.search(r"(?:(\d+)x)?(\d+)p", label.lower())
    if not match:
        return 0, 0
    return int(match.group(1) or 0), int(match.group(2))


def format_from_file(file_: dict[str, Any], source: str) -> dict[str, Any] | None:
    file_url = http_url(file_.get("fileUrl"))
    if not file_url:
        return None
    resolution = file_.get("resolution") if isinstance(file_.get("resolution"), dict) else {}
    label = str(resolution.get("label") or "unknown")
    label_width, label_height = resolution_from_label(label)
    width = int_value(file_.get("width")) or label_width
    height = int_value(file_.get("height")) or label_height
    audio_label = label.lower() in {"0p", "audio", "audio only"} or (width == 0 and height == 0)
    explicit_video = file_.get("hasVideo") if isinstance(file_.get("hasVideo"), bool) else None
    explicit_audio = file_.get("hasAudio") if isinstance(file_.get("hasAudio"), bool) else None
    has_video = explicit_video if explicit_video is not None else (False if audio_label else bool(width or height))
    has_audio = explicit_audio if explicit_audio is not None else (True if audio_label else None)
    return {
        "formatId": label,
        "source": source,
        "url": file_url,
        "width": width,
        "height": height,
        "fps": int_value(file_.get("fps")) or 0,
        "filesize": int_value(file_.get("size")),
        "bitrate": int_value(file_.get("bitrate")),
        "hasVideo": has_video,
        "hasAudio": has_audio,
        "videoCodec": None,
        "audioCodec": None,
    }


def parse_hls_master(playlist_url: str, body: bytes) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    text = body.decode("utf-8", "replace")
    formats: list[dict[str, Any]] = []
    subtitles: list[dict[str, Any]] = []
    pending_stream: dict[str, str] | None = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line.startswith("#EXT-X-MEDIA:"):
            attrs = parse_m3u8_attributes(line.partition(":")[2])
            media_url = http_url(attrs.get("URI"), playlist_url)
            media_type = attrs.get("TYPE", "").upper()
            if media_type == "SUBTITLES" and media_url:
                subtitles.append({
                    "language": attrs.get("LANGUAGE") or attrs.get("NAME") or "und",
                    "label": attrs.get("NAME"),
                    "url": media_url,
                    "source": "hls",
                })
            elif media_type == "AUDIO" and media_url:
                formats.append({
                    "formatId": attrs.get("NAME") or "audio",
                    "source": "hls-media",
                    "url": media_url,
                    "width": 0,
                    "height": 0,
                    "fps": 0,
                    "filesize": None,
                    "bitrate": None,
                    "hasVideo": False,
                    "hasAudio": True,
                    "videoCodec": None,
                    "audioCodec": None,
                    "groupId": attrs.get("GROUP-ID"),
                })
        elif line.startswith("#EXT-X-STREAM-INF:"):
            pending_stream = parse_m3u8_attributes(line.partition(":")[2])
        elif pending_stream is not None and line and not line.startswith("#"):
            stream_url = http_url(line, playlist_url)
            if stream_url:
                resolution = pending_stream.get("RESOLUTION", "")
                match = re.fullmatch(r"(\d+)x(\d+)", resolution)
                width = int(match.group(1)) if match else 0
                height = int(match.group(2)) if match else 0
                video_codec, audio_codec = codec_roles(pending_stream.get("CODECS"))
                has_video = bool(video_codec or width or height)
                # An AUDIO group is a separate rendition. The variant URL itself is muxed only
                # when its CODECS list contains an audio codec.
                has_audio = bool(audio_codec)
                formats.append({
                    "formatId": f"{height}p" if height else "hls",
                    "source": "hls-variant",
                    "url": stream_url,
                    "width": width,
                    "height": height,
                    "fps": int_value((pending_stream.get("FRAME-RATE") or "").split(".")[0]) or 0,
                    "filesize": None,
                    "bitrate": int_value(pending_stream.get("AVERAGE-BANDWIDTH") or pending_stream.get("BANDWIDTH")),
                    "hasVideo": has_video,
                    "hasAudio": has_audio,
                    "videoCodec": video_codec,
                    "audioCodec": audio_codec,
                    "audioGroup": pending_stream.get("AUDIO"),
                })
            pending_stream = None
    return formats, subtitles


def deduplicate_dicts(items: Iterable[dict[str, Any]], keys: tuple[str, ...]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[tuple[Any, ...]] = set()
    for item in items:
        key = tuple(item.get(field) for field in keys)
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


def choose_playback_plan(formats: list[dict[str, Any]]) -> dict[str, Any] | None:
    def score(format_: dict[str, Any]) -> tuple[int, int, int, int]:
        source_score = {"hls-variant": 3, "hls-file": 2, "direct": 1}.get(str(format_.get("source")), 0)
        return (
            int(format_.get("height") or 0),
            int(format_.get("width") or 0),
            int(format_.get("bitrate") or 0),
            source_score,
        )

    muxed = [format_ for format_ in formats if format_.get("hasVideo") is True and format_.get("hasAudio") is True]
    if muxed:
        return {"mode": "muxed", "video": max(muxed, key=score), "audio": None}

    video = [format_ for format_ in formats if format_.get("hasVideo") is True]
    audio = [
        format_ for format_ in formats
        if format_.get("hasVideo") is False and format_.get("hasAudio") is True
    ]
    if video and audio:
        selected_video = max(video, key=score)
        matching_audio = [
            format_ for format_ in audio
            if selected_video.get("audioGroup")
            and format_.get("groupId") == selected_video.get("audioGroup")
        ]
        return {
            "mode": "separate",
            "video": selected_video,
            "audio": max(
                matching_audio or audio,
                key=lambda item: int(item.get("bitrate") or item.get("filesize") or 0),
            ),
        }
    return None


def inspect_video(client: HttpClient, item: dict[str, Any]) -> dict[str, Any]:
    page_url = str(item.get("url") or "")
    parsed = urllib.parse.urlparse(page_url)
    video_uuid = str(item.get("uuid") or "")
    if parsed.scheme != "https" or not parsed.hostname or not video_uuid:
        raise ValueError("invalid origin URL or UUID")

    endpoint = f"https://{parsed.hostname}/api/v1/videos/{urllib.parse.quote(video_uuid)}"
    metadata = client.get_json(endpoint, media_request=True)
    formats: list[dict[str, Any]] = []
    subtitles: list[dict[str, Any]] = []
    playlist_urls: list[str] = []
    hls_reachable = False

    for file_ in metadata.get("files") or []:
        if isinstance(file_, dict) and (format_ := format_from_file(file_, "direct")):
            formats.append(format_)

    playlists: list[dict[str, Any]] = list(metadata.get("streamingPlaylists") or [])
    for playlist in playlists:
        if not isinstance(playlist, dict):
            continue
        for file_ in playlist.get("files") or []:
            if isinstance(file_, dict) and (format_ := format_from_file(file_, "hls-file")):
                formats.append(format_)
        playlist_url = http_url(playlist.get("playlistUrl"))
        if not playlist_url:
            continue
        playlist_urls.append(playlist_url)
        try:
            playlist_body = client.get_bytes(
                playlist_url,
                limit=64 * 1024,
                media_request=True,
            )
        except (urllib.error.URLError, TimeoutError):
            continue
        if not playlist_body.lstrip().startswith(b"#EXTM3U"):
            continue
        hls_reachable = True
        parsed_formats, parsed_subtitles = parse_hls_master(playlist_url, playlist_body)
        formats.extend(parsed_formats)
        subtitles.extend(parsed_subtitles)

    captions_endpoint = endpoint.rstrip("/") + "/captions"
    try:
        captions = client.get_json(captions_endpoint, media_request=True)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        captions = {}
    for caption in captions.get("data") or []:
        if not isinstance(caption, dict):
            continue
        language = caption.get("language") if isinstance(caption.get("language"), dict) else {}
        caption_url = http_url(
            caption.get("fileUrl") or caption.get("captionPath"),
            f"https://{parsed.hostname}",
        )
        if caption_url:
            subtitles.append({
                "language": language.get("id") or "und",
                "label": language.get("label"),
                "url": caption_url,
                "hlsUrl": http_url(caption.get("m3u8Url"), f"https://{parsed.hostname}"),
                "source": "captions-api",
                "automaticallyGenerated": bool(caption.get("automaticallyGenerated")),
            })

    thumbnails: list[dict[str, Any]] = []
    for thumbnail in metadata.get("thumbnails") or []:
        if not isinstance(thumbnail, dict):
            continue
        thumbnail_url = http_url(thumbnail.get("fileUrl"), f"https://{parsed.hostname}")
        if thumbnail_url:
            thumbnails.append({
                "url": thumbnail_url,
                "width": int_value(thumbnail.get("width")) or 0,
                "height": int_value(thumbnail.get("height")) or 0,
            })
    for path in (metadata.get("thumbnailPath"), metadata.get("previewPath")):
        thumbnail_url = http_url(path, f"https://{parsed.hostname}")
        if thumbnail_url:
            thumbnails.append({"url": thumbnail_url, "width": 0, "height": 0})

    formats = deduplicate_dicts(formats, ("source", "url"))
    subtitles = deduplicate_dicts(subtitles, ("language", "url"))
    thumbnails = deduplicate_dicts(thumbnails, ("url",))
    video_formats = [format_ for format_ in formats if format_.get("hasVideo") is True]
    max_width = max((int(format_.get("width") or 0) for format_ in video_formats), default=0)
    max_height = max((int(format_.get("height") or 0) for format_ in video_formats), default=0)
    audio_only_available = any(
        format_.get("hasVideo") is False and format_.get("hasAudio") is True
        for format_ in formats
    )
    muxed_available = any(
        format_.get("hasVideo") is True and format_.get("hasAudio") is True
        for format_ in formats
    )
    playback_plan = choose_playback_plan(formats)
    return {
        "origin": parsed.hostname,
        "duration": int(metadata.get("duration") or item.get("duration") or 0),
        "maxWidth": max_width,
        "maxHeight": max_height,
        "isLive": bool(metadata.get("isLive")),
        "audioOnly": audio_only_available,
        "audioOnlyAvailable": audio_only_available,
        "muxedAvailable": muxed_available,
        "hls": bool(playlist_urls),
        "hlsReachable": hls_reachable,
        "hlsMasterUrls": playlist_urls,
        "language": (metadata.get("language") or {}).get("id"),
        "formats": formats,
        "playbackPlan": playback_plan,
        "playbackReady": playback_plan is not None,
        "subtitles": subtitles,
        "thumbnails": thumbnails,
        "passwordHeaderSent": bool(client.video_password),
    }


def load_films(path: Path) -> list[Film]:
    with path.open(encoding="utf-8", newline="") as source:
        return [Film(int(row["rank"]), row["title"], int(row["year"])) for row in csv.DictReader(source, delimiter="\t")]


def quality_label(width: int, height: int) -> str:
    if width >= 3840 or height >= 2160:
        return "4K"
    if width >= 2560 or height >= 1440:
        return "1440p"
    if width >= 1920 or height >= 1080:
        return "1080p"
    if width >= 1280 or height >= 720:
        return "720p"
    return f"{height}p" if height else "audio"


def run_film(client: HttpClient, film: Film, count: int, inspect_limit: int) -> dict[str, Any]:
    original = normalize(film.title)
    latin = transliterate(film.title)
    primary_queries = [f'"{original}"']
    if latin and latin != original:
        primary_queries.append(f'"{latin}"')

    search_errors: list[str] = []
    raw_groups = [safe_search(client, query, count, search_errors) for query in primary_queries]
    candidates = unique_results(raw_groups)
    evaluated: list[tuple[dict[str, Any], Evaluation]] = []
    for item in candidates:
        evaluation = evaluate(film.title, str(item.get("name") or ""), int(item.get("duration") or 0), film.year)
        if evaluation.accepted:
            evaluated.append((item, evaluation))

    fallback_queries: list[str] = []
    if not evaluated and latin:
        for fallback_query in dict.fromkeys((original, latin)):
            fallback_queries.append(fallback_query)
            fallback = safe_search(client, fallback_query, count, search_errors)
            candidates = unique_results([candidates, fallback])
            evaluated = []
            for item in candidates:
                evaluation = evaluate(
                    film.title,
                    str(item.get("name") or ""),
                    int(item.get("duration") or 0),
                    film.year,
                )
                if evaluation.accepted:
                    evaluated.append((item, evaluation))
            if evaluated:
                break

    evaluated.sort(key=lambda pair: pair[1].score, reverse=True)
    matches: list[dict[str, Any]] = []
    for item, evaluation in evaluated[:inspect_limit]:
        match = {
            "name": item.get("name"),
            "url": item.get("url"),
            "uuid": item.get("uuid"),
            "searchDuration": int(item.get("duration") or 0),
            "score": evaluation.score,
            "confidence": evaluation.confidence,
            "reason": evaluation.reason,
        }
        try:
            match["playback"] = inspect_video(client, item)
        except Exception as error:  # A dead federated origin is part of the result we want to measure.
            match["playbackError"] = f"{type(error).__name__}: {error}"
        matches.append(match)

    return {
        "rank": film.rank,
        "title": film.title,
        "year": film.year,
        "queries": primary_queries + fallback_queries,
        "rawCandidates": len(candidates),
        "acceptedCandidates": len(evaluated),
        "searchErrors": search_errors,
        "matches": matches,
    }


def main() -> int:
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--titles", type=Path, default=directory / "roskino_top100.tsv")
    parser.add_argument("--start", type=int, default=1, help="First 1-based list rank")
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--count", type=int, default=20, help="Results requested per Sepia query")
    parser.add_argument("--inspect-limit", type=int, default=3)
    parser.add_argument("--delay", type=float, default=0.28, help="Minimum delay between uncached HTTP requests")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument(
        "--video-password",
        help="PeerTube video password sent as x-peertube-video-password; never written to reports",
    )
    parser.add_argument("--output", type=Path, default=Path("/tmp/owtube-peertube-results.json"))
    args = parser.parse_args()

    films = [film for film in load_films(args.titles) if film.rank >= args.start][: args.limit]
    client = HttpClient(args.cache, args.delay, not args.no_cache, args.video_password)
    report: dict[str, Any] = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "searchEndpoint": SEARCH_ENDPOINT,
        "films": [],
    }

    for index, film in enumerate(films, 1):
        try:
            result = run_film(client, film, args.count, args.inspect_limit)
            report["films"].append(result)
            playable = [
                match for match in result["matches"]
                if (match.get("playback") or {}).get("playbackReady")
            ]
            if playable:
                best = max(playable, key=lambda match: (match["playback"]["maxWidth"], match["score"]))
                playback = best["playback"]
                print(
                    f"[{film.rank:3}] FOUND {quality_label(playback['maxWidth'], playback['maxHeight']):>5} "
                    f"{film.title} -> {best['name']} [{playback['origin']}]"
                )
            elif result["matches"]:
                print(f"[{film.rank:3}] DEAD?       {film.title} -> {result['matches'][0]['name']}")
            else:
                print(f"[{film.rank:3}] NONE        {film.title}")
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            print(f"[{film.rank:3}] ERROR       {film.title}: {error}", file=sys.stderr)
            report["films"].append({"rank": film.rank, "title": film.title, "year": film.year, "error": str(error)})
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    found = sum(
        bool(film.get("matches"))
        and any((match.get("playback") or {}).get("playbackReady") for match in film["matches"])
        for film in report["films"]
    )
    print(f"\nPlayable: {found}/{len(report['films'])}")
    print(f"Report: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
