# Movie Hub 3.2.0

## Standard Kodi look only

- Removed the custom "Cinematic" interface (custom home/details windows and
  their skin files). Movie Hub now uses only standard Kodi lists and dialogs,
  so it always follows your installed skin.
- Login, sign-up and profile picking now use Kodi's own dialogs.
- Removed the interface/trailer-preview/reduced-motion first-run questions.

## Playback fixes

- Every link in the source list is now selectable and playable. Previously,
  with Real-Debrid or AllDebrid every link was added as a non-playable
  "send to debrid" item, because both services removed their cache-check APIs.
- Real-Debrid: waits for magnet conversion, selects the actual video file
  (not samples), picks the right episode from season packs and removes
  failed/uncached torrents from your account.
- AllDebrid: updated to the current API (Bearer auth, `magnet/files`,
  `ready` flag); the removed `magnet/instant` call is no longer used.
- Playback no longer keeps the plugin running for the whole film. Progress
  sync, resume, Trakt and Up Next moved to a background service.
- Playing a link no longer runs the full login/profile flow (several server
  calls and possible dialogs) while Kodi waits for the stream.
- A temporary server error no longer wipes your saved debrid key.
- Source sites are queried in parallel with shorter timeouts; you now get a
  clear message if none of them answer.
- Fixed finished Premiumize transfers being added as folders that could
  never open.

# Movie Hub 3.1.0

## Security and deployment maintenance

- Made the Cloudflare project self-contained for reliable one-click deployment.
- Added expiry and database hashing for login tokens.
- Moved rate limiting from Worker memory into D1.
- Encrypted Trakt tokens and masked credentials in Kodi settings.
- Allowed Premiumize, Real-Debrid or AllDebrid during onboarding.
- Prevented credentials carrying between profiles.
- Removed all profile-owned data when a profile is deleted.
- Added deterministic release packaging and security behaviour tests.

## Movie Hub 3.0.0

## Two complete television experiences

- Added a custom cinematic home with live artwork and curated shelves.
- Added a first-run choice between Cinematic and fully native Kodi modes.
- Added manual, automatic and disabled trailer-preview choices.
- Added reduced-motion support for older devices.
- Added guided HTTPS server setup and connection testing.
- Added a self-hosted Kodi update repository.
- Added dedicated Films, Television, Search and My List views.
- Added a custom title screen with synopsis, rating, runtime, genres, trailer,
  watchlist controls, cast access and recommendations.
- Reworked focus states for directional remotes and gamepads.
- Replaced emoji and generic app styling with a restrained black-and-brass
  visual identity.
- Kept a compact compatibility menu under Account for maintenance features.

## Server, deployment and account improvements

- Enforced authentication centrally for every private endpoint.
- Encrypted account-level TMDb and Premiumize keys at rest.
- Increased new password hashes to 600,000 PBKDF2-SHA256 iterations.
- Added automatic password-hash upgrades after a successful login.
- Prevented unconfigured server secrets from silently authorising requests.
- Stopped internal exception details being returned to clients.
- Added Windows and macOS/Linux guided private-deployment launchers.
- Added per-owner hosting for repository metadata and automatic updates.

Existing accounts, profiles, history and watchlists remain compatible.
