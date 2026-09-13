# Movie Hub setup

## 1. Install the Kodi repository

In Kodi, open **Settings → File manager → Add source** and enter:

```text
https://billyke2000s.github.io/moviehub/
```

Name the source **Movie Hub**. Then open **Settings → Add-ons → Install from
zip file**, choose **Movie Hub**, and select `repository.moviehub.zip`. Install
Movie Hub from **Movie Hub Repository → Video add-ons**.

## 2. Deploy the private server

Press **Deploy to Cloudflare** in the main README. Sign in to Cloudflare and
GitHub when asked. Cloudflare will create and bind:

- One Worker
- One D1 database
- One R2 bucket for optional profile pictures

Create two different secrets:

- `APP_SECRET`: at least 24 characters; this is the secret code entered in Kodi.
- `ENC_KEY`: at least 32 characters; this protects stored service credentials.

Save the resulting HTTPS `.workers.dev` URL.

## 3. Connect Movie Hub

Open Movie Hub and enter the private Worker URL and `APP_SECRET`. The secret
field is masked, and Movie Hub verifies both values before continuing.

Create a Movie Hub username, password and profile. These belong only to that
private Cloudflare deployment.

## 4. Add service credentials

Movie Hub asks for:

- A TMDb API key for metadata and discovery.
- One supported playback service: Premiumize, Real-Debrid or AllDebrid.

Trakt is optional. Sensitive values are masked in Kodi and encrypted by the
private Worker before storage.

## Troubleshooting

- **Kodi cannot open the source:** confirm GitHub Pages is enabled and the URL
  ends with a slash.
- **Connection details rejected:** check the complete HTTPS Worker URL and make
  sure the Kodi secret code exactly matches `APP_SECRET`.
- **Discovery does not load:** verify the TMDb key.
- **No playable sources:** verify the selected playback-service account is
  active and its API key is current. Availability is title- and provider-specific.
- **Old device feels slow:** choose Native Kodi mode or enable reduced effects.
