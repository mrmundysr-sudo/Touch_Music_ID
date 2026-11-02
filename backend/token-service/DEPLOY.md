# Deploy the token service (Cloudflare Workers, free plan)

**Why this option:** it needs no credit card, has a hard free quota (100,000 requests/day; past it requests simply fail, there is no
overage billing on the free plan), keeps the `.p8` in Cloudflare's encrypted secret store, and serves HTTPS on a `workers.dev`
address with no server to maintain. Cost: **$0**. Nothing here uses a paid plan; if Cloudflare ever offers an upgrade, decline it.
(Google Cloud Run's free tier also works but requires attaching a billing account, so it is not the simplest.)

The `.p8` never enters GitHub, the APK, or this chat. Do not paste it anywhere except step 4 below.

## Steps you perform (about 10 minutes)
1. **Account:** create a free Cloudflare account at https://dash.cloudflare.com/sign-up (email + password; no card).
2. **Tools** (any computer with Node.js 18+ and this repo checked out on branch `claude/bold-pascal-nrecdm`):
   ```bash
   cd backend/token-service
   npx wrangler login        # opens a browser; approve access
   ```
3. **Deploy the code** (first run asks you to choose a `workers.dev` subdomain; accept the free plan):
   ```bash
   npx wrangler deploy
   ```
   It prints your address, like `https://touch-music-id-token.<your-subdomain>.workers.dev`.
4. **Store the key as a secret** (point it at wherever you saved the Apple file; the key text is not shown or logged):
   ```bash
   npx wrangler secret put APPLE_P8_PRIVATE_KEY < /path/to/AuthKey_GSSMVT3KYL.p8
   ```
   (Windows PowerShell: `Get-Content -Raw C:\path\AuthKey_GSSMVT3KYL.p8 | npx wrangler secret put APPLE_P8_PRIVATE_KEY`)
5. **Check it works:**
   ```bash
   curl https://touch-music-id-token.<your-subdomain>.workers.dev/shazam-token
   ```
   Expected: `{"token":"xxxxx.yyyyy.zzzzz","expiresAt":1767...}`. `503 token service not configured` means step 4 did not take.
   Never post the token output publicly while testing; it is short-lived but is a credential.
6. **Tell the app the address** (GitHub: repo → Settings → Secrets and variables → Actions → **Variables** tab → New repository variable):
   Name `TOKEN_SERVICE_URL`, value `https://touch-music-id-token.<your-subdomain>.workers.dev/shazam-token`.
   It is a variable, not a secret, because the address is public by design; it contains no credential.
7. **Build the APK:** repo → Actions → "AutoBuild (dev branches)" → Run workflow → branch `claude/bold-pascal-nrecdm`. Download the
   `Touch-Music-ID-debug-N` artifact, install on your phone, and tap Listen near music.
8. **Optional abuse protection (still free):** Cloudflare dashboard → your Worker → Security → add a Rate Limiting rule (the free
   plan includes one) for path `/shazam-token`, for example 20 requests / minute / IP. The code also has its own per-instance limit.

## Key hygiene
* Apple allows two keys per media identifier: to rotate, create a second key, `wrangler secret put` it (update `APPLE_KEY_ID` in
  `wrangler.toml` to match, redeploy), then revoke the old one in the Apple developer portal.
* If the `.p8` is ever exposed, revoke it in Apple's portal immediately and create a new one.
* Anyone who can call the endpoint can obtain a short-lived token (the app has to be able to). Tokens last 1 hour and cannot reveal the key.
  Stronger device attestation (Play Integrity) is a later hardening step.

## Local test (no account, no key)
```bash
cd backend/token-service && npm test
```
Uses a throwaway key generated in the test. Does not contact Apple.
