# PeerTube Russian-film search prototype

Test date: 2026-08-11

## Goal

Estimate whether the global PeerTube index is useful as another 0W-Tube backend for
Russian films, while keeping irrelevant search results out of the TV interface.

The test set is the 100-title [Roskino list on Kinopoisk](https://www.kinopoisk.ru/lists/movies/top_100_russian_by_roskino/).
Search uses [Sepia Search](https://sepiasearch.org/), then verifies video metadata and
the beginning of the HLS master playlist directly on the originating PeerTube server.
No video segments are downloaded.

## Conservative filter

- Search the normalized Russian title and its transliteration, quoted first and
  unquoted only as a fallback.
- Ask the server only for non-live videos at least 40 minutes long.
- Treat `е` and `ё` as equivalent and allow small transliteration spelling differences.
- Reject trailers, teasers, reviews, reactions, fragments, and conflicting years.
- Require the whole title as a contiguous phrase near the beginning of the result
  title, unless the correct release year is explicitly present.
- Never use the description alone to make an irrelevant result pass.

These rules deliberately prefer false negatives over cards such as a 46-second robot
project named “Курьер”, an Italian `mirino` result for “Мимино”, or a lecture containing
“Война и мир”. Those false positives were found and eliminated during prototype tuning.

## Result

- 100 films tested.
- 1,378 raw candidates returned for 80 titles.
- 7 titles had relevant and reachable HLS results (7%).
- 6 titles had a complete film in one video (6%).
- 4 titles had a useful HD-or-better version (4%).
- “Братья Карамазовы” was found only as three separate parts.
- Two individual search variants timed out; other variants for those titles completed.
- All accepted results had a reachable HLS master playlist at test time.

| Rank | Film | Best result | Duration | Origin |
| ---: | --- | ---: | ---: | --- |
| 1 | [Андрей Рублев](https://retvrn.tv/videos/watch/210d2d58-5ace-4205-8315-f9d5152107a2) | 2160p | 3:02:38 | `retvrn.tv` |
| 4 | [Летят журавли](https://tube.azbyka.ru/videos/watch/ae569137-3693-4a72-97a9-6ad6c4b6b65f) | 1080p | 1:36:51 | `tube.azbyka.ru` |
| 46 | [Кин-дза-дза!](https://peertube.terranout.mine.nu/videos/watch/bde0ec8d-dbbe-464f-b61e-ef32d8939393) | 1080p | 2:07:24 | `peertube.terranout.mine.nu` |
| 54 | [Солярис](https://pablo.tube/videos/watch/b7f31ce1-5242-4f83-87cf-bc2de3cb24ca) | 136p | 2:46:54 | `pablo.tube` |
| 63 | [Сталкер](https://tube-action-educative.apps.education.fr/videos/watch/754f1c91-cff7-449e-a89d-d5c570433ce8) | 1080p | 2:35:28 | `tube-action-educative.apps.education.fr` |
| 73 | [Пять вечеров](https://tube.kx-home.su/videos/watch/ccc1af18-6901-48d8-a4cc-9cebe32aac81) | 360p | 1:38:03 | `tube.kx-home.su` |
| 92 | [Братья Карамазовы, часть 1](https://tube.azbyka.ru/videos/watch/3d4881e0-b8d3-4512-8c0e-a2601ac63f96) | 392p, 3 parts | 3:42:33 total | `tube.azbyka.ru` |

## Conclusion

PeerTube is technically straightforward to support, but the global index is a weak
source for this use case. Strict filtering reduces 1,378 raw candidates to seven
credible titles, and only four have quality that is attractive on a 1080p projector.

The sensible product shape is therefore an optional, low-priority backend. It should
search after the existing backends, append its few accepted results asynchronously,
and never delay RUTUBE, VK Video, or Dzen. Running four Sepia queries for every user
search is not justified by the measured recall; a production version should use one
Russian query first and try transliteration only when needed.

## Media inspection

The prototype incorporates the useful parts of yt-dlp's PeerTube extractor without
depending on yt-dlp at runtime:

- direct files and every `streamingPlaylists` entry;
- HLS variants, codec declarations, bitrates, frame rates, and separate audio groups;
- explicit `hasVideo`/`hasAudio` flags, with conservative fallback for older servers;
- a muxed or separate-track playback plan instead of assuming every MP4 contains audio;
- captions from both the PeerTube captions API and HLS manifests;
- all advertised thumbnail sizes;
- live status and the `x-peertube-video-password` header for protected videos.

All 11 accepted video entries received a reachable HLS manifest and a valid playback
plan. A password-protected upstream PeerTube test video was also inspected successfully.
This specifically avoids the current yt-dlp failure mode where a video-only file can be
mistaken for a muxed file and played without sound.

## Reproduce

```sh
python3 research/peertube_search_prototype.py \
  --output /tmp/owtube-peertube-results.json
python3 -m unittest research/test_peertube_search_prototype.py
```

The HTTP cache defaults to `/tmp/owtube-peertube-prototype-cache`. Pass `--no-cache`
to repeat every network request or `--start`/`--limit` to test a smaller slice.
