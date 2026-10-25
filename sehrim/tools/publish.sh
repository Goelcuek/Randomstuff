#!/usr/bin/env bash
# data/ içindeki dosyaları sehrim-data dalına tek commit olarak yayımlar.
# Daldaki güncel içerik önce alınır, sonra yalnızca bu çalışmanın ürettiği dosyalar üzerine yazılır
# (eşzamanlı çalışmalar birbirinin verisini eskisiyle ezmesin).
set -e
TOKEN="$1"; REPO="$2"; shift 2
PATTERNS=("$@"); [ ${#PATTERNS[@]} -gt 0 ] || PATTERNS=("*.json")
URL="https://x-access-token:$TOKEN@github.com/$REPO.git"
W=$(mktemp -d)
git init -q -b sehrim-data "$W"
cd "$W"
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
if git fetch -q --depth 1 "$URL" sehrim-data; then git archive FETCH_HEAD | tar -x; fi
for p in "${PATTERNS[@]}"; do cp -f "$GITHUB_WORKSPACE"/data/$p . 2>/dev/null || true; done
git add -A
git commit -qm "Şehrim verisi $(date -u +%Y-%m-%dT%H:%MZ)" || { echo "değişiklik yok"; exit 0; }
git push -qf "$URL" HEAD:sehrim-data
ls -la
