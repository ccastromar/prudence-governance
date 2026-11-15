/**
 * The Policy Enforcement Point client: asks the Prudence PDP (`POST /api/policy/decisions`)
 * and returns a typed decision. The enforcement point belongs in the runtime; the policy stays
 * outside, in Prudence.
 *
 * Fail closed: any transport error, timeout, unexpected status or malformed body is a DENY.
 * A governance engine that is down must never grant access.
 *
 * When the PDP is secured, the gate obtains an OAuth2 client-credentials token (`M2MAuth`, the
 * `pdp.decide` scope) and presents it as a bearer; the token is cached until shortly before it
 * expires.
 */
import { randomUUID } from "node:crypto";

import { Decision, type Verdict } from "./decision.js";
import { checkCorrelationId, currentCorrelationId } from "./run.js";

const DEFAULT_URL = "http://127.0.0.1:8099";

/** OAuth2 client credentials for a secured PDP. `tokenUrl` is the IdP's token endpoint. */
export class M2MAuth {
  private token = "";
  private expiresAt = 0;

  constructor(
    readonly tokenUrl: string,
    readonly clientId: string,
    private readonly clientSecret: string,
    readonly scope = "pdp.decide",
  ) {}

  /** From PRUDENCE_PDP_TOKEN_URL, PRUDENCE_PDP_CLIENT_ID, PRUDENCE_PDP_CLIENT_SECRET (and
   * PRUDENCE_PDP_SCOPE); undefined when any of the first three is missing. */
  static fromEnv(env: NodeJS.ProcessEnv = process.env): M2MAuth | undefined {
    const {
      PRUDENCE_PDP_TOKEN_URL: url,
      PRUDENCE_PDP_CLIENT_ID: id,
      PRUDENCE_PDP_CLIENT_SECRET: secret,
    } = env;
    return url && id && secret
      ? new M2MAuth(url, id, secret, env.PRUDENCE_PDP_SCOPE || "pdp.decide")
      : undefined;
  }

  async bearer(timeoutMs = 5000): Promise<string> {
    if (this.token && Date.now() < this.expiresAt - 30_000) return this.token;
    const response = await fetch(this.tokenUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: this.clientId,
        client_secret: this.clientSecret,
        scope: this.scope,
      }),
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok)
      throw new Error(`token endpoint answered HTTP ${response.status}`);
    const body = (await response.json()) as {
      access_token?: unknown;
      expires_in?: unknown;
    };
    if (typeof body.access_token !== "string" || !body.access_token)
      throw new Error("token endpoint gave no access_token");
    this.token = body.access_token;
    this.expiresAt = Date.now() + Number(body.expires_in ?? 300) * 1000;
    return this.token;
  }
}

export interface PrudenceGateOptions {
  /** Prudence's base URL; else PRUDENCE_PDP_URL, else PRUDENCE_URL, else http://127.0.0.1:8099. */
  baseUrl?: string;
  /** Per request, in milliseconds (default 3000). */
  timeoutMs?: number;
  /** Credentials for a secured Prudence; else `M2MAuth.fromEnv()`. Pass `null` for none. */
  auth?: M2MAuth | null;
  /** A fixed flow id for every decision of this gate; usually leave it unset and wrap each
   * agent run in `run()` instead. */
  correlationId?: string;
}

/** What `decide` answers for each status (Decision API v1, section 2). */
const EXPECTED: Record<number, Verdict> = {
  200: "ALLOW",
  202: "APPROVAL_REQUIRED",
  403: "DENY",
  503: "ERROR",
};

export class PrudenceGate {
  readonly baseUrl: string;
  readonly timeoutMs: number;
  readonly auth: M2MAuth | undefined;
  readonly correlationId: string | undefined;

  constructor(options: PrudenceGateOptions = {}) {
    const env = process.env;
    this.baseUrl = (
      options.baseUrl ||
      env.PRUDENCE_PDP_URL ||
      env.PRUDENCE_URL ||
      DEFAULT_URL
    ).replace(/\/+$/, "");
    this.timeoutMs = options.timeoutMs ?? 3000;
    this.auth =
      options.auth === null ? undefined : (options.auth ?? M2MAuth.fromEnv());
    this.correlationId = options.correlationId
      ? checkCorrelationId(options.correlationId)
      : undefined;
  }

  private async headers(): Promise<Record<string, string>> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
    };
    if (this.auth) headers.Authorization = `Bearer ${await this.auth.bearer()}`;
    return headers;
  }

  /**
   * Ask Prudence whether `pipeline` may run now. Never throws: anything but a valid ALLOW is a
   * refusal. The decision carries the flow's correlation id: the option, else the enclosing
   * `run()`, else the gate's own; with none, it is sent without one.
   */
  async decide(
    subject: string,
    pipeline: string,
    aiSystemId: string,
    action = "run",
    options: { correlationId?: string } = {},
  ): Promise<Decision> {
    const payload: Record<string, string> = {
      subject,
      pipeline,
      aiSystemId,
      action,
    };
    const correlation =
      options.correlationId || currentCorrelationId() || this.correlationId;
    if (correlation) payload.correlationId = correlation;
    let status: number;
    let body: unknown;
    try {
      const response = await fetch(`${this.baseUrl}/api/policy/decisions`, {
        method: "POST",
        headers: await this.headers(),
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      status = response.status;
      body = JSON.parse(await response.text());
    } catch (error) {
      return new Decision(
        "DENY",
        `PDP unreachable: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
    if (typeof body !== "object" || body === null || Array.isArray(body)) {
      return new Decision("DENY", `Invalid PDP response (HTTP ${status})`);
    }
    const answer = body as Record<string, unknown>;
    const expected = EXPECTED[status];
    const decisionId = answer.decisionId;
    if (
      !expected ||
      answer.decision !== expected ||
      typeof decisionId !== "string" ||
      !decisionId.trim()
    ) {
      return new Decision("DENY", `Unexpected PDP response (HTTP ${status})`);
    }
    const text = (value: unknown) => (typeof value === "string" ? value : null);
    return new Decision(
      expected,
      text(answer.reason),
      text(answer.approver),
      decisionId,
    );
  }

  /**
   * Tell Prudence this execution started under `decision` (Decision API v1,
   * `POST /api/executions`), so drift sees it even without Ernest. Best effort: never throws;
   * resolves to whether Prudence recorded it.
   */
  async reportExecution(
    decision: Decision,
    aiSystemId: string,
    pipeline: string,
    options: { runtime?: string; executionId?: string } = {},
  ): Promise<boolean> {
    const report = {
      runtime: options.runtime ?? "typescript",
      executionId: options.executionId || randomUUID(),
      decisionId: decision.decisionId,
      aiSystemId,
      pipeline,
      startedAt: new Date().toISOString(),
    };
    try {
      const response = await fetch(`${this.baseUrl}/api/executions`, {
        method: "POST",
        headers: await this.headers(),
        body: JSON.stringify(report),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      return response.status === 200 || response.status === 201;
    } catch {
      return false;
    }
  }
}
