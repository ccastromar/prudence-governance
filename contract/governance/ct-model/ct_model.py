#!/usr/bin/env python3
"""ct-model: checks a governance model and generates what an organization needs to run it.

    python -m pip install pyyaml
    ct_model.py check MODEL [--strict]
    ct_model.py keycloak MODEL [--realm NAME] [--base REALM.json] [-o OUT.json]
    ct_model.py entra MODEL [-o OUT.json]
    ct_model.py env MODEL [--path /etc/chestertown/governance-model.yaml]
    ct_model.py explain MODEL TOKEN|-

check     validates the model (contract README, section 4) and warns about what it leaves
          uncovered: a function nobody can exercise in an app, a role that holds both sides of an
          incompatible pair, a pair no app enforces. Notes (not warnings) flag machine roles a
          person could be given.
keycloak  a development realm: one realm role per map key, one persona per person role (password =
          username, for local testing only) and one confidential client per machine role. The
          console clients come from --base (scripts/chestertown-realm.json by default).
entra     the appRoles of an Entra ID app registration: person roles for users and groups,
          machine roles for applications.
env       the setting each app reads its governance model from.
explain   the functions each app grants to a token (a JWT, or its claims as JSON; `-` reads stdin).
          It decodes without checking the signature: use it to see what an identity provider
          issues, never to authorize.

Exit codes: 0 valid, 1 invalid model (or warnings with --strict), 2 usage.
"""
from __future__ import annotations

import argparse
import base64
import copy
import json
import sys
import uuid
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "reference"))
from governance_model import APPS, FUNCTIONS, ModelError, parse_grant, resolve, validate  # noqa: E402

DEFAULT_BASE = HERE.parent.parent.parent / "scripts" / "chestertown-realm.json"

# Pairs an app enforces on one object (contract README, "Where each rule applies").
ENFORCED_PAIRS = {
    frozenset(("system.declare", "system.approve")): "Prudence",
    frozenset(("policy.author", "policy.approve")): "Prudence",
    frozenset(("ai.use", "oversight")): "AOS",
}

# A stable namespace, so regenerating the Entra roles keeps their ids.
ROLE_NAMESPACE = uuid.UUID("6f1c3c2e-6a43-4a43-9a6c-0c4a7f1f9e11")


def load(path: str) -> dict:
    model = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    validate(model)
    return model


def kind_of(grants: list) -> str:
    return FUNCTIONS[parse_grant(grants[0])[0]][0]


def roles(model: dict, kind: str) -> list[str]:
    return [key for key, grants in model["map"].items() if kind_of(grants) == kind]


def granted_in(grants: list, app: str) -> set[str]:
    """The bare functions these grants give in `app` (domains dropped)."""
    out = set()
    for g in grants:
        fn, _, only = parse_grant(g)
        if app in FUNCTIONS[fn][1] and only in (None, app):
            out.add(fn)
    return out


# ── check ───────────────────────────────────────────────────────────────────────────────────


def findings(model: dict) -> tuple[list[str], list[str]]:
    """Warnings (gaps and conflicts) and notes (setups that are fine if done with care)."""
    warnings, notes = [], []
    for app in APPS:
        for fn, (_, apps) in FUNCTIONS.items():
            if app not in apps:
                continue
            if not any(fn in granted_in(grants, app) for grants in model["map"].values()):
                warnings.append(f"nobody can exercise {fn} in {app}")
    pairs = (model.get("rules") or {}).get("incompatible") or []
    for a, b in pairs:
        if frozenset((a, b)) not in ENFORCED_PAIRS:
            warnings.append(f"no app enforces the pair [{a}, {b}] yet: it documents intent only")
        for key, grants in model["map"].items():
            fns = {parse_grant(g)[0] for g in grants}
            if a in fns and b in fns:
                warnings.append(
                    f"role {key} grants both {a} and {b}: its holders are refused one of them on "
                    "every object where they used the other"
                )
    identity = model["identity"]
    if identity["people"]["claim"] == identity["machines"]["claim"] and roles(model, "machine"):
        notes.append(
            f"people and workloads share the claim {identity['people']['claim']!r}: assign the machine "
            f"roles ({', '.join(roles(model, 'machine'))}) to workloads only (in Entra, "
            "allowedMemberTypes Application)"
        )
    return warnings, notes


