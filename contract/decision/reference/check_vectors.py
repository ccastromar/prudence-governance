#!/usr/bin/env python3
"""Checks the Decision API vectors against a reference gate and the OpenAPI schemas.

    python -m pip install pyyaml
    python contract/decision/reference/check_vectors.py

Prints one line per failing check and exits 1 if any fails. Prudence and the gates run the same
vectors against their own code; this makes sure the vectors agree with README.md and with each
other before they do.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
VECTORS = HERE.parent / "vectors"
SPEC = HERE.parent.parent.parent / "docs" / "api" / "decision-api-v1.yaml"

PAIRS = {200: "ALLOW", 202: "APPROVAL_REQUIRED", 403: "DENY", 503: "ERROR"}


def gate(status: int, body: str) -> dict:
    """README.md, section 2: run only on 200 + a JSON object + ALLOW + a non-blank decisionId."""
    try:
        doc = json.loads(body)
    except ValueError:
        return {"run": False}
    if not isinstance(doc, dict):
        return {"run": False}
    decision, decision_id = doc.get("decision"), doc.get("decisionId")
    well_formed = (
        PAIRS.get(status) == decision
        and isinstance(decision_id, str)
        and decision_id.strip() != ""
    )
    if not well_formed:
        return {"run": False}
    if decision == "ALLOW":
        return {"run": True, "decisionId": decision_id}
    out = {"run": False}
    if decision in ("APPROVAL_REQUIRED", "DENY"):
        out["decision"] = decision
        if decision == "APPROVAL_REQUIRED":
            out["approver"] = doc.get("approver")
    return out


def report(status: int) -> str:
    """README.md, section 3."""
    if status in (200, 201):
        return "recorded"
    if status >= 500:
        return "retry"
    return "drop"


def schema_errors(value, schema: dict, spec: dict, path: str = "$") -> list[str]:
    """The subset of JSON Schema the spec uses: $ref, allOf, type (or a list), enum, required,
    properties. A property the schema does not declare is an error: it is drift."""
    if "$ref" in schema:
        node = spec
        for part in schema["$ref"].lstrip("#/").split("/"):
            node = node[part]
        return schema_errors(value, node, spec, path)
    if "allOf" in schema:
        merged: dict = {"type": "object", "required": [], "properties": {}}
        for part in schema["allOf"]:
            while "$ref" in part:
                node = spec
                for p in part["$ref"].lstrip("#/").split("/"):
                    node = node[p]
                part = node
            merged["required"] += part.get("required", [])
            merged["properties"].update(part.get("properties", {}))
        return schema_errors(value, merged, spec, path)
    errors = []
    types = schema.get("type")
    if types is not None:
        types = types if isinstance(types, list) else [types]
        kinds = {"object": dict, "string": str, "array": list, "boolean": bool, "null": type(None)}
        if not any(isinstance(value, kinds[t]) for t in types):
            return [f"{path}: {value!r} is not {'/'.join(types)}"]
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: {value!r} not in {schema['enum']}")
    if isinstance(value, dict):
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in value:
                errors.append(f"{path}: {key} is required")
        for key, item in value.items():
            if key not in props:
                errors.append(f"{path}: {key} is not in the schema")
            else:
                errors += schema_errors(item, props[key], spec, f"{path}.{key}")
    return errors


def main() -> int:
    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    schemas = spec["components"]["schemas"]
    failures = []

    for c in json.loads((VECTORS / "gate.json").read_text())["cases"]:
        got = gate(c["status"], c["body"])
        if got != c["expect"]:
            failures.append(f"gate: {c['name']}: expected {c['expect']}, reference says {got}")
        # A body the gate accepts as well formed must also be a valid DecisionResponse.
        if c["status"] in PAIRS and (got["run"] or "decision" in got):
            for e in schema_errors(json.loads(c["body"]), schemas["DecisionResponse"], spec):
                if not (c.get("future") and e.endswith("is not in the schema")):
                    failures.append(f"gate: {c['name']}: {e}")

    for c in json.loads((VECTORS / "report.json").read_text())["cases"]:
        if report(c["status"]) != c["expect"]:
            failures.append(f"report: {c['status']}: expected {c['expect']}")

    provider = json.loads((VECTORS / "provider.json").read_text())
    for e in schema_errors(provider["request"], schemas["DecisionRequest"], spec):
        failures.append(f"provider request: {e}")
    for d in provider["decide"]:
        if PAIRS.get(d["status"]) != d["decision"]:
            failures.append(f"provider {d['outcome']}: {d['status']} does not pair with {d['decision']}")
        body = {"decision": d["decision"], "decisionId": "x", "reason": d.get("reason"),
                "approver": d.get("approver"), "policyVersion": "lab", "policyDigest": "sha256:x"}
        if gate(d["status"], json.dumps(body))["run"] != (d["outcome"] == "allow"):
            failures.append(f"provider {d['outcome']}: a gate would disagree on whether it runs")
        for e in schema_errors(body, schemas["DecisionResponse"], spec):
            failures.append(f"provider {d['outcome']}: {e}")
    base = provider["report"]["report"]
    for e in schema_errors(base, schemas["ExecutionReport"], spec):
        failures.append(f"provider report: {e}")
    for step in provider["report"]["steps"]:
        if report(step["status"]) == "retry":
            failures.append(f"provider report {step['name']}: a 5xx is not an answer to expect")

    for f in failures:
        print(f)
    print(f"{'FAIL' if failures else 'ok'}: decision contract vectors")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
