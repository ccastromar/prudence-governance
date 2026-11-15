/** A Prudence decision, as the gate returns it (Decision API v1). */
export type Verdict = "ALLOW" | "DENY" | "APPROVAL_REQUIRED" | "ERROR";

export class Decision {
  constructor(
    /** ALLOW | DENY | APPROVAL_REQUIRED | ERROR */
    readonly decision: Verdict,
    readonly reason: string | null = null,
    readonly approver: string | null = null,
    /** The id Prudence recorded it under; always set on a decision Prudence actually made. */
    readonly decisionId: string | null = null,
  ) {}

  get allowed(): boolean {
    return this.decision === "ALLOW";
  }

  /**
   * What to tell the model when the call may not run: the verdict, why, and who must approve.
   * Adapters return it instead of the tool's result (contract/adapter/, section 4).
   */
  refusal(): string {
    let text = `Not permitted by governance (Prudence ${this.decision})`;
    if (this.reason) text += `: ${this.reason}`;
    if (this.approver) text += `. Approval required from: ${this.approver}`;
    return `${text}.`;
  }
}
