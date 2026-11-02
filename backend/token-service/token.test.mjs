import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, createPublicKey, verify, webcrypto } from "node:crypto";
import { mintToken, makeLimiter, makeHandler, pemToPkcs8, MAX_TTL_SECONDS } from "./token.mjs";
import worker from "./worker.mjs";

// Throwaway key generated in-test. The real .p8 is never needed or present here.
const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const pem = privateKey.export({ type: "pkcs8", format: "pem" });
const env = { APPLE_TEAM_ID: "2M956232BT", APPLE_KEY_ID: "GSSMVT3KYL", APPLE_P8_PRIVATE_KEY: pem };
const dec = (s) => JSON.parse(Buffer.from(s, "base64url").toString());
const req = (path = "/shazam-token", method = "GET", headers = {}) => new Request(`https://x.test${path}`, { method, headers });

test("JWT has Apple's documented shape: ES256, kid, iss=team, iat, exp; no id/sub", async () => {
  const { token, expiresAt } = await mintToken({ teamId: "2M956232BT", keyId: "GSSMVT3KYL", privateKeyPem: pem, now: () => 1000, ttlSeconds: 600 });
  const [h, c, s] = token.split(".");
  assert.deepEqual(dec(h), { alg: "ES256", kid: "GSSMVT3KYL", typ: "JWT" });
  assert.deepEqual(dec(c), { iss: "2M956232BT", iat: 1000, exp: 1600 });
  assert.equal(expiresAt, 1600);
  assert.equal(Buffer.from(s, "base64url").length, 64, "raw r||s signature");
  assert.ok(verify("sha256", Buffer.from(`${h}.${c}`), { key: publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(s, "base64url")));
});

test("refuses bad config and over-long lifetimes", async () => {
  await assert.rejects(mintToken({ teamId: "t", keyId: "k", privateKeyPem: "" }), /not configured/);
  await assert.rejects(mintToken({ teamId: "t", keyId: "k", privateKeyPem: pem, ttlSeconds: MAX_TTL_SECONDS + 1 }), /ttl/);
  await assert.rejects(mintToken({ teamId: "t", keyId: "k", privateKeyPem: "not a key" }));
});

test("accepts a .p8 with escaped newlines (secret-store style)", () => {
  assert.deepEqual(pemToPkcs8(pem.replace(/\n/g, "\\n")), pemToPkcs8(pem));
});

test("worker: 200 contract, no-store, never contains the key", async () => {
  const r = await worker.fetch(req(), env);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("cache-control"), "no-store");
  const text = await r.text(); const body = JSON.parse(text);
  assert.match(body.token, /^[\w-]+\.[\w-]+\.[\w-]+$/);
  assert.ok(body.expiresAt > Date.now() / 1000);
  assert.ok(!text.includes("PRIVATE KEY") && !text.includes(pem.split("\n")[1]));
});

test("worker: unconfigured -> 503 without details; wrong path/method -> 404", async () => {
  const h = makeHandler();
  const r = await h(req(), {});
  assert.equal(r.status, 503); assert.deepEqual(await r.json(), { error: "token service not configured" });
  assert.equal((await h(req("/nope"), env)).status, 404);
  assert.equal((await h(req("/shazam-token", "POST"), env)).status, 404);
  assert.equal((await h(req("/health"), env)).status, 200);
});

test("tokens are reused until near expiry, then re-minted (expiration handling)", async () => {
  let t = 10_000; const h = makeHandler({ now: () => t, reuseMarginSeconds: 600 });
  const a = await (await h(req(), { ...env, TOKEN_TTL_SECONDS: "3600" })).json();
  t += 1000; const b = await (await h(req(), { ...env, TOKEN_TTL_SECONDS: "3600" })).json();
  assert.equal(b.token, a.token, "reused while > margin remains");
  t += 3000; const c = await (await h(req(), { ...env, TOKEN_TTL_SECONDS: "3600" })).json();
  assert.notEqual(c.token, a.token); assert.equal(c.expiresAt, t + 3600);
});

test("rate limit returns 429 with Retry-After, per client, and resets", async () => {
  const h = makeHandler();
  const e = { ...env, RATE_LIMIT_PER_MIN: "2" };
  const ip = { "x-forwarded-for": "1.2.3.4" };
  assert.equal((await h(req("/shazam-token", "GET", ip), e)).status, 200);
  assert.equal((await h(req("/shazam-token", "GET", ip), e)).status, 200);
  const r = await h(req("/shazam-token", "GET", ip), e);
  assert.equal(r.status, 429); assert.equal(r.headers.get("retry-after"), "60");
  assert.equal((await h(req("/shazam-token", "GET", { "x-forwarded-for": "9.9.9.9" }), e)).status, 200);
  let t = 0; const allow = makeLimiter(1, 1000, () => t);
  assert.ok(allow("a")); assert.ok(!allow("a")); t = 1001; assert.ok(allow("a"));
});
