/**
 * Ernest evidence client: seals that a governed execution started, under the AI system's id.
 * Only SHA-256 hashes of inputs and outputs travel; raw data never leaves the process. The
 * decision itself is sealed by Prudence.
 *
 * Two transports for step records: direct (`POST /api/inferences`, the default) or Ernest's
 * event-ingestor (`POST /events`, set `ingestorUrl` or ERNEST_INGESTOR_URL), queued and
 * deduplicated by the inference id, for volume.
 *
 * Qualified electronic seals (the Python core's `QtspSealer`) are not available here yet.
 */
import { createHash, randomUUID } from "node:crypto";

/** Canonical JSON as the Python core writes it (sorted keys, no spaces, non-ASCII escaped), so
 * the same inputs give the same hash in both. */
export function canonicalJson(value: unknown): string {
  const walk = (v: unknown): unknown => {
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === "object" && !(v instanceof Date)) {
      return Object.fromEntries(
        Object.keys(v as object)
          .sort()
          .map((k) => [k, walk((v as Record<string, unknown>)[k])]),
      );
    }
    if (
      typeof v === "bigint" ||
      typeof v === "function" ||
      typeof v === "symbol"
    )
      return String(v);
    return v;
  };
  return (JSON.stringify(walk(value)) ?? "null").replace(
    /[\u007f-￿]/g,
    (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
}

export function sha256Json(value: unknown): string {
  return createHash("sha256")
    .update(canonicalJson(value), "utf8")
    .digest("hex");
}

export type EvidenceResult = [status: number | null, body: unknown];

export interface ErnestEvidenceOptions {
  /** Ernest's base URL; else ERNEST_URL, else http://127.0.0.1:3001. */
  baseUrl?: string;
  /** Else ERNEST_API_KEY. */
  apiKey?: string;
  timeoutMs?: number;
  /** Ernest's event-ingestor; else ERNEST_INGESTOR_URL (unset: direct). */
  ingestorUrl?: string;
  /** Else ERNEST_INGESTOR_KEY. */
  ingestorKey?: string;
  source?: string;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export class ErnestEvidence {
  readonly baseUrl: string;
  readonly ingestorUrl: string;
  private readonly apiKey: string;
  private readonly ingestorKey: string;
  private readonly timeoutMs: number;
  private readonly source: string;

  constructor(options: ErnestEvidenceOptions = {}) {
    const env = process.env;
    this.baseUrl = (
      options.baseUrl ||
      env.ERNEST_URL ||
      "http://127.0.0.1:3001"
    ).replace(/\/+$/, "");
    this.apiKey = options.apiKey ?? env.ERNEST_API_KEY ?? "";
    this.ingestorUrl = (
      options.ingestorUrl ||
      env.ERNEST_INGESTOR_URL ||
      ""
    ).replace(/\/+$/, "");
    this.ingestorKey = options.ingestorKey ?? env.ERNEST_INGESTOR_KEY ?? "";
    this.timeoutMs = options.timeoutMs ?? 5000;
    this.source = options.source ?? "prudence-agent";
  }

  private async post(
    url: string,
    body: unknown,
    headers: Record<string, string>,
  ): Promise<EvidenceResult> {
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...headers },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      const text = await response.text();
      try {
        return [response.status, JSON.parse(text)];
      } catch {
        return [
          response.status,
          { status: response.status, text: text.slice(0, 200) },
        ];
      }
    } catch (error) {
      return [
        null,
        { error: error instanceof Error ? error.message : String(error) },
      ];
    }
  }

  private direct(): Record<string, string> {
    return this.apiKey ? { "X-Ernest-Api-Key": this.apiKey } : {};
  }

  /** Register a model block (direct transport always). */
  ensureModel(
    modelId: string,
    modelName: string,
    version: string,
    options: {
      modelHash?: string;
      gitCommit?: string;
      metadata?: Record<string, unknown>;
    } = {},
  ): Promise<EvidenceResult> {
    const body: Record<string, unknown> = {
      modelId,
      modelName,
      version,
      mlflow: {
        modelHash:
          options.modelHash ?? sha256Json({ model: modelId, v: version }),
        gitCommit: options.gitCommit ?? "0000000",
      },
    };
    if (options.metadata) body.metadata = options.metadata;
    return this.post(`${this.baseUrl}/api/models`, body, this.direct());
  }

  /** One step record: only hashes of `inputs` and `output`. */
  logStep(
    modelId: string,
    inputs: unknown,
    output: unknown,
    metadata?: Record<string, unknown>,
  ): Promise<EvidenceResult> {
    const body: Record<string, unknown> = {
      modelId,
      inferenceId: randomUUID(),
      inputHash: sha256Json(inputs),
      outputHash: sha256Json(output),
    };
    if (metadata) body.metadata = metadata;
    if (this.ingestorUrl) {
      const headers: Record<string, string> = {
        "X-Ernest-Event-Source": this.source,
        "X-Ernest-Event-Type": "inference.logged",
        "X-Ernest-Source-Event-Id": body.inferenceId as string,
      };
      if (this.ingestorKey) headers["X-Ernest-Ingest-Key"] = this.ingestorKey;
      return this.post(`${this.ingestorUrl}/events`, body, headers);
    }
    return this.post(`${this.baseUrl}/api/inferences`, body, this.direct());
  }

  /**
   * Seal that a governed execution started (`stage: execution` under the AI system's id), which
   * Prudence compares with the runtime's own report. Pass the same `executionId` given to
   * `PrudenceGate.reportExecution`: it travels as the evidence's `correlationId`, so Prudence can
   * match the two one by one. Best effort: never throws.
   *
   * Prudence registers the system in Ernest with its first decision, which can land a moment
   * after the run started; Ernest then answers 404 or 409 and the seal is retried for a few
   * seconds, so do not await this in the tool's path.
   */
  async logExecution(
    aiSystemId: string,
    options: {
      pipeline: string;
      runtime: string;
      decisionId: string | null;
      inputs?: unknown;
      executionId?: string;
    },
  ): Promise<EvidenceResult> {
    const metadata: Record<string, unknown> = {
      stage: "execution",
      aiSystemId,
      pipeline: options.pipeline,
      runtime: options.runtime,
      decisionId: options.decisionId,
    };
    if (options.executionId) metadata.correlationId = options.executionId;
    let result: EvidenceResult = [null, { error: "not attempted" }];
    for (const delay of [500, 1000, 2000, 2000, 3000, 0]) {
      try {
        result = await this.logStep(
          aiSystemId,
          options.inputs ?? {},
          { decisionId: options.decisionId },
          metadata,
        );
      } catch (error) {
        result = [null, { error: String(error) }];
      }
      if (![null, 404, 409].includes(result[0]) || !delay) return result;
      await sleep(delay);
    }
    return result;
  }

  async verify(): Promise<unknown> {
    try {
      const response = await fetch(`${this.baseUrl}/api/verify`, {
        headers: this.direct(),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      return await response.json();
    } catch (error) {
      return { error: error instanceof Error ? error.message : String(error) };
    }
  }
}
