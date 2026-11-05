"""Regression checks for actual audio-output evidence, never provider success fixtures."""
import array
import importlib.util
import math
from pathlib import Path
import unittest

_spec = importlib.util.spec_from_file_location('vod_evidence', Path(__file__).with_name('review-vod-evidence.py'))
evidence = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(evidence)


class EvidenceTests(unittest.TestCase):
    def tone(self, frequency):
        return array.array('h', (int(8000 * math.sin(2 * math.pi * frequency * (i // 2) / 16000))
                                for i in range(64000)))

    def test_silent_samples_cannot_pass_signal_calibration(self):
        checked = evidence.calibration(self.tone(0))
        self.assertFalse(checked['valid'])
        self.assertFalse(checked['signalDetected'])

    def test_known_decoder_calibration_tone_is_required_not_just_any_sound(self):
        self.assertTrue(evidence.calibration(self.tone(440))['valid'])
        self.assertFalse(evidence.calibration(self.tone(1000))['valid'])

    def test_one_sounding_channel_is_reported_without_inventing_second_channel(self):
        pcm = self.tone(440)
        pcm[1::2] = array.array('h', [0] * (len(pcm) // 2))
        checked = evidence.levels(pcm)
        self.assertTrue(checked['signalDetected'])
        self.assertGreater(checked['channels'][0]['activeSeconds'], 0)
        self.assertEqual(checked['channels'][1]['activeSeconds'], 0)

    def test_short_fragment_does_not_round_up_to_half_second_of_signal(self):
        checked = evidence.levels(self.tone(440)[:1600])
        self.assertAlmostEqual(checked['seconds'], .05)
        self.assertAlmostEqual(checked['channels'][0]['activeSeconds'], .05)
        self.assertFalse(checked['signalDetected'])

    def test_selected_audio_requires_half_the_requested_eight_second_window(self):
        track = dict(seconds=.6613, signalDetected=True, language='eng')
        self.assertFalse(evidence.selected_audio_signal([track], 'eng', True))
        track['seconds'] = 4
        self.assertTrue(evidence.selected_audio_signal([track], 'eng', True))
        self.assertFalse(evidence.selected_audio_signal([track], 'eng', False))
        track['signalDetected'] = False
        self.assertFalse(evidence.selected_audio_signal([track], 'eng', True))

    def test_other_language_cannot_supply_selected_tracks_missing_signal(self):
        tracks = [dict(seconds=8, signalDetected=False, language='eng'),
                  dict(seconds=8, signalDetected=True, language='hin')]
        self.assertFalse(evidence.selected_audio_signal(tracks, 'en', True))
        self.assertTrue(evidence.selected_audio_signal(tracks, 'hi', True))

    def test_single_known_other_language_cannot_verify_selected_audio(self):
        track = dict(seconds=8, signalDetected=True, language='hin')
        self.assertFalse(evidence.selected_audio_signal([track], 'eng', True))
        self.assertTrue(evidence.selected_audio_signal([track], 'hi', True))

    def test_single_unlabelled_track_remains_usable_without_claiming_its_language(self):
        for label in ('', 'und', 'mis'):
            track = dict(seconds=8, signalDetected=True, language=label)
            with self.subTest(language=label):
                self.assertTrue(evidence.selected_audio_signal([track], 'eng', True))

    def test_single_track_with_unknown_selection_can_establish_signal(self):
        track = dict(seconds=8, signalDetected=True, language='hin')
        self.assertTrue(evidence.selected_audio_signal([track], '', True))

    def test_regional_language_tags_match_the_same_selected_language(self):
        for selected, captured in [('en-GB', 'eng'), ('eng', 'en_US'), (' English ', 'en')]:
            track = dict(seconds=8, signalDetected=True, language=captured)
            with self.subTest(selected=selected, captured=captured):
                self.assertTrue(evidence.selected_audio_signal([track], selected, True))
