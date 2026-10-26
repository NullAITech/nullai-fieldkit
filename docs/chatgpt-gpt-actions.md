# ChatGPT custom GPT (GPT Actions)

GPT Actions can't consume a stream, so the schema exposes only the non-streaming endpoints:
`getStatus`, `listDir`, `readFile`, `writeFile` and `runCommand` (`POST /v1/exec/run`).

1. `sudo zoth-relay agent add chatgpt --profile workspace`. Use `exec` only if you really want the GPT to run commands. It's a good idea to add `--allow-cmd git --allow-cmd ls ...` too.
2. Expose the relay: `cloudflared tunnel --url http://localhost:8765`. You can also set `public_url:` in `/etc/zoth-relay/config.yaml` so the schema hard-codes your URL.
3. In ChatGPT, open **Explore GPTs → Create → Configure → Actions → Create new action → Import from URL** and enter
   `https://<tunnel>.trycloudflare.com/openapi-actions.json`.
   You can also paste the output of `zoth-relay openapi --url https://<tunnel>`.
4. Under **Authentication** choose **API Key** → **Auth Type: Bearer** and paste the token.
5. Add a **Privacy policy** URL if you plan to share the GPT. Keep the GPT private: anyone who can use the GPT can use your token.

Caveats:
- Quick-tunnel URLs change on every cloudflared restart, so you have to re-import each time. Use a named Cloudflare tunnel for a stable hostname.
- ChatGPT may ask for confirmation before each action call. Leave that on for `writeFile` and `runCommand`.
- Responses are capped (`max_output_bytes`). ChatGPT also truncates large action responses on its side.
