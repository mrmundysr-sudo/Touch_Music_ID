// Alternative: run the same handler on plain Node (any host with TLS in front). Env vars as in token.mjs.
import { createServer } from "node:http";
import { makeHandler } from "./token.mjs";
const handle = makeHandler();
createServer(async (req, res) => {
  const r = await handle(new Request(`http://localhost${req.url}`, { method: req.method, headers: req.headers }), process.env);
  res.writeHead(r.status, Object.fromEntries(r.headers)); res.end(await r.text());
}).listen(Number(process.env.PORT || 8080));
