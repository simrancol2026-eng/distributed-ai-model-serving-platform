import io
from pathlib import Path
import struct
import tempfile
import unittest
import wave
import zipfile
from audit import audit, read_audio, sound_label


def wav(value):
    out = io.BytesIO()
    with wave.open(out, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(4000)
        w.writeframes(struct.pack('<h', value) * 4000)
    return out.getvalue()


class AuditTests(unittest.TestCase):
    def test_annotations_do_not_map_unknown_sounds_to_normal(self):
        self.assertIsNone(sound_label('BRONCHIAL'))
        self.assertIsNone(sound_label('I C B'))
        self.assertEqual(sound_label('I C E W'), 'both')
        self.assertEqual(sound_label('Crep'), 'crackles')
        self.assertEqual(sound_label('N'), 'neither')

    def test_duplicate_conflicts_quarantine_whole_groups(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'data.zip'
            with zipfile.ZipFile(path, 'w') as z:
                for group in range(1, 17):
                    for variant, offset in [('B', 0), ('D', 100), ('E', 200)]:
                        value = group + offset
                        if variant == 'B' and group == 9:
                            value = 1  # Identical PCM, conflicting normal/wheeze groups.
                        sound = 'N' if group <= 8 else 'E W'
                        z.writestr(f'Audio Files/{variant}P{group}_label,{sound},P L L,40,M.wav', wav(value))
            first = audit(path); second = audit(path)
            self.assertEqual(first, second)
            self.assertTrue(all(r['partition'] == 'excluded' for r in first['rows']
                                if r['group'] in {'1', '9'}))
            for field in ('group', 'pcm_sha256'):
                a = {r[field] for r in first['rows'] if r['partition'] == 'development'}
                b = {r[field] for r in first['rows'] if r['partition'] == 'heldout'}
                self.assertTrue(a and b)
                self.assertFalse(a & b)

    def test_bad_audio_rejected(self):
        with self.assertRaises((wave.Error, EOFError)):
            read_audio(b'not a WAV')


if __name__ == '__main__':
    unittest.main()
