#!/usr/bin/env bash
# Copies this contract into Ernest, next to this repository:
#
#   ai-blockchain-provenance/backend/test/contracts/prudence-evidence/
#
# The copy gets a SOURCE file (this repository's commit and a SHA-256 per file). Ernest's
# verification checks those sums, so a copy edited by hand fails its CI: change the contract here
# and sync again.
#
#   contract/ernest/sync.sh            # copies into the sibling checkout if found
#   CHESTERTOWN_ROOT=/path contract/ernest/sync.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ROOT="${CHESTERTOWN_ROOT:-$(dirname "$REPO")}"
FILES=(README.md interactions.json interactions-unbatched.json)
TARGET="ai-blockchain-provenance/backend/test/contracts/prudence-evidence"

if [ -n "$(git -C "$REPO" status --porcelain -- "$HERE")" ]; then
  echo "contract/ernest has uncommitted changes: commit them first, so SOURCE names a real commit" >&2
  exit 1
fi
commit="$(git -C "$REPO" rev-parse --short=12 HEAD)"

app="${TARGET%%/*}"
if [ ! -d "$ROOT/$app" ]; then
  printf '  - %-34s skipped (not checked out)\n' "$app"
  exit 0
fi
dest="$ROOT/$TARGET"
rm -rf "$dest"
mkdir -p "$dest"
for f in "${FILES[@]}"; do cp "$HERE/$f" "$dest/$f"; done
{
  echo "# Copied from prudence-governance contract/ernest at $commit by sync.sh. Do not edit:"
  echo "# change the contract there and run sync.sh again. Ernest's verification checks these sums."
  for f in "${FILES[@]}"; do
    printf '%s  %s\n' "$(shasum -a 256 "$dest/$f" | cut -d' ' -f1)" "$f"
  done
} >"$dest/SOURCE"
printf '  → %-34s %s\n' "$app" "${TARGET#*/}"
