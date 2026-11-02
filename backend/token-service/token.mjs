// Mints short-lived Apple developer tokens (ES256 JWT) for ShazamKit. No dependencies (Node >= 18).
// The .p8 private key is supplied ONLY via the environment (APPLE_P8_PRIVATE_KEY), from your host's secret store.
// It is never read from disk by default, never logged, and never returned.
import { createPrivateKey, sign } from "node:crypto";

const b64u = (b) => Buffer.from(b).toString("base64url");

/**
 * @param {{teamId:string,keyId:string,mediaId:string,privateKeyPem:string,ttlSeconds?:number,now?:()=>number}} c
 * Header/claims follow Apple's "Create a media identifier and private key" guide as understood on 2026-11-02:
 * header {alg:ES256, kid:<Media Services key id>, id:"<teamId>.<mediaId>"}, claims {iss:<teamId>, iat, exp, sub:<mediaId>}.
 * VERIFY these against Apple's current guide before production: they could not be confirmed offline.
 */
export function mintToken({ teamId, keyId, mediaId, privateKeyPem, ttlSeconds = 3600, now = () => Math.floor(Date.now() / 1000) }) {
  if (!teamId || !keyId || !mediaId || !privateKeyPem) throw new Error("token service is not configured");
  const iat = now();
  const exp = iat + ttlSeconds;
  const header = { alg: "ES256", kid: keyId, id: `${teamId}.${mediaId}` };
  const claims = { iss: teamId, iat, exp, sub: mediaId };
  const input = `${b64u(JSON.stringify(header))}.${b64u(JSON.stringify(claims))}`;
  const sig = sign("sha256", Buffer.from(input), { key: createPrivateKey(privateKeyPem), dsaEncoding: "ieee-p1363" });
  return { token: `${input}.${b64u(sig)}`, expiresAt: exp };
}

/** Fixed-window per-client limiter (in memory; use your platform's limiter if you scale out). */
export function makeLimiter(max = 30, windowMs = 60_000, clock = Date.now) {
  const hits = new Map();
  return (client) => {
    const t = clock();
    const h = hits.get(client);
    if (!h || t - h.start >= windowMs) { hits.set(client, { start: t, n: 1 }); return true; }
    return ++h.n <= max;
  };
}
