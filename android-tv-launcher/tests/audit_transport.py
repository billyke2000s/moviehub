"""Keep unattended diagnostics from uploading provider material to an external decoder."""
from urllib.parse import urlparse

from moviehub_compat import client


def block_external_decoder():
    original = client.request

    def request(url, *args, **kwargs):
        host = (urlparse(url).hostname or '').lower()
        if host == 'enc-dec.app' or host.endswith('.enc-dec.app'):
            raise PermissionError('External decoder validation requires explicit approval')
        return original(url, *args, **kwargs)

    client.request = request
