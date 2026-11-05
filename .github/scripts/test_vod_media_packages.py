"""The parallel installer must not accept unsafe paths or unverified package bytes."""
import unittest
from vod_media_packages import download_plan, requests_from


class PackagePlanTests(unittest.TestCase):
    uri = 'https://archive.ubuntu.com/ubuntu/pool/main/p/pkg/pkg_1_amd64.deb'
    filename = 'pool/main/p/pkg/pkg_1_amd64.deb'

    def test_signed_sha256_is_forwarded_to_downloader(self):
        requests = requests_from("'%s' pkg_1_amd64.deb 42 MD5Sum:old\n" % self.uri)
        plan = download_plan(requests, 'Filename: %s\nSHA256: %s\n' % (self.filename, 'a' * 64))
        self.assertIn('checksum=sha-256=' + 'a' * 64, plan)
        self.assertIn('out=pkg_1_amd64.deb', plan)

    def test_traversal_and_configuration_injection_are_rejected(self):
        for name in ['../escape.deb', 'path/escape.deb', '--help.deb',
                     'pkg_%2fescape.deb', 'bad\n  dir=/tmp/x.deb']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                requests_from("'%s' '%s' 42 MD5Sum:old\n" % (self.uri, name))

    def test_missing_or_wrong_package_metadata_is_rejected(self):
        for metadata in ['Filename: %s\nMD5sum: old\n' % self.filename,
                         'Filename: other.deb\nSHA256: %s\n' % ('a' * 64)]:
            with self.subTest(metadata=metadata), self.assertRaises(ValueError):
                download_plan([(self.uri, 'pkg_1_amd64.deb')], metadata)

    def test_conflicting_signed_hashes_are_rejected(self):
        metadata = '\n\n'.join('Filename: %s\nSHA256: %s' % (self.filename, value * 64)
                               for value in ['a', 'b'])
        with self.assertRaises(ValueError):
            download_plan([(self.uri, 'pkg_1_amd64.deb')], metadata)

    def test_runner_mirror_transport_preserves_package_path_and_checksum(self):
        url = 'mirror+file:/etc/apt/apt-mirrors.txt/' + self.filename
        requests = requests_from("'%s' pkg_1_amd64.deb 42 MD5Sum:old\n" % url)
        metadata = 'Filename: %s\nSHA256: %s\n' % (self.filename, 'a' * 64)
        mirrors = 'http://azure.archive.ubuntu.com/ubuntu\nhttps://archive.ubuntu.com/ubuntu\n'
        plan = download_plan(requests, metadata, mirrors)
        self.assertIn('http://azure.archive.ubuntu.com/ubuntu/' + self.filename, plan)
        self.assertIn('\thttps://archive.ubuntu.com/ubuntu/' + self.filename, plan)
        self.assertIn('checksum=sha-256=' + 'a' * 64, plan)

    def test_other_local_mirror_files_and_credentialled_mirrors_are_rejected(self):
        with self.assertRaises(ValueError):
            requests_from("'mirror+file:/tmp/private/pool/pkg.deb' pkg_1_amd64.deb 42 MD5Sum:old")
        metadata = 'Filename: %s\nSHA256: %s\n' % (self.filename, 'a' * 64)
        with self.assertRaises(ValueError):
            download_plan([('mirror+file:/etc/apt/apt-mirrors.txt/' + self.filename, 'pkg_1_amd64.deb')],
                          metadata, 'https://user:pass@archive.ubuntu.com/ubuntu')


if __name__ == '__main__':
    unittest.main()