def summary(model: dict) -> list[str]:
    rules = model.get("rules") or {}
    lines = ["approvals:"]
    for tier in ("HIGH", "LIMITED", "MINIMAL"):
        rule = (rules.get("approvals") or {}).get(tier) or {"count": 1}
        distinct = ", distinct" if rule.get("distinct") else ""
        lines.append(f"  {tier:8} {rule['count']}{distinct}")
    lines.append("accountable before review:")
    for tier, required in (rules.get("accountable") or {}).items():
        lines.append(f"  {tier:8} {', '.join(required)}")
    lines.append("incompatible:")
    for a, b in rules.get("incompatible") or []:
        where = ENFORCED_PAIRS.get(frozenset((a, b)), "not enforced")
        lines.append(f"  {a} / {b} ({where})")
    lines.append("roles:")
    for key, grants in model["map"].items():
        lines.append(f"  {key:28} {kind_of(grants):8} {', '.join(map(str, grants))}")
    return lines


def cmd_check(args) -> int:
    model = load(args.model)
    print("\n".join(summary(model)))
    warnings, notes = findings(model)
    for w in warnings:
        print(f"warning: {w}")
    for n in notes:
        print(f"note: {n}")
    print(f"{args.model}: valid, {len(warnings)} warning(s)")
    return 1 if warnings and args.strict else 0


# ── keycloak ────────────────────────────────────────────────────────────────────────────────


def slug(key: str) -> str:
    return "".join(c if c.isalnum() else "-" for c in key.lower()).strip("-")


def set_claim(mappers: list, claim: str) -> None:
    for m in mappers:
        if m.get("protocolMapper") == "oidc-usermodel-realm-role-mapper":
            m["config"]["claim.name"] = claim


def keycloak_realm(model: dict, name: str, base: dict) -> dict:
    people_claim = model["identity"]["people"]["claim"]
    machines_claim = model["identity"]["machines"]["claim"]
    people, machines = roles(model, "person"), roles(model, "machine")
    by_scope = machines_claim == "scope"

    realm = {"realm": name, "enabled": True}
    realm["roles"] = {
        "realm": [{"name": k} for k in people]
        + ([] if by_scope else [{"name": k} for k in machines])
    }

    # Keep the base realm's standard scopes; machine roles carried in `scope` become client scopes.
    scopes = [s for s in base.get("clientScopes", []) if s["name"] in ("basic", "profile", "email", "roles")]
    if by_scope:
        scopes += [
            {
                "name": k,
                "protocol": "openid-connect",
                "description": f"Workload role {k} (governance model).",
                "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"},
            }
            for k in machines
        ]
    realm["clientScopes"] = scopes

    consoles = [copy.deepcopy(c) for c in base.get("clients", []) if c.get("publicClient")]
    for c in consoles:
        set_claim(c.get("protocolMappers", []), people_claim)
    template = next((c for c in base.get("clients", []) if c.get("serviceAccountsEnabled")), None)
    workloads, service_accounts = [], []
    for k in machines:
        client = copy.deepcopy(template) if template else {"protocol": "openid-connect"}
        client.update(
            {
                "clientId": f"{slug(k)}-client",
                "enabled": True,
                "publicClient": False,
                "serviceAccountsEnabled": True,
                "standardFlowEnabled": False,
                "directAccessGrantsEnabled": False,
                "description": f"Workload holding {k} (client_credentials). Development secret.",
                "secret": f"{slug(k)}-dev-secret",
                "defaultClientScopes": ["basic", "roles"] + ([k] if by_scope else []),
                "optionalClientScopes": [],
            }
        )
        set_claim(client.get("protocolMappers", []), machines_claim if not by_scope else people_claim)
        workloads.append(client)
        service_accounts.append(
            {
                "username": f"service-account-{slug(k)}-client",
                "enabled": True,
                "serviceAccountClientId": f"{slug(k)}-client",
                "realmRoles": [] if by_scope else [k],
            }
        )
    realm["clients"] = workloads + consoles

    # One persona per person role, to try each role in the consoles. Development passwords only.
    realm["users"] = service_accounts + [
        {
            "username": slug(k),
            "enabled": True,
            "emailVerified": True,
            "email": f"{slug(k)}@example.test",
            "firstName": k,
            "lastName": "Persona",
            "credentials": [{"type": "password", "value": slug(k), "temporary": False}],
            "realmRoles": [k],
        }
        for k in people
    ]
    return realm


