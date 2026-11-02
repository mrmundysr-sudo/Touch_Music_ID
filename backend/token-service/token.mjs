// Apple developer-token (ES256 JWT) minting + HTTP handler. Pure WebCrypto: runs unchanged on Node >= 18 and
// Cloudflare Workers. The .p8 private key comes ONLY from the environment / secret store. It is never logged,
// returned, or written anywhere.
//
// Token format (evidence): Apple's "Create a media identifier and private key" guide says to use the key id (kid) in a JWT
// per "Generating Developer Tokens"; that page describes ES256, header {alg, kid}, claims {iss = Team ID, iat, exp},
// exp at most 15777000 s (6 months) after iat. An open-source ShazamKit client builds exactly {alg,kid,typ}/{iss,iat,exp}.
// There is NO id header and NO sub claim. Only a live call to Apple with the real key can prove acceptance.

const b64u = (bytes) => {
  let s = ""; for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
const enc = (o) => b64u(new TextEncoder().encode(JSON.stringify(o)));

export const MAX_TTL_SECONDS = 15_777_000;

/** Accepts the .p8 text (PKCS#8 PEM). Also tolerates "\n" escapes, which secret stores often produce. */
export function pemToPkcs8(pem) {
  const body = String(pem).replace(/\\n/g, "\n").replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  if (!body) throw new Error("empty key");
  const bin = atob(body);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export async function mintToken({ teamId, keyId, privateKeyPem, ttlSeconds = 3600, now = () => Math.floor(Date.now() / 1000) }) {
  if (!teamId || !keyId || !privateKeyPem) throw new Error("not configured");
  if (!(ttlSeconds > 0 && ttlSeconds <= MAX_TTL_SECONDS)) throw new Error("bad ttl");
  const iat = now();
  const exp = iat + ttlSeconds;
  const input = `${enc({ alg: "ES256", kid: keyId, typ: "JWT" })}.${enc({ iss: teamId, iat, exp })}`;
  const key = await crypto.subtle.importKey("pkcs8", pemToPkcs8(privateKeyPem), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  // WebCrypto ECDSA returns raw r||s, which is the format JWS ES256 requires.
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(input));
  return { token: `${input}.${b64u(sig)}`, expiresAt: exp };
}

/** Fixed-window per-client limiter (per instance; add an edge rate-limit rule for real protection). */
export function makeLimiter(max = 30, windowMs = 60_000, clock = Date.now) {
  const hits = new Map();
  return (client) => {
    const t = clock();
    if (hits.size > 5000) hits.clear();
    const h = hits.get(client);
    if (!h || t - h.start >= windowMs) { hits.set(client, { start: t, n: 1 }); return true; }
    return ++h.n <= max;
  };
}

/**
 * Builds a request handler. Tokens are cached and reused until fewer than `reuseMarginSeconds` remain, so the private key
 * signs rarely and every client sees a token with a comfortable remaining lifetime.
 * env: APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_P8_PRIVATE_KEY (secret), optional TOKEN_TTL_SECONDS, RATE_LIMIT_PER_MIN.
 */
export function makeHandler({ now = () => Math.floor(Date.now() / 1000), reuseMarginSeconds = 600 } = {}) {
  let cached = null;
  let allow = null;
  return async function handle(request, env) {
    const json = (code, body, extra = {}) =>
      new Response(JSON.stringify(body), { status: code, headers: { "content-type": "application/json", "cache-control": "no-store", ...extra } });
    const url = new URL(request.url);
    if (url.pathname === "/health") return json(200, { ok: true });
    if (request.method !== "GET" || url.pathname !== "/shazam-token") return json(404, { error: "not found" });
    allow ??= makeLimiter(Number(env.RATE_LIMIT_PER_MIN || 30));
    const client = (request.headers.get("cf-connecting-ip") || request.headers.get("x-forwarded-for") || "?").split(",")[0].trim();
    if (!allow(client)) return json(429, { error: "rate limited" }, { "retry-after": "60" });
    try {
      if (!cached || cached.expiresAt - reuseMarginSeconds <= now()) {
        cached = await mintToken({
          teamId: env.APPLE_TEAM_ID, keyId: env.APPLE_KEY_ID, privateKeyPem: env.APPLE_P8_PRIVATE_KEY,
          ttlSeconds: Number(env.TOKEN_TTL_SECONDS || 3600), now,
        });
      }
      return json(200, cached);
    } catch {
      return json(503, { error: "token service not configured" }); // never echo details or key material
    }
  };
}
