#!/bin/bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
install -d -m 0755 /usr/local/libexec
install -m 0755 -o root -g root "$repo_root/server/tools/publish-main.sh" /usr/local/libexec/moviehub-publish
cat > /etc/sudoers.d/moviehub-publish <<'EOF'
moviehub ALL=(moviehub-release) NOPASSWD:NOSETENV: /usr/local/libexec/moviehub-publish providers, /usr/local/libexec/moviehub-publish scrapers, /usr/local/libexec/moviehub-publish scrapers-experimental
EOF
chmod 0440 /etc/sudoers.d/moviehub-publish
visudo -cf /etc/sudoers.d/moviehub-publish
