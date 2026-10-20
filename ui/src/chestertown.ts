import { chestertownApps } from "@chestertown/design";

// The Chestertown app-switcher: one platform, three modules (three lines of defense).
// URLs are deploy-configured (VITE_AOS_URL etc.); unset ones fall back to the dev defaults.
const env = import.meta.env as Record<string, string | undefined>;
export const APPS = chestertownApps({
  operate: env.VITE_AOS_URL,
  govern: env.VITE_PRUDENCE_URL,
  assure: env.VITE_ERNEST_URL,
});

/// Deep link to an AI system's evidence in Ernest: its decisions (from Prudence) and executions
/// (from AOS) are recorded under the system's id, which Ernest's Models page opens directly.
export const ernestEvidenceUrl = (aiSystemId: string) =>
  `${APPS.find((a) => a.vertex === "assure")!.url}/models?model=${encodeURIComponent(aiSystemId)}`;
