#!/usr/bin/env python3
"""Register a runtime (an orchestrator, an agent framework, an MCP gateway) with Prudence.

A runtime governed by Prudence needs two things, and getting either wrong means every
decision is DENY:

1. A machine identity in Keycloak: a confidential client with a service account, the
   `pdp.decide` and `execution.report` scopes, a `preferred_username` mapper and an audience
   mapper. Without the username mapper the token carries only an opaque `sub`, Prudence uses
   it as the subject, and OPA, which knows subjects by name, denies everything. Without the
   audience (`prudence-api` unless Prudence is configured otherwise) a secured Prudence
   rejects the token outright with 401.
2. A grant in OPA's data (`policy/opa/data.json`): the service account's username gets a
   role, and the role may `run` the runtime's pipelines.

This script does both. It is idempotent: run it again with more pipelines to widen the
grant. Standard library only.

    KEYCLOAK_ADMIN_PASSWORD=… scripts/provision-runtime.py \\
        --name langgraph-claims --pipeline claims_triage --pipeline claims_letter

    # see what it would do, touching nothing
    scripts/provision-runtime.py --name langgraph-claims --pipeline claims_triage --dry-run

The client secret is written to a 0600 file (default `./<client-id>.secret`), never printed.
Commit `policy/opa/data.json` afterwards: OPA reloads it where it watches the policy folder.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SCOPES = ("pdp.decide", "execution.report")
REPO = Path(__file__).resolve().parent.parent


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


class Keycloak:
    def __init__(self, url: str, realm: str, user: str, password: str):
        self.url, self.realm = url.rstrip("/"), realm
        self.token = self._admin_token(user, password)

    def _admin_token(self, user: str, password: str) -> str:
        body = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                       "username": user, "password": password}).encode()
        req = urllib.request.Request(f"{self.url}/realms/master/protocol/openid-connect/token", data=body)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return json.load(r)["access_token"]
        except urllib.error.HTTPError as e:
            die(f"Keycloak admin login failed ({e.code}); check KEYCLOAK_ADMIN_USER/PASSWORD")
        except urllib.error.URLError as e:
            die(f"cannot reach Keycloak at {self.url}: {e.reason}")
        return ""

    def call(self, method: str, path: str, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{self.url}/admin/realms/{self.realm}{path}", data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            die(f"Keycloak {method} {path} failed ({e.code}): {e.read()[:200].decode(errors='replace')}")

    def find(self, path: str, field: str, value: str):
        # Filter here: some list endpoints ignore query parameters and return every item.
        return next((x for x in self.call("GET", path) or [] if x.get(field) == value), None)


def provision_keycloak(kc: Keycloak, client_id: str, display: str, audience: str) -> tuple[str, str]:
    """Create or tighten the client; return (service-account username, secret)."""
    scope_ids = {}
    for name in SCOPES:
        scope = kc.find("/client-scopes", "name", name)
        if scope is None:
            kc.call("POST", "/client-scopes", {
                "name": name, "protocol": "openid-connect",
                "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"}})
            scope = kc.find("/client-scopes", "name", name)
        scope_ids[name] = scope["id"]

    client = kc.find("/clients", "clientId", client_id)
    settings = {
        "clientId": client_id, "name": display, "enabled": True, "publicClient": False,
        "serviceAccountsEnabled": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
        "implicitFlowEnabled": False, "fullScopeAllowed": False,
    }
    if client is None:
        kc.call("POST", "/clients", settings)
        client = kc.find("/clients", "clientId", client_id)
        print(f"  created Keycloak client {client_id}")
    else:
        # Keep an existing client deliberately narrow, even if someone widened it by hand.
        kc.call("PUT", f"/clients/{client['id']}", {**client, **settings})
        print(f"  Keycloak client {client_id} exists; settings tightened")
    cid = client["id"]

    for name, sid in scope_ids.items():
        kc.call("PUT", f"/clients/{cid}/default-client-scopes/{sid}")
    attached = {s["name"] for s in kc.call("GET", f"/clients/{cid}/default-client-scopes")}
    missing = set(SCOPES) - attached
    if missing:
        die(f"Keycloak did not attach {', '.join(sorted(missing))} to {client_id}")

    mappers = kc.call("GET", f"/clients/{cid}/protocol-mappers/models") or []
    if not any(m.get("name") == "preferred-username" for m in mappers):
        kc.call("POST", f"/clients/{cid}/protocol-mappers/models", {
            "name": "preferred-username", "protocol": "openid-connect",
            "protocolMapper": "oidc-usermodel-property-mapper",
            "config": {"user.attribute": "username", "claim.name": "preferred_username", "jsonType.label": "String",
                       "access.token.claim": "true", "id.token.claim": "false", "userinfo.token.claim": "false"}})
    if not any(m.get("name") == "prudence-audience" for m in mappers):
        kc.call("POST", f"/clients/{cid}/protocol-mappers/models", {
            "name": "prudence-audience", "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "config": {"included.custom.audience": audience, "access.token.claim": "true", "id.token.claim": "false"}})

    user = kc.call("GET", f"/clients/{cid}/service-account-user")
    secret = kc.call("GET", f"/clients/{cid}/client-secret")["value"]
    return user["username"], secret


def grant(data: dict, subject: str, role: str, pipelines: list[str]) -> list[str]:
    """Bind subject → role and let the role run the pipelines. Returns the pipelines added."""
    prudence = data.setdefault("prudence", {})
    roles = prudence.setdefault("roles_of", {}).setdefault(subject, [])
    if role not in roles:
        roles.append(role)
    perms = prudence.setdefault("role_permissions", {}).setdefault(role, [])
    added = []
    for p in pipelines:
        perm = next((x for x in perms if x.get("pipeline") == p), None)
        if perm is None:
            perms.append({"pipeline": p, "actions": ["run"]})
            added.append(p)
        elif "run" not in perm.setdefault("actions", []):
            perm["actions"].append("run")
            added.append(p)
    return added


def main() -> None:
    ap = argparse.ArgumentParser(description="Register a runtime with Prudence (Keycloak identity + OPA grant).")
    ap.add_argument("--name", required=True, help="runtime name, e.g. langgraph-claims (lowercase, digits, dashes)")
    ap.add_argument("--pipeline", action="append", default=[], help="pipeline it may run (repeatable)")
    ap.add_argument("--role", help="OPA role to grant (default: runtime-<name>)")
    ap.add_argument("--client-id", help="Keycloak client id (default: <name>-pdp)")
    ap.add_argument("--keycloak-url", default=os.getenv("KEYCLOAK_URL", "http://localhost:8081"))
    ap.add_argument("--realm", default=os.getenv("KEYCLOAK_REALM", "prudence"))
    ap.add_argument("--audience", default=os.getenv("PRUDENCE_OIDC_AUDIENCE", "prudence-api"),
                    help="audience Prudence requires in tokens (default: prudence-api)")
    ap.add_argument("--opa-data", default=str(REPO / "policy/opa/data.json"))
    ap.add_argument("--secret-file", help="where to write the client secret (default: ./<client-id>.secret)")
    ap.add_argument("--dry-run", action="store_true", help="show the plan; change nothing")
    args = ap.parse_args()

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,40}", args.name):
        die("--name must be 2–41 characters: lowercase letters, digits and dashes")
    if not args.pipeline:
        die("give at least one --pipeline: a runtime with no grant is denied everything")
    client_id = args.client_id or f"{args.name}-pdp"
    role = args.role or f"runtime-{args.name}"
    subject = f"service-account-{client_id}"  # Keycloak's username for the client's service account
    secret_file = Path(args.secret_file or f"{client_id}.secret")
    opa_path = Path(args.opa_data)

    print(f"Runtime {args.name}")
    print(f"  Keycloak  {args.keycloak_url} realm {args.realm}: client {client_id}, scopes {', '.join(SCOPES)}, audience {args.audience}")
    print(f"  Subject   {subject} (the PDP takes it from the token, never from the request)")
    print(f"  OPA       role {role} may run: {', '.join(args.pipeline)}  ({opa_path})")
    if args.dry_run:
        print("Dry run: nothing changed.")
        return

    password = os.getenv("KEYCLOAK_ADMIN_PASSWORD") or die("set KEYCLOAK_ADMIN_PASSWORD")
    kc = Keycloak(args.keycloak_url, args.realm, os.getenv("KEYCLOAK_ADMIN_USER", "admin"), password)
    username, secret = provision_keycloak(kc, client_id, f"{args.name} → Prudence PDP", args.audience)
    if username != subject:
        die(f"service account is {username}, expected {subject}; grant it by hand or pass --client-id")

    data = json.loads(opa_path.read_text())
    added = grant(data, subject, role, args.pipeline)
    opa_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    print(f"  OPA grant updated ({len(added)} new pipeline grant(s))")

    fd = os.open(secret_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(secret + "\n")
    print(f"  client secret written to {secret_file} (mode 0600)")

    token_url = f"{args.keycloak_url.rstrip('/')}/realms/{args.realm}/protocol/openid-connect/token"
    print("\nNext:")
    print(f"  1. Commit {opa_path.name} so OPA picks up the grant.")
    print("  2. Register and approve the AI system in Prudence; the runtime sends its id as aiSystemId.")
    print("  3. Give the runtime:")
    print(f"       PRUDENCE_PDP_TOKEN_URL={token_url}")
    print(f"       PRUDENCE_PDP_CLIENT_ID={client_id}")
    print(f"       PRUDENCE_PDP_CLIENT_SECRET=<contents of {secret_file}>")
    print("  Guide: docs/integration/README.md")


if __name__ == "__main__":
    main()
