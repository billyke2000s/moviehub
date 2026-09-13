# Security

## Reporting a vulnerability

Please do not publish credentials, exploit details or personal data in a public
issue. Contact the repository owner privately through their GitHub profile with
a concise description, affected version and reproduction steps.

## Secrets

Never commit `APP_SECRET`, `ENC_KEY`, API tokens, `deployment-details.txt`,
`.dev.vars` or Wrangler authentication files. Rotate a secret immediately if it
is disclosed. Changing `ENC_KEY` makes values encrypted with the previous key
unreadable, so re-enter those service credentials after rotation.

## Deployment boundaries

Each owner should deploy their own Worker, D1 database and R2 bucket. Do not
reuse another person's Worker or share an account database between unrelated
households. Use the generated HTTPS `workers.dev` address or a correctly
configured HTTPS custom domain.

## Supported versions

Security fixes are applied to the latest published version only. Kodi repository
updates should remain enabled.
