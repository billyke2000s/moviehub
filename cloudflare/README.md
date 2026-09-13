# Movie Hub private server

This folder contains the Cloudflare Worker used for accounts, profiles,
watchlists and viewing progress. It does not proxy video.

The recommended setup is the repository's **Deploy to Cloudflare** button.

For a local command-line deployment:

- Windows: run `setup-windows.ps1`
- macOS or Linux: run `sh setup-macos-linux.sh`

The scripts create strong secrets locally and write the private connection
details to `deployment-details.txt`, which is excluded from Git.

Never commit `APP_SECRET`, `ENC_KEY`, Wrangler credentials or deployment details.
