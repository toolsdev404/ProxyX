# ProxyX

ProxyX is a lightweight, fast, and privacy-focused Android proxy client for
personal use. It routes device network traffic through user-configured proxy
servers using Android's VpnService.

> ⚠️ Status: Early development (v0.1.0). Not ready for use yet.

## Planned features (v1.0)

- Proxy support: SOCKS5, HTTP, HTTPS
- Authentication: none, or username / password
- Proxy profiles: add, edit, delete, duplicate, favorite, search, sort
- Connection: connect / disconnect, auto-reconnect, timeout, live status
- Live info: ping, upload / download speed, connected duration
- Logs: connection, error, authentication, DNS, reconnection
- Settings: dark mode, auto-connect, DNS, notifications, background service

## Principles

- No ads, no analytics, no tracking
- Credentials stored securely on-device; proxy info is never uploaded
- Requests only the permissions the app truly needs

## Tech stack

- Language: Kotlin
- UI: Jetpack Compose (dark-first)
- Architecture: MVVM
- Database: Room
- Async: Kotlin Coroutines
- Networking: OkHttp (where appropriate) + Android VpnService

## Documentation

- Requirements.md — what the app must do
- Roadmap.md — milestones and progress
- Architecture.md — how the app is structured
- Changelog.md — what changed in each version
- DecisionLog.md — important decisions and why
- LessonsLearned.md — notes learned while building

## Build

Setup instructions will be added in Milestone 1 (project setup).

## License

Not licensed yet (private project). To be decided before any public release.# ProxyX
Lightweight private Android proxy client
