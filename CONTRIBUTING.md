# Contributing

Issues and focused pull requests are welcome. Keep television navigation usable
with only directional buttons, Select and Back. New cinematic features must
retain an equivalent Native Kodi route and must not make network calls from the
Cloudflare server on behalf of video playback.

Before opening a pull request, run `npm test`, verify every Kodi XML document
parses, and confirm no credentials or generated deployment files are present.
Update the add-on version and rebuild `kodi/repository/` when publishing a release.
