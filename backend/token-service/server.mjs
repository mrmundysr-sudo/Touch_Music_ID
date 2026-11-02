// Minimal HTTPS-fronted token endpoint. Run behind TLS (Cloud Run, Fly, a reverse proxy...). Contract: docs/TOKEN_SERVICE.md
import { createServer } from "node:http";
import { mintToken, makeLimiter } from "./token.mjs";

const cfg = {
  teamId: process.env.APPLE_TEAM_ID,            // 2M956232BT
  keyId: process.env.APPLE_KEY_ID,              // GSSMVT3KYL (Media Services key)
  mediaId: process.env.APPLE_MEDIA_ID,          // media.com.mundymorningstudios.touchmusicid
  privateKeyPem: process.env.APPLE_P8_PRIVATE_KEY, // contents of the .p8, from a secret manager. NEVER commit or log.
  ttlSeconds: Number(process.env.TOKEN_TTL_SECONDS || 3600),
};
const allow = makeLimiter(Number(process.env.RATE_LIMIT_PER_MIN || 30));

createServer((req, res) => {
  const send = (code, body) => { res.writeHead(code, { "content-type": "application/json", "cache-control": "no-store" }); res.end(JSON.stringify(body)); };
  if (req.method !== "GET" || req.url.split("?")[0] !== "/shazam-token") return send(404, { error: "not found" });
  const client = (req.headers["x-forwarded-for"] || req.socket.remoteAddress || "?").toString().split(",")[0].trim();
  if (!allow(client)) return send(429, { error: "rate limited" });
  try { send(200, mintToken(cfg)); }
  catch { send(503, { error: "token service not configured" }); } // never echo details or key material
}).listen(Number(process.env.PORT || 8080));
