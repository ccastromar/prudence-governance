#!/usr/bin/env bash
# Create dedicated test users in the MVP Keycloak realm. Existing users are never changed.
set -euo pipefail

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
infra=${CHESTERTOWN_INFRA_DIR:-"$repo/../chestertown-infra"}
cd "$infra"
[ -f .env ] || { echo 'Falta el archivo .env de chestertown-infra.' >&2; exit 1; }
set -a; . ./.env; set +a
: "${KEYCLOAK_ADMIN_PASSWORD:?Falta KEYCLOAK_ADMIN_PASSWORD}"
command -v python3 >/dev/null || { echo 'Se necesita python3.' >&2; exit 1; }

# Validate before making any Keycloak changes.
: "${PRUDENCE_TEST_USERS_PASSWORD:?Falta PRUDENCE_TEST_USERS_PASSWORD}"

compose=(docker compose -f compose-server.yaml --env-file .env)
kc() { "${compose[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master \
  --user "${KEYCLOAK_ADMIN_USERNAME:-admin}" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

for role in read-only read-write policy-admin policy-reviewer; do
  if ! kc get "roles/$role" -r prudence >/dev/null 2>&1; then
    kc create roles -r prudence -s "name=$role" >/dev/null
  fi
done

create_user() {
  local username=$1 roles=$2 id role
  id=$(kc get users -r prudence -q "username=$username" | python3 -c \
    'import json,sys; users=[u for u in json.load(sys.stdin) if u.get("username")==sys.argv[1]]; print(users[0]["id"] if users else "")' "$username")
  if [ -n "$id" ]; then
    echo "$username ya existe: no se cambian sus roles ni su contraseña; revísalos manualmente."
    return
  fi
  kc create users -r prudence -s "username=$username" -s enabled=true >/dev/null
  kc set-password -r prudence --username "$username" --new-password "$PRUDENCE_TEST_USERS_PASSWORD" --temporary >/dev/null
  for role in $roles; do
    kc add-roles -r prudence --uusername "$username" --rolename "$role" >/dev/null
  done
  echo "$username creado."
}

create_user lector 'read-only'
create_user auditor 'read-only'
create_user operador 'read-write'
create_user autor-politicas 'read-write policy-admin'
create_user revisor-1 'read-write policy-reviewer'
create_user revisor-2 'read-write policy-reviewer'
create_user auto-revision 'policy-admin policy-reviewer'
create_user sin-rol ''

echo 'Keycloak pedirá cambiar la contraseña en el primer acceso.'
echo 'Las cuentas existentes no se han modificado; comprueba sus roles antes de usarlas.'
