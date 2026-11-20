# Run Masqueradarr and Nuvio Sports on a home server

For MovieHub on Android TV. This example runs **two independent services** on one Linux host:

- Masqueradarr on **3000**, with its own MongoDB database (not exposed to the network).
- Nuvio Live Sports on **7000**. Nuvio is built from its own upstream source.
- MovieHub continues to use its **existing local free providers**, ordinary M3U and Xtream independently. Neither hosted service replaces them.

The Ryzen 5 5625U (6 cores / 12 threads) is a suitable host for personal-use catalogue fetching and stream resolution. A starting allocation in a Proxmox Linux VM is 4 vCPU, 8 GB RAM and 25+ GB disk; monitor actual utilisation before allocating more. Throughput and RAM usage depend on active streams, upstream sites, proxying and transcoding. This deployment does **not** add video transcoding.

## Installation

Use Docker Engine with its Compose plugin on Ubuntu/Debian Linux, or in a dedicated Proxmox Linux VM. Make sure the VM presents **host CPU features**, including AVX, since MongoDB 7 requires AVX on amd64.

From this directory:

```sh
git clone https://github.com/rajhodedara/live-sport-plugin.git nuvio-source
cp .env.example .env
openssl rand -hex 32
```

Edit `.env`: replace `SERVER_LAN_IP` with **this server's actual static LAN IP**, change `MONGO_ROOT_PASS` to a strong random password, and set `NUVIO_APP_SECRET` to the hex value generated above. Keep the secret and database password private. The `.env` and `nuvio-source/` folders are ignored by MovieHub's Git rules.

```sh
chmod 600 .env
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

The build uses Nuvio's own upstream `Dockerfile` (currently Node.js 22) and Masqueradarr's published `iflip721/masqueradarr:latest` app image plus MongoDB 7. We deliberately do **not** clone or build Masqueradarr's private source submodule. If the image is temporarily unavailable, check the official upstream release instructions rather than substituting an unrelated fork.

Open these from another computer on the **same home network**, using your real IP instead of the example:

- `http://192.168.1.50:3000` — Masqueradarr setup and playlist management
- `http://192.168.1.50:7000/manifest.json` — Nuvio addon manifest
- `http://192.168.1.50:7000/configure` — optional Nuvio preferences

The Nuvio manifest must have `id: community.nuvio.live-sports` and catalogue/stream resources. Verify it before connecting MovieHub. **Do not use `localhost` in MovieHub**: that would refer to the Android TV box, not the mini PC.

## Connect the two services in MovieHub

1. In Masqueradarr's browser UI, complete admin setup, add/sync authorised channels and create a viewer if required. Grant that viewer playlist access. From the viewer's **Integration URLs**, copy the **entire personal/tokenised M3U export link**. Set Masqueradarr's **Domain** to the exact LAN origin that MovieHub can reach, e.g. `http://192.168.1.50:3000`, or links inside the playlist may point to an unreachable address.
2. On the TV, open **Settings → Setup → Masqueradarr**. Paste the **full M3U URL**, not just `http://192.168.1.50:3000`, and connect.
3. Open **Settings → Setup → Nuvio Sports**. Enter the **base server address** only: `http://192.168.1.50:7000` (no `/manifest.json`). Connect; MovieHub checks the addon identity and its catalog/stream endpoints before saving the address.
4. For these trusted local HTTP services, enable **Allow http:// links (for the whole app)** in MovieHub's existing setting. This setting affects other sources too; use HTTPS for untrusted networks. Confirm the TV and server are on mutually reachable networks.
5. Go to Live TV and refresh channels. Masqueradarr's exported channels and Nuvio's sports events/networks keep **separate source identities**. Local free-service switches remain under **Settings → Setup → Free channels**.

No credentials or private streaming URLs belong in the public GitHub repo or screenshots. MovieHub stores connections in its encrypted on-device secrets; Masqueradarr playlist URLs may grant viewing access.

## Troubleshooting and updates

```sh
docker compose ps
docker compose logs --tail=100 masqueradarr
docker compose logs --tail=100 nuvio
docker compose logs --tail=100 mongo
```

- If Nuvio doesn't connect, first open `http://SERVER-IP:7000/manifest.json` on a second device. Use the LAN IP and enable trusted HTTP links in MovieHub; don't enter the manifest path in Nuvio's form.
- If Masqueradarr returns a login page or an empty playlist, copy the *viewer M3U export* (not a dashboard URL), sync its source, and grant playlist access. Exported video URLs must also resolve to the same reachable IP/domain.
- If a stream fails, the server, upstream source or specific transport may be unavailable even when an addon catalogue loads. A successful HTTP manifest check is **not** proof of decoded audio/video on the TV.
- Restart failed containers with `docker compose restart`. On the same host, check that 3000/7000 aren't occupied. `docker compose down` keeps named volumes; **do not run `down -v`**, which deletes stored database/exports.
- To update Nuvio, run `git -C nuvio-source pull --ff-only` and then `docker compose up -d --build nuvio`. To update Masqueradarr, run `docker compose pull masqueradarr mongo` and `docker compose up -d`. Back up first: upstream releases can change configuration or database expectations.

## Security and limits

Bind these ports only on the LAN interface and restrict them with the host/VM firewall. **Do not port-forward 3000, 7000 or MongoDB** or publish a public proxy without proper authentication/TLS. Nuvio's dashboard/API is not assumed to provide access control. This stack uses plain HTTP only within a trusted home network; use a correctly configured HTTPS reverse proxy for remote clients.

This is a **deployment template**, not a verified live installation. Docker builds, current image availability, the upstream Nuvio resolver's runtime requirements, streaming endpoints, audio/video and TV playback still need testing on the actual server and TV box. Nuvio intentionally keeps its upstream security defaults; individual resolver-dependent streams may remain unavailable without additional upstream-supported configuration.
