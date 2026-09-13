# Movie Hub 3.0.0

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
