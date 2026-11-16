import unittest
from cryptography.fernet import Fernet, InvalidToken
from backup_archive import seal, unseal


class BackupArchiveTest(unittest.TestCase):
    def test_binary_dump_and_configuration_round_trip(self):
        key = Fernet.generate_key()
        files = {'database.dump': b'PGDMP\x00\xff', 'secrets/runtime-password': b'private'}
        blob = seal(files, {'scope': 'LOCAL_ONLY'}, key)
        self.assertNotIn(b'private', blob)
        self.assertEqual(unseal(blob, key), (files, {'scope': 'LOCAL_ONLY'}))

    def test_wrong_key_and_tampering_rejected_before_extraction(self):
        key = Fernet.generate_key()
        blob = seal({'database.dump': b'PGDMP'}, {}, key)
        with self.assertRaises(InvalidToken):
            unseal(blob, Fernet.generate_key())
        with self.assertRaises(InvalidToken):
            unseal(blob[:30] + bytes([blob[30] ^ 1]) + blob[31:], key)

    def test_paths_must_be_relative_and_unambiguous(self):
        for path in ('../secret', '/tmp/secret', 'a/../secret', 'a//b', 'a\\b', 'metadata.json'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                seal({path: b'private'}, {}, Fernet.generate_key())


if __name__ == '__main__':
    unittest.main()
