import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import { mintToken, makeLimiter } from "./token.mjs";

// Throwaway key generated in-test. The real .p8 is never needed here.
const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const pem = privateKey.export({ type: "pkcs8", format: "pem" });
const base = { teamId: "2M956232BT", keyId: "GSSMVT3KYL", mediaId: "media.com.mundymorningstudios.touchmusicid", privateKeyPem: pem };
const dec = (s) => JSON.parse(Buffer.from(s, "base64url").toString());

test("mints a verifiable ES256 JWT with the expected header and claims", () => {
  const { token, expiresAt } = mintToken({ ...base, now: () => 1000, ttlSeconds: 600 });
  const [h, c, s] = token.split(".");
  assert.deepEqual(dec(h), { alg: "ES256", kid: "GSSMVT3KYL", id: "2M956232BT.media.com.mundymorningstudios.touchmusicid" });
  assert.deepEqual(dec(c), { iss: "2M956232BT", iat: 1000, exp: 1600, sub: base.mediaId });
  assert.equal(expiresAt, 1600);
  assert.ok(verify("sha256", Buffer.from(`${h}.${c}`), { key: publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(s, "base64url")));
});

test("refuses to mint when not configured, without leaking anything", () => {
  assert.throws(() => mintToken({ ...base, privateKeyPem: "" }), /not configured/);
});

test("rate limiter blocks after the limit and resets next window", () => {
  let t = 0; const allow = makeLimiter(2, 1000, () => t);
  assert.ok(allow("a")); assert.ok(allow("a")); assert.ok(!allow("a")); assert.ok(allow("b"));
  t = 1001; assert.ok(allow("a"));
});
