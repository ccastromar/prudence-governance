// SSO for the Prudence UI — Authorization Code + PKCE against the trio IdP (Keycloak,
// client prudence-spa). Opt-in: enabled only when VITE_OIDC_ISSUER is set. The resulting
// access token (carrying the realm `roles` claim) is sent as a bearer to the backend,
// which validates it as an OAuth2 resource server.
const env = import.meta.env as Record<string, string | undefined>;
const ISSUER = env.VITE_OIDC_ISSUER?.replace(/\/$/, '');
const CLIENT_ID = env.VITE_OIDC_CLIENT_ID ?? 'prudence-spa';

export const ssoEnabled = Boolean(ISSUER && CLIENT_ID);

const TOKEN_KEY = 'prudence_token';
const VERIFIER_KEY = 'prudence_pkce_verifier';
const STATE_KEY = 'prudence_pkce_state';

export function getToken(): string {
  try { return sessionStorage.getItem(TOKEN_KEY) ?? ''; } catch { return ''; }
}
export function clearToken() { try { sessionStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ } }

function b64url(bytes: Uint8Array): string {
  let s = ''; for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function rand(n = 32): string { const a = new Uint8Array(n); crypto.getRandomValues(a); return b64url(a); }
async function challenge(v: string): Promise<string> {
  return b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(v))));
}
const redirectUri = () => `${window.location.origin}/`;

export async function login() {
  if (!ssoEnabled) return;
  const verifier = rand(48);
  const state = rand();
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const p = new URLSearchParams({
    response_type: 'code', client_id: CLIENT_ID, redirect_uri: redirectUri(),
    scope: 'openid profile email', state, code_challenge: await challenge(verifier), code_challenge_method: 'S256',
  });
  window.location.assign(`${ISSUER}/protocol/openid-connect/auth?${p}`);
}

/** If the URL carries ?code, exchange it for a token. Returns true if a login completed. */
export async function completeLoginIfPresent(): Promise<boolean> {
  const url = new URL(window.location.href);
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  if (!code) return false;
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  const expected = sessionStorage.getItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY); sessionStorage.removeItem(STATE_KEY);
  // strip query from the address bar regardless
  window.history.replaceState({}, '', url.origin + url.pathname);
  if (!verifier || !state || state !== expected) return false;

  const body = new URLSearchParams({
    grant_type: 'authorization_code', code, redirect_uri: redirectUri(), client_id: CLIENT_ID, code_verifier: verifier,
  });
  const res = await fetch(`${ISSUER}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
  });
  if (!res.ok) return false;
  const tok = await res.json();
  if (tok.access_token) { sessionStorage.setItem(TOKEN_KEY, tok.access_token); return true; }
  return false;
}
