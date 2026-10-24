#!/usr/bin/env bash
# Prudence backend with real login, on one machine, no Docker. Runs the SECURED (non-dev)
# configuration — JWT from the local Keycloak, PostgreSQL + Flyway, the same fail-closed start
# checks as production — with local values for everything those checks demand.
#
#   ./scripts/keycloak.sh        # first, in another terminal
#   ./scripts/backend-sso.sh
#
# Needs a local PostgreSQL on 127.0.0.1:5432 (brew services start postgresql@16) where the
# current OS user is a superuser (Homebrew's default). Creates role/database `prudence_sso`
# on first run. Not a production setup: test credentials, a dummy SMTP host, open Ernest.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

PG_HOST="${PG_HOST:-127.0.0.1}"; PG_PORT="${PG_PORT:-5432}"
DB="${PRUDENCE_SSO_DB:-prudence_sso}"; DB_PASSWORD="${PRUDENCE_SSO_DB_PASSWORD:-prudence-sso-dev}"
ISSUER="${OIDC_ISSUER:-http://localhost:8081/realms/chestertown}"
PSQL="$(command -v psql || echo "$(pg_config --bindir 2>/dev/null)/psql")"

command -v mvn >/dev/null || { echo "mvn not found" >&2; exit 1; }
"$PSQL" -h "$PG_HOST" -p "$PG_PORT" -d postgres -Atc 'select 1' >/dev/null 2>&1 || {
  echo "PostgreSQL not reachable on $PG_HOST:$PG_PORT (brew services start postgresql@16)" >&2; exit 1; }
curl -sf -o /dev/null "$ISSUER/.well-known/openid-configuration" || {
  echo "Keycloak issuer not reachable: $ISSUER — start ./scripts/keycloak.sh first" >&2; exit 1; }

# Idempotent: role + database for this mode only (separate from any other local database).
if [ "$("$PSQL" -h "$PG_HOST" -p "$PG_PORT" -d postgres -Atc "select 1 from pg_roles where rolname='$DB'")" != 1 ]; then
  "$PSQL" -h "$PG_HOST" -p "$PG_PORT" -d postgres -qc "create role $DB login password '$DB_PASSWORD'"
  "$PSQL" -h "$PG_HOST" -p "$PG_PORT" -d postgres -qc "create database $DB owner $DB"
  echo "created PostgreSQL role/database $DB"
fi

# Java 25+ (pom.xml); prefer the newest installed JDK if the shell pins an older one.
if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null || echo "${JAVA_HOME:-}")"; export JAVA_HOME
fi

export SPRING_PROFILES_ACTIVE=""
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$ISSUER"
# Access tokens of the test realm carry this audience (prudence-spa and pdp-client mappers).
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES="${PRUDENCE_OIDC_AUDIENCE:-prudence-api}"
# The realm's suite-wide role names (prudence-author, …) mapped to Prudence's own.
export PRUDENCE_IDENTITY_ROLE_MAPPING="${PRUDENCE_IDENTITY_ROLE_MAPPING:-prudence-reader=reader,prudence-author=author,prudence-system-approver=system-approver,prudence-policy-reviewer=policy-reviewer,prudence-admin=admin}"
export PRUDENCE_DB_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$DB"
export PRUDENCE_DB_USER="$DB" PRUDENCE_DB_PASSWORD="$DB_PASSWORD"
# Ernest's local dev run has open access; the start check only needs a non-blank key.
export PRUDENCE_ERNEST_API_KEY="${PRUDENCE_ERNEST_API_KEY:-local-dev-open}"
# No local SMTP: alerts fail and show up as undelivered records in Posture (retryable).
export SPRING_MAIL_HOST="${SPRING_MAIL_HOST:-127.0.0.1}" SPRING_MAIL_PORT="${SPRING_MAIL_PORT:-1025}"
# ...and keep that dummy SMTP out of /actuator/health, or the instance reports DOWN.
export MANAGEMENT_HEALTH_MAIL_ENABLED=false
export PRUDENCE_ALERTS_GOVERNANCE_TO="${PRUDENCE_ALERTS_GOVERNANCE_TO:-ai-governance@example.eu}"
export PRUDENCE_ALERTS_OPERATIONS_TO="${PRUDENCE_ALERTS_OPERATIONS_TO:-platform-oncall@example.eu}"
export PRUDENCE_CORS_ORIGINS="${PRUDENCE_CORS_ORIGINS:-http://localhost:5200,http://127.0.0.1:5200}"
export PRUDENCE_CONSOLE_URL="${PRUDENCE_CONSOLE_URL:-http://localhost:5200}"

echo "Prudence (secured) on http://localhost:8099 · issuer $ISSUER · db $DB · java $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
cd "$REPO"
exec mvn -q spring-boot:run
