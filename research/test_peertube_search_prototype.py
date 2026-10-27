import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("peertube_search_prototype.py")
SPEC = importlib.util.spec_from_file_location("peertube_search_prototype", MODULE_PATH)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
sys.modules[SPEC.name] = probe
SPEC.loader.exec_module(probe)


class PeerTubeFilmFilterTest(unittest.TestCase):
    def test_accepts_exact_russian_title_with_metadata(self):
        result = probe.evaluate(
            "Кин-дза-дза!",
            "Кин-дза-дза! (FullHD, комедия, реж. Георгий Данелия, 1986 г.)",
            7644,
            1986,
        )
        self.assertTrue(result.accepted)

    def test_accepts_exact_transliterated_single_word(self):
        result = probe.evaluate("Солярис", "Solaris", 10014, 1972)
        self.assertTrue(result.accepted)

    def test_rejects_short_keyword_hit(self):
        result = probe.evaluate(
            "Курьер",
            "СДЕЛАЛ Робота ЗА ЧАС! Проект Курьер Робот 2022",
            46,
            1986,
        )
        self.assertFalse(result.accepted)

    def test_rejects_approximate_foreign_word(self):
        result = probe.evaluate("Мимино", "Scuole nel mirino", 3600, 1977)
        self.assertFalse(result.accepted)

    def test_rejects_first_words_only(self):
        result = probe.evaluate(
            "Шерлок Холмс и доктор Ватсон: Знакомство",
            "The Sherlock Holmes Pub, London Ambience",
            7200,
            1979,
        )
        self.assertFalse(result.accepted)

    def test_rejects_literature_lecture(self):
        result = probe.evaluate(
            "Война и мир",
            "Осмысление роли личности и народа в истории на примере романа Л.Н. Толстого Война и мир",
            5400,
            1965,
        )
        self.assertFalse(result.accepted)

    def test_rejects_generic_talk_with_short_title_phrase(self):
        result = probe.evaluate(
            "Война и мир",
            "КОНЕЦ ИСТОРИИ: ВОЙНА, МИР И ВЕРА С ПАВЛОМ ЩЕЛИНЫМ",
            5400,
            1965,
        )
        self.assertFalse(result.accepted)

    def test_does_not_treat_opposite_stop_words_as_same_phrase(self):
        result = probe.evaluate(
            "Война и мир",
            "ВОЙНА не МИР. Психология мирного и военного времени",
            5400,
            1965,
        )
        self.assertFalse(result.accepted)

    def test_rejects_title_used_at_end_of_lecture_name(self):
        result = probe.evaluate(
            "Собачье сердце",
            "Человек с собачьим сердцем как созидатель светлого будущего: Собачье сердце",
            5400,
            1988,
        )
        self.assertFalse(result.accepted)

    def test_rejects_conflicting_year(self):
        result = probe.evaluate("Солярис", "Solaris (2024)", 7200, 1972)
        self.assertFalse(result.accepted)


class PeerTubeFormatTest(unittest.TestCase):
    def test_explicit_file_flags_override_resolution_guess(self):
        audio = probe.format_from_file({
            "fileUrl": "https://example.test/audio.mp4",
            "resolution": {"id": 0, "label": "Audio only"},
            "width": 0,
            "height": 0,
            "hasVideo": False,
            "hasAudio": True,
        }, "direct")
        self.assertFalse(audio["hasVideo"])
        self.assertTrue(audio["hasAudio"])

        old_api_video = probe.format_from_file({
            "fileUrl": "https://example.test/video.mp4",
            "resolution": {"id": 1080, "label": "1080p"},
            "width": 1920,
            "height": 1080,
        }, "hls-file")
        self.assertTrue(old_api_video["hasVideo"])
        self.assertIsNone(old_api_video["hasAudio"])

    def test_hls_master_extracts_muxed_codecs_and_subtitles(self):
        formats, subtitles = probe.parse_hls_master(
            "https://example.test/master.m3u8",
            b'''#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1569059,RESOLUTION=1920x1080,FRAME-RATE=25,CODECS="avc1.640028,mp4a.40.2",SUBTITLES="subs"
1080.m3u8
''',
        )
        self.assertEqual(len(formats), 1)
        self.assertTrue(formats[0]["hasVideo"])
        self.assertTrue(formats[0]["hasAudio"])
        self.assertEqual(formats[0]["videoCodec"], "avc1.640028")
        self.assertEqual(formats[0]["audioCodec"], "mp4a.40.2")
        self.assertEqual(subtitles[0]["language"], "en")
        self.assertEqual(subtitles[0]["url"], "https://example.test/en.m3u8")

    def test_hls_audio_group_creates_separate_playback_plan(self):
        formats, _ = probe.parse_hls_master(
            "https://example.test/master.m3u8",
            b'''#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Stereo",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.64001f",AUDIO="audio"
video.m3u8
''',
        )
        plan = probe.choose_playback_plan(formats)
        self.assertEqual(plan["mode"], "separate")
        self.assertEqual(plan["video"]["url"], "https://example.test/video.m3u8")
        self.assertEqual(plan["audio"]["url"], "https://example.test/audio.m3u8")

    def test_playback_plan_prefers_highest_muxed_variant(self):
        formats = [
            {"url": "https://example.test/360.m3u8", "source": "hls-variant", "width": 640,
             "height": 360, "bitrate": 300_000, "hasVideo": True, "hasAudio": True},
            {"url": "https://example.test/1080.m3u8", "source": "hls-variant", "width": 1920,
             "height": 1080, "bitrate": 1_500_000, "hasVideo": True, "hasAudio": True},
        ]
        plan = probe.choose_playback_plan(formats)
        self.assertEqual(plan["mode"], "muxed")
        self.assertEqual(plan["video"]["height"], 1080)


if __name__ == "__main__":
    unittest.main()
