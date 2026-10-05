# Movie Hub

Movie Hub is a private, self-hosted Kodi experience for discovering films and
television, managing profiles, saving a watchlist and continuing across devices.
It uses Kodi's standard lists and dialogs, so it follows your installed skin.
Current release: **3.2.0**.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/billyke2000s/moviehub/tree/main/cloudflare)
[![Validate](https://github.com/billyke2000s/moviehub/actions/workflows/validate.yml/badge.svg)](https://github.com/billyke2000s/moviehub/actions/workflows/validate.yml)

## Install on Kodi

Add this source in **Kodi → Settings → File manager → Add source**:

```text
https://billyke2000s.github.io/moviehub/
```

Name it **Movie Hub**, then choose:

1. **Settings → Add-ons → Install from zip file**
2. **Movie Hub → repository.moviehub.zip**
3. **Install from repository → Movie Hub Repository**
4. **Video add-ons → Movie Hub → Install**

Kodi uses the repository feed for future updates automatically.
See the [complete setup guide](SETUP.md) for the Cloudflare and API steps.

## Deploy your private server

Use the **Deploy to Cloudflare** button above. Every owner receives a separate
Worker, D1 database and R2 bucket in their own Cloudflare account. After
deployment, Movie Hub asks for:

- **Private server URL** — the HTTPS `.workers.dev` address.
- **Secret code** — the private `APP_SECRET` created during deployment.

The public Kodi source URL is the same for everyone. The private server URL and
secret code are unique to each owner and must never be committed or shared.

Command-line deployment is also available in [`cloudflare/`](cloudflare/README.md).

## Interface

Movie Hub uses only standard Kodi screens:

- Kodi media lists and poster walls with proper `InfoTagVideo` metadata
- The installed skin's typography and information panels
- Kodi context menus, playback behaviour and remote conventions
- Kodi's own dialogs for sign-in, profiles and setup

## Security

- Passwords use salted PBKDF2-SHA256 with 600,000 iterations.
- Unknown accounts perform a dummy password derivation to reduce timing leaks.
- Login failures never reveal whether the username or password was incorrect.
- Secret codes and passwords are never echoed in API responses.
- Service credentials, including Trakt tokens, are encrypted at rest with AES-GCM.
- Login tokens expire after 30 days and only their SHA-256 hashes are stored.
- Every private route passes through a central application-secret guard.
- Personal data operations verify account and profile ownership.
- Authentication attempts are rate-limited through D1 across Worker isolates.
- Internal exception details remain in server logs.

See [SECURITY.md](SECURITY.md) for reporting and operational guidance.

## Repository layout

| Path | Purpose |
|---|---|
| [`kodi/plugin.video.moviehub/`](kodi/plugin.video.moviehub/) | Kodi add-on source |
| [`kodi/repository/`](kodi/repository/) | Kodi update-feed artifacts |
| [`cloudflare/`](cloudflare/) | Private Worker, migrations and deployment tools |
| [`docs/`](docs/) | Public Kodi source served by GitHub Pages |
| [`tools/`](tools/) | Release validation and deterministic repository builder |

## Development

```bash
npm install
npm run dev
npm test
```

Rebuild the Kodi package and repository feed:

```bash
python3 tools/build_repository.py \
  --url https://raw.githubusercontent.com/billyke2000s/moviehub/main \
  --addon-dir kodi/plugin.video.moviehub \
  --output kodi/repository \
  --pages-output docs
```

## Services and responsibility

Movie Hub requires users to supply their own third-party service credentials.
Users are responsible for complying with those services and accessing only
content they are legally entitled to use. Movie Hub is not affiliated with
Kodi, TMDb, Trakt or any debrid provider.

## Licence

[MIT](LICENSE)
