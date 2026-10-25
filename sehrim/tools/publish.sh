#!/usr/bin/env bash
# data/ klasörünü tek commit'lik sehrim-data dalı olarak yayımlar.
set -e
cd data
[ -n "$(ls -A)" ] || { echo "veri yok"; exit 0; }
rm -rf .git
git init -q -b sehrim-data
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
git add -A
git commit -qm "Şehrim verisi $(date -u +%Y-%m-%dT%H:%MZ)"
git push -qf "https://x-access-token:$1@github.com/$2.git" sehrim-data
rm -rf .git
ls -la
