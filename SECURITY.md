# Security Policy

## Supported version

Security fixes are applied to the latest published release.

## Reporting a vulnerability

Please use GitHub private vulnerability reporting when available. If it is unavailable, open an
issue containing only a short, non-sensitive description and ask for a private contact channel.

Do not publish production Tokens, QQ account data, cookies, QR codes, server addresses, database
files, logs, or a working exploit in a public issue.

## Deployment requirements

- Bind LLBot OneBot WebSocket and WebUI to `127.0.0.1`.
- Use a unique random Token of at least 32 characters.
- Keep the BungeeCord plugin configuration and runtime database readable only by the server account.
- Do not expose backend Minecraft servers directly to the Internet.
- Treat database failures as access-control failures; do not bypass the fail-closed behavior.
