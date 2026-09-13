$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  throw "Install Node.js LTS from https://nodejs.org, then run this file again."
}
Write-Host "Movie Hub private deployment" -ForegroundColor Cyan
Write-Host "A browser will open so you can sign in to Cloudflare."
npm install
npx wrangler login

Write-Host "Creating private storage..."
npx wrangler d1 create moviehub --update-config --binding DB --location weur
npx wrangler r2 bucket create moviehub-avatars

$appSecret = node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"
$encKey = node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
$appSecret | npx wrangler secret put APP_SECRET
$encKey | npx wrangler secret put ENC_KEY
npx wrangler d1 migrations apply DB --remote
npx wrangler deploy

$workerUrl = Read-Host "Paste the HTTPS workers.dev address displayed above"
$workerUrl = $workerUrl.TrimEnd('/')
$repoUrl = "https://billyke2000s.github.io/moviehub/"
@"
MOVIE HUB IS READY

Kodi source URL:     $repoUrl
Private server URL:  $workerUrl
Secret code:         $appSecret

Keep this file private. Kodi asks for the private server URL and secret code once.
"@ | Set-Content -Encoding UTF8 deployment-details.txt

Write-Host "Complete. Open deployment-details.txt for the three values you need." -ForegroundColor Green
Read-Host "Press Enter to close"
