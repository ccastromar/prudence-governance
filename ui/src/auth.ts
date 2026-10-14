// SSO for the Prudence UI — Authorization Code + PKCE against the Chestertown IdP (Keycloak,
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
  const bytes = new TextEncoder().encode(v);
  // Browsers only expose crypto.subtle in a secure context. The LAN development
  // server is deliberately plain HTTP, so retain PKCE S256 there with the same
  // SHA-256 calculation instead of silently leaving the sign-in button inert.
  if (crypto.subtle) return b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)));
  return b64url(sha256(bytes));
}

function sha256(input: Uint8Array): Uint8Array {
  const constants = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];
  const bitLength = input.length * 8;
  const paddedLength = Math.ceil((input.length + 9) / 64) * 64;
  const padded = new Uint8Array(paddedLength);
  padded.set(input); padded[input.length] = 0x80;
  new DataView(padded.buffer).setUint32(paddedLength - 4, bitLength, false);
  let [a, b, c, d, e, f, g, h] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const rotate = (value: number, by: number) => (value >>> by) | (value << (32 - by));
  for (let offset = 0; offset < padded.length; offset += 64) {
    const words = new Uint32Array(64); const view = new DataView(padded.buffer, offset, 64);
    for (let index = 0; index < 16; index++) words[index] = view.getUint32(index * 4, false);
    for (let index = 16; index < 64; index++) {
      const x = words[index - 15], y = words[index - 2];
      words[index] = (words[index - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + words[index - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10))) >>> 0;
    }
    let [aa, bb, cc, dd, ee, ff, gg, hh] = [a, b, c, d, e, f, g, h];
    for (let index = 0; index < 64; index++) {
      const s1 = rotate(ee, 6) ^ rotate(ee, 11) ^ rotate(ee, 25);
      const choice = (ee & ff) ^ (~ee & gg);
      const nextH = (hh + s1 + choice + constants[index] + words[index]) >>> 0;
      const s0 = rotate(aa, 2) ^ rotate(aa, 13) ^ rotate(aa, 22);
      const majority = (aa & bb) ^ (aa & cc) ^ (bb & cc);
      [hh, gg, ff, ee, dd, cc, bb, aa] = [gg, ff, ee, (dd + nextH) >>> 0, cc, bb, aa, (nextH + s0 + majority) >>> 0];
    }
    [a, b, c, d, e, f, g, h] = [(a + aa) >>> 0, (b + bb) >>> 0, (c + cc) >>> 0, (d + dd) >>> 0, (e + ee) >>> 0, (f + ff) >>> 0, (g + gg) >>> 0, (h + hh) >>> 0];
  }
  const result = new Uint8Array(32); const output = new DataView(result.buffer);
  [a, b, c, d, e, f, g, h].forEach((value, index) => output.setUint32(index * 4, value, false));
  return result;
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
