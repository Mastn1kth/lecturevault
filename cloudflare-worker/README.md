# LectureVault AI Gateway

Cloudflare Worker keeps provider keys off Android, Windows, iPhone and Mac clients. It routes audio to Groq and text generation through Gemini, then OpenRouter models, then Together when configured.

## Deploy

```bash
cd cloudflare-worker
npm install
npx wrangler login
npx wrangler secret put GROQ_API_KEY
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put OPENROUTER_API_KEY       # optional
npx wrangler secret put TOGETHER_API_KEY         # optional
npx wrangler deploy
```

Set non-secret routing values with `npx wrangler secret put` too:

- `OPENROUTER_MODELS`: comma-separated model ids, in desired fallback order.
- `TOGETHER_MODEL`: Together model id.
- `ALLOWED_ORIGINS`: comma-separated web origins; native applications do not send an Origin header.

Endpoints: `GET /health`, `POST /v1/transcribe` (multipart `file`), `POST /v1/generate` (`summary` or `quiz`).

Do not put provider keys in `wrangler.jsonc`, Git, APK, IPA or JAR. Before publishing to testers, add authentication and rate limiting; a public unauthenticated AI gateway can be abused even if its provider keys remain hidden.
