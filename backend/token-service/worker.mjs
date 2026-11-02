// Cloudflare Workers entry point (free plan). Secrets: `wrangler secret put APPLE_P8_PRIVATE_KEY`. See DEPLOY.md.
import { makeHandler } from "./token.mjs";
const handle = makeHandler();
export default { fetch: (request, env) => handle(request, env) };
