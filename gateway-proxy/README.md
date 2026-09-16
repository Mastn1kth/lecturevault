# Independent gateway entry point

This small Docker service gives LectureVault a domain that users can reach without configuring VPN. It contains no Gemini, Groq, OpenRouter or Together key: it forwards HTTPS requests to the existing protected Worker, while preserving the visitor IP for the Worker rate limiter through a private shared secret.

## Deploy

1. Create an A/AAAA DNS record for `api.your-domain` to the server IP.
2. On the server, install Docker Compose and copy `.env.example` to `.env`.
3. Set `PUBLIC_HOST` to the real DNS name and generate `PROXY_TOKEN` with `openssl rand -hex 32`.
4. In `cloudflare-worker`, run `npx wrangler secret put TRUSTED_PROXY_TOKEN` and paste the same token. Never commit either secret.
5. Run `docker compose up -d` in this directory.
6. Check `https://api.your-domain/health`; it must return JSON with `ok: true`.

## Point a release at this domain

- Android: `./gradlew assembleDebug -PlectureVaultGateway=https://api.your-domain`
- Windows/macOS desktop JAR: add `-Dlecturevault.gateway=https://api.your-domain`, or set environment variable `LECTUREVAULT_GATEWAY` before launch.
- iPhone/Mac SwiftUI: in Xcode set build setting `LECTUREVAULT_GATEWAY` to `https://api.your-domain` before Archive.

The existing default is retained until a real domain is chosen, so ordinary builds continue using the current gateway.
