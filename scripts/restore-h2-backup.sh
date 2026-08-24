#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then echo "usage: $0 data/backups/arbitrage-YYYYMMDD-HHMMSS.zip"; exit 2; fi
backup="$1"
test -f "$backup"
cd "$(dirname "$0")/.."
docker compose stop app
stamp="$(date +%Y%m%d-%H%M%S)"
cp data/arbitrage.mv.db "data/backups/before-restore-$stamp.mv.db"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q "$backup" -d "$tmp"
restored="$(find "$tmp" -name '*.mv.db' -type f | head -n 1)"
test -n "$restored"
cp "$restored" data/arbitrage.mv.db
chown 10001:10001 data/arbitrage.mv.db
docker compose up -d app caddy
docker compose ps
