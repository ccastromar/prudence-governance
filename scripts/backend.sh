#!/usr/bin/env bash
# Prudence backend WITHOUT login (dev profile) on http://localhost:8099: H2 file in ./data,
# every action signed as `dev-reviewer`. For login, use ./scripts/backend-sso.sh instead.
#
#   ./scripts/backend.sh
#   rm -rf data        # (with it stopped) to start from an empty database
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/.."   # the H2 path (./data) is relative to the working directory

# Java 25+ (pom.xml); prefer the newest installed JDK if the shell pins an older one.
if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null || echo "${JAVA_HOME:-}")"; export JAVA_HOME
fi
if lsof -nP -iTCP:8099 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port 8099 is already in use (another Prudence?):" >&2; lsof -nP -iTCP:8099 -sTCP:LISTEN >&2; exit 1
fi
echo "Prudence (dev, no login, H2 in ./data) on http://localhost:8099"
SPRING_PROFILES_ACTIVE=dev exec mvn -q spring-boot:run
