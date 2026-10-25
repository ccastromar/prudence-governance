#!/usr/bin/env bash
# Copies this contract into the apps that implement it, next to this repository:
#
#   AOS     aos-agentic-orchestration-system/internal/governance/contract/
#   Ernest  ai-blockchain-provenance/backend/src/governance/contract/
#
# Each copy gets a SOURCE file (this repository's commit and a SHA-256 per file). The apps' tests
# check those sums, so a copy edited by hand fails CI: change the contract here and sync again.
#
#   contract/governance/sync.sh            # copies into every sibling checkout found
#   CHESTERTOWN_ROOT=/path contract/governance/sync.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ROOT="${CHESTERTOWN_ROOT:-$(dirname "$REPO")}"
FILES=(README.md default-model.yaml governance-model.schema.json vectors/resolution.json vectors/validation.json)
TARGETS=(
  "aos-agentic-orchestration-system/internal/governance/contract"
  "ai-blockchain-provenance/backend/src/governance/contract"
)

if [ -n "$(git -C "$REPO" status --porcelain -- "$HERE")" ]; then
  echo "contract/governance has uncommitted changes: commit them first, so SOURCE names a real commit" >&2
  exit 1
fi
commit="$(git -C "$REPO" rev-parse --short=12 HEAD)"

for target in "${TARGETS[@]}"; do
  app="${target%%/*}"
  if [ ! -d "$ROOT/$app" ]; then
    printf '  - %-34s skipped (not checked out)\n' "$app"
    continue
  fi
  dest="$ROOT/$target"
  rm -rf "$dest"
  mkdir -p "$dest/vectors"
  for f in "${FILES[@]}"; do cp "$HERE/$f" "$dest/$f"; done
  {
    echo "# Copied from prudence-governance contract/governance at $commit by sync.sh. Do not edit:"
    echo "# change the contract there and run sync.sh again. The app's tests check these sums."
    for f in "${FILES[@]}"; do
      printf '%s  %s\n' "$(shasum -a 256 "$dest/$f" | cut -d' ' -f1)" "$f"
    done
  } >"$dest/SOURCE"
  printf '  → %-34s %s\n' "$app" "${target#*/}"
done
