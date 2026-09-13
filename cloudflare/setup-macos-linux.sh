#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

command -v node >/dev/null 2>&1 || { echo "Install Node.js LTS first: https://nodejs.org"; exit 1; }
echo "Movie Hub private deployment"
echo "A browser will open so you can sign in to Cloudflare."
npm install
npx wrangler login

npx wrangler d1 create moviehub --update-config --binding DB --location weur
npx wrangler r2 bucket create moviehub-avatars

app_secret=$(node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))")
enc_key=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))")
printf '%s' "$app_secret" | npx wrangler secret put APP_SECRET
printf '%s' "$enc_key" | npx wrangler secret put ENC_KEY
npx wrangler d1 migrations apply DB --remote
npx wrangler deploy

printf 'Paste the HTTPS workers.dev address displayed above: '
read -r worker_url
worker_url=${worker_url%/}
repo_url="https://billyke2000s.github.io/moviehub/"
{
  echo "MOVIE HUB IS READY"
  echo
  echo "Kodi source URL: $repo_url"
  echo "Private server URL: $worker_url"
  echo "Secret code: $app_secret"
  echo
  echo "Keep this file private. Kodi asks for the private server URL and secret code once."
} > deployment-details.txt
chmod 600 deployment-details.txt
echo "Complete. Open deployment-details.txt for the three values you need."
