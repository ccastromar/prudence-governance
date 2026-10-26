#!/usr/bin/env bash
# Copies this contract (and the OpenAPI file it refines) into the gates that implement it, next to
# this repository:
#
#   AOS     aos-agentic-orchestration-system/internal/governance/decision-contract/
#
# The copy gets a SOURCE file (this repository's commit and a SHA-256 per file). AOS's tests
# check those sums, so a copy edited by hand fails CI: change the contract here and sync again.
# prudence-core (integrations/) reads the vectors from this repository and needs no copy.
#
#   contract/decision/sync.sh            # copies into every sibling checkout found
#   CHESTERTOWN_ROOT=/path contract/decision/sync.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ROOT="${CHESTERTOWN_ROOT:-$(dirname "$REPO")}"
# source (relative to the repository) -> name in the copy
FILES=(
  "contract/decision/README.md:README.md"
  "docs/api/decision-api-v1.yaml:decision-api-v1.yaml"
  "contract/decision/vectors/gate.json:vectors/gate.json"
  "contract/decision/vectors/report.json:vectors/report.json"
  "contract/decision/vectors/provider.json:vectors/provider.json"
)
TARGETS=(
  "aos-agentic-orchestration-system/internal/governance/decision-contract"
)

if [ -n "$(git -C "$REPO" status --porcelain -- "$HERE" "$REPO/docs/api/decision-api-v1.yaml")" ]; then
  echo "contract/decision or its OpenAPI file has uncommitted changes: commit them first, so SOURCE names a real commit" >&2
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
  for f in "${FILES[@]}"; do cp "$REPO/${f%%:*}" "$dest/${f#*:}"; done
  {
    echo "# Copied from prudence-governance (contract/decision, docs/api/decision-api-v1.yaml) at $commit"
    echo "# by contract/decision/sync.sh. Do not edit: change the contract there and sync again."
    echo "# The app's tests check these sums."
    for f in "${FILES[@]}"; do
      printf '%s  %s\n' "$(shasum -a 256 "$dest/${f#*:}" | cut -d' ' -f1)" "${f#*:}"
    done
  } >"$dest/SOURCE"
  printf '  → %-34s %s\n' "$app" "${target#*/}"
done
