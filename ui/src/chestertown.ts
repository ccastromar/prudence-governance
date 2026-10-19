// The Chestertown app-switcher: one platform, three modules (three lines of defense).
// URLs are deploy-configured; these are dev defaults. Override with VITE_AOS_URL etc.
const env = import.meta.env as Record<string, string | undefined>;
export const APPS = [
  {
    key: "operate",
    label: "AOS",
    vertex: "operate",
    url: env.VITE_AOS_URL ?? "http://localhost:5173",
  },
  {
    key: "govern",
    label: "Prudence",
    vertex: "govern",
    url: env.VITE_PRUDENCE_URL ?? "http://localhost:5200",
  },
  {
    key: "assure",
    label: "Ernest",
    vertex: "assure",
    url: env.VITE_ERNEST_URL ?? "http://localhost:5174",
  },
] as const;
export const CURRENT = "govern"; // this app

/// Deep link to an AI system's evidence in Ernest: its decisions (from Prudence) and executions
/// (from AOS) are recorded under the system's id, which Ernest's Models page opens directly.
export const ernestEvidenceUrl = (aiSystemId: string) =>
  `${APPS.find((a) => a.key === "assure")!.url.replace(/\/+$/, "")}/models?model=${encodeURIComponent(aiSystemId)}`;