def write_json(data, out: str | None) -> None:
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    if out:
        Path(out).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)


def cmd_keycloak(args) -> int:
    model = load(args.model)
    base = json.loads(Path(args.base).read_text(encoding="utf-8"))
    write_json(keycloak_realm(model, args.realm, base), args.output)
    return 0


# ── entra ───────────────────────────────────────────────────────────────────────────────────


def entra_roles(model: dict) -> list[dict]:
    out = []
    for key, grants in model["map"].items():
        kind = kind_of(grants)
        out.append(
            {
                "allowedMemberTypes": ["Application"] if kind == "machine" else ["User"],
                "description": "Chestertown governance: " + ", ".join(map(str, grants)),
                "displayName": key,
                "id": str(uuid.uuid5(ROLE_NAMESPACE, key)),
                "isEnabled": True,
                "value": key,
            }
        )
    return out


def cmd_entra(args) -> int:
    model = load(args.model)
    identity = model["identity"]
    for side in ("people", "machines"):
        if identity[side]["claim"] != "roles":
            print(
                f"warning: Entra puts app roles in the `roles` claim; identity.{side}.claim is "
                f"{identity[side]['claim']!r}",
                file=sys.stderr,
            )
    for key in model["map"]:
        if len(key) > 120 or any(c.isspace() for c in key):
            print(f"error: {key!r} cannot be an Entra app role value", file=sys.stderr)
            return 1
    write_json({"appRoles": entra_roles(model)}, args.output)
    return 0


# ── env ─────────────────────────────────────────────────────────────────────────────────────


def cmd_env(args) -> int:
    load(args.model)
    print(
        f"""# Each app reads the same governance model; mount it at {args.path}.
# AOS
AOS_GOVERNANCE_MODEL={args.path}
# Prudence (property prudence.governance.model)
PRUDENCE_GOVERNANCE_MODEL={args.path}
# Ernest
ERNEST_GOVERNANCE_MODEL={args.path}"""
    )
    return 0


# ── explain ─────────────────────────────────────────────────────────────────────────────────


def claims_of(token: str) -> dict:
    token = token.strip()
    if token.startswith("{"):
        return json.loads(token)
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def cmd_explain(args) -> int:
    model = load(args.model)
    try:
        claims = claims_of(sys.stdin.read() if args.token == "-" else args.token)
    except (ValueError, IndexError):
        print("error: not a JWT or a JSON object of claims", file=sys.stderr)
        return 2
    identity = model["identity"]
    for side in ("people", "machines"):
        name = identity[side]["claim"]
        print(f"{side} claim {name}: {claims.get(name, '(absent)')}")
    for key in ("iss", "aud", "sub", "azp"):
        if key in claims:
            print(f"{key}: {claims[key]}")
    for app in APPS:
        print(f"{app}: {', '.join(resolve(model, claims, app)) or '(no functions)'}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="ct-model", description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("check", help="validate the model and report what it leaves uncovered")
    p.add_argument("model")
    p.add_argument("--strict", action="store_true", help="exit 1 on warnings too")
    p.set_defaults(run=cmd_check)

    p = sub.add_parser("keycloak", help="a development realm with roles, personas and workloads")
    p.add_argument("model")
    p.add_argument("--realm", default="chestertown")
    p.add_argument("--base", default=str(DEFAULT_BASE), help="realm whose console clients to reuse")
    p.add_argument("-o", "--output")
    p.set_defaults(run=cmd_keycloak)

    p = sub.add_parser("entra", help="the appRoles of an Entra ID app registration")
    p.add_argument("model")
    p.add_argument("-o", "--output")
    p.set_defaults(run=cmd_entra)

    p = sub.add_parser("env", help="the setting each app reads the model from")
    p.add_argument("model")
    p.add_argument("--path", default="/etc/chestertown/governance-model.yaml")
    p.set_defaults(run=cmd_env)

    p = sub.add_parser("explain", help="the functions each app grants to a token")
    p.add_argument("model")
    p.add_argument("token", help="a JWT or its claims as JSON; - reads stdin")
    p.set_defaults(run=cmd_explain)

    args = parser.parse_args(argv)
    try:
        return args.run(args)
    except ModelError as e:
        print(f"{args.model}: invalid: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
