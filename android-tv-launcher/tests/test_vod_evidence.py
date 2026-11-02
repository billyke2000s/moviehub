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

    def test_disabled_output_cannot_pass_calibration(self):
        checked = evidence.calibration(self.tone(0))
        self.assertFalse(checked['valid'])
        self.assertFalse(checked['signalDetected'])

    def test_known_emulator_tone_is_required_not_just_any_sound(self):
        self.assertTrue(evidence.calibration(self.tone(440))['valid'])
        self.assertFalse(evidence.calibration(self.tone(1000))['valid'])

    def test_one_sounding_channel_is_reported_without_inventing_second_channel(self):
        pcm = self.tone(440)
        pcm[1::2] = array.array('h', [0] * (len(pcm) // 2))
        checked = evidence.levels(pcm)
        self.assertTrue(checked['signalDetected'])
        self.assertGreater(checked['channels'][0]['activeSeconds'], 0)
        self.assertEqual(checked['channels'][1]['activeSeconds'], 0)
