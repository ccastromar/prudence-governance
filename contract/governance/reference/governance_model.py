"""Reference implementation of the Chestertown governance contract v1 (README.md).

It exists to check the test vectors, not to run in production: each app implements the same
rules in its own language and must agree with these vectors. Standard library only, except for
reading YAML models (PyYAML).
"""
from __future__ import annotations

import re

VERSION = "chestertown.governance/v1"
APPS = ("aos", "prudence", "ernest")

# Function → (kind, apps where it exists). README.md, section 2.
FUNCTIONS = {
    "system.declare": ("person", {"prudence"}),
    "system.approve": ("person", {"prudence"}),
    "policy.author": ("person", {"prudence"}),
    "policy.approve": ("person", {"prudence"}),
    "oversight": ("person", {"aos", "prudence"}),
    "incident.handle": ("person", {"prudence"}),
    "ai.use": ("person", {"aos"}),
    "evidence.write": ("person", {"ernest"}),
    "audit": ("person", {"aos", "prudence", "ernest"}),
    "platform.admin": ("person", {"aos", "prudence", "ernest"}),
    "runtime.decide": ("machine", {"prudence"}),
    "runtime.report": ("machine", {"prudence"}),
}
TIERS = ("HIGH", "LIMITED", "MINIMAL")
ACCOUNTABILITIES = ("OWNER", "SUPERVISOR", "COMPLIANCE", "DPO", "SECURITY")

_GRANT = re.compile(r"^([a-z]+(?:\.[a-z]+)?)(?::([a-z0-9][a-z0-9_-]*|\*))?(?:@([a-z]+))?$")


class ModelError(Exception):
    """An invalid model. `code` is the README's error code."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code


def parse_grant(grant) -> tuple[str, str | None, str | None]:
    """Return (function, domain, app) or raise ModelError."""
    if not isinstance(grant, str):
        raise ModelError("grant.syntax", repr(grant))
    m = _GRANT.match(grant)
    if not m:
        raise ModelError("grant.syntax", grant)
    function, domain, app = m.groups()
    if function not in FUNCTIONS:
        raise ModelError("grant.unknown_function", grant)
    if domain is not None and function != "ai.use":
        raise ModelError("grant.domain_not_allowed", grant)
    if domain is None and function == "ai.use":
        raise ModelError("grant.domain_required", grant)
    if app is not None:
        if app not in APPS:
            raise ModelError("grant.unknown_app", grant)
        if app not in FUNCTIONS[function][1]:
            raise ModelError("grant.app_not_applicable", grant)
    return function, domain, app


def validate(model) -> None:
    """Raise ModelError with the first rule the model breaks (README.md, section 4)."""
    if not isinstance(model, dict) or model.get("version") != VERSION:
        raise ModelError("model.version", "expected " + VERSION)

    identity = model.get("identity")
    for kind in ("people", "machines"):
        section = identity.get(kind) if isinstance(identity, dict) else None
        claim = section.get("claim") if isinstance(section, dict) else None
        if not isinstance(claim, str) or not claim:
            raise ModelError("model.identity", f"identity.{kind}.claim")

    mapping = model.get("map")
    if not isinstance(mapping, dict):
        raise ModelError("model.map", "map must be an object")
    for key, grants in mapping.items():
        if not isinstance(key, str) or not key or not isinstance(grants, list) or not grants:
            raise ModelError("model.map", f"entry {key!r}")
        kinds = {FUNCTIONS[parse_grant(g)[0]][0] for g in grants}
        if len(kinds) > 1:
            raise ModelError("grant.mixed_kinds", key)

    rules = model.get("rules")
    if not isinstance(rules, dict):
        rules = {}
    approvals = rules.get("approvals") or {}
    if not isinstance(approvals, dict):
        raise ModelError("rules.approvals", "approvals must be an object")
    for tier, rule in approvals.items():
        count = rule.get("count") if isinstance(rule, dict) else None
        if tier not in TIERS or not isinstance(count, int) or isinstance(count, bool) or count < 1:
            raise ModelError("rules.approvals", tier)
    high = approvals.get("HIGH")
    if not high or high.get("count", 0) < 2 or high.get("distinct") is not True:
        raise ModelError("floor.approvals.HIGH", "HIGH needs count >= 2 and distinct: true")

    for pair in rules.get("incompatible") or []:
        if (not isinstance(pair, list) or len(pair) != 2 or pair[0] == pair[1]
                or any(f not in FUNCTIONS or FUNCTIONS[f][0] != "person" for f in pair)):
            raise ModelError("rules.incompatible", repr(pair))

    accountable = rules.get("accountable") or {}
    if not isinstance(accountable, dict):
        raise ModelError("rules.accountable", "accountable must be an object")
    for tier, roles in accountable.items():
        if tier not in TIERS or not isinstance(roles, list) or any(r not in ACCOUNTABILITIES for r in roles):
            raise ModelError("rules.accountable", tier)
    if "SUPERVISOR" not in (accountable.get("HIGH") or []):
        raise ModelError("floor.accountable.HIGH", "HIGH needs SUPERVISOR")


def claim_values(claims: dict, name: str) -> list[str]:
    """README.md, section 5, steps 1-2: a dotted claim name, strings split on whitespace."""
    value = claims
    for part in name.split("."):
        if not isinstance(value, dict) or part not in value:
            return []
        value = value[part]
    if isinstance(value, str):
        return value.split()
    if isinstance(value, list):
        return [v for v in value if isinstance(v, str)]
    return []


def resolve(model: dict, claims: dict, app: str) -> list[str]:
    """The functions `app` grants to a token with these claims (README.md, section 5)."""
    granted = set()
    mapping = model["map"]
    sources = (("person", model["identity"]["people"]["claim"]),
               ("machine", model["identity"]["machines"]["claim"]))
    for kind, claim in sources:
        for value in claim_values(claims, claim):
            for grant in mapping.get(value, []):
                function, domain, grant_app = parse_grant(grant)
                fkind, apps = FUNCTIONS[function]
                if fkind != kind:
                    continue
                if (grant_app or app) != app or app not in apps:
                    continue
                granted.add(f"{function}:{domain}" if domain else function)
    return sorted(granted)
