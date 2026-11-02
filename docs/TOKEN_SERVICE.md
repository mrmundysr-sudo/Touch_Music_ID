# Developer-token service (reference contract)

ShazamKit needs an **Apple developer token** (Apple's page: "provide your own `DeveloperTokenProvider`"). The token is
signed with a private key tied to your Apple Developer account. **That key must never ship in the APK or sit in this
repository (DEC-007).** The app therefore asks a small service you host for a *short-lived* token.

> Client: `RemoteTokenSource`. Reference service: `backend/token-service/` (Cloudflare Workers, free plan). Deploy steps: `backend/token-service/DEPLOY.md`. **Not yet deployed; Apple has not yet accepted a token from it.**

**Token format** (Apple guide → "Generating Developer Tokens"; not independently confirmed because that page did not render here): ES256 JWT, header `{alg, kid, typ}`, claims `{iss: Team ID, iat, exp}`, `exp` at most 6 months out. No `id` header, no `sub` (an earlier draft of this repo wrongly had them).
> The exact JWT claims and lifetime come from Apple's "Create a media identifier and private key" guide; this document
> does not restate them because they were not verifiable here.

## HTTP contract the app expects
`GET {TOKEN_SERVICE_URL}` (set with `-PTOKEN_SERVICE_URL=https://...`; must be `https://`)

Response `200 application/json`:
```json
{ "token": "<signed developer token>", "expiresAt": 1767225600 }
```
* `expiresAt` is seconds since the Unix epoch. The app rejects blank tokens and tokens already expired, caches in memory only,
  and refetches ~60 s before expiry. Anything else (non-200, bad JSON, network error) shows an "Authorisation problem" and never a fake match.

## What the service must do
1. Hold the private key in a secret store (platform secret manager / HSM). Never log it or return it.
2. Mint tokens with the **shortest lifetime Apple allows that suits you** (minutes to hours, not months), per Apple's guide.
3. Rate-limit per IP/device; return `429` when exceeded. Do not mint unlimited tokens for anonymous callers.
4. Prefer app attestation (Google Play Integrity) so only genuine installs get tokens. Not implemented in this build.
5. CORS is irrelevant (native app). Use HTTPS only; pin nothing in the app yet (decision needed).
6. Return no user data; collect no user data. Keep logs free of IPs where possible.

## Deployment options (owner decision, not made)
Serverless function (Cloud Run/Cloud Functions/Workers) or a tiny container. Estimated effort once Apple credentials
exist: small. Cost: effectively free at low volume.

## Testing the client without Apple
`TokenSourceTest` covers: not-configured, non-HTTPS refused, parse, caching and refresh margin, expired/blank token rejected,
HTTP error, malformed JSON and network failure.
