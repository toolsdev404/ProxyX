# ProxyX — Decision Log

This file records important decisions and the reasons behind them, so future
maintenance is easier. Newest entries at the top.

## 2026-07-23 — Package name: io.github.toolsdev404.proxyx
We use the reverse-domain convention tied to the GitHub identity. Android package
names cannot contain hyphens, so the GitHub username was changed from
"tools-dev-404" to "toolsdev404" so the username and package name match.

## 2026-07-23 — Private GitHub repository
The project stores personal proxy configuration and is for personal use first,
so the repository is private. Licensing will be decided before any public release.

## 2026-07-23 — Versioning: Semantic Versioning from 0.1.0
MAJOR.MINOR.PATCH is a clear, standard scheme. Starting at 0.1.0 signals early
development; 1.0.0 will mark the first stable release.

## 2026-07-23 — Language: Kotlin
Kotlin is the official, first-class language for modern Android development.

## 2026-07-23 — UI: Jetpack Compose
Compose is the modern declarative UI toolkit — less boilerplate than XML layouts
and a better fit for a clean, dark-first interface.

## 2026-07-23 — Architecture: MVVM
MVVM separates UI, state, and data clearly, which makes the app testable and
easier to maintain.

## 2026-07-23 — Database: Room
Room gives safe, compile-checked access to a local SQLite database for profiles,
settings, and logs.

## 2026-07-23 — Async: Kotlin Coroutines
Coroutines provide structured, readable asynchronous code for networking and I/O.

## 2026-07-23 — HTTP client: OkHttp (where appropriate)
OkHttp is a proven, efficient HTTP client for the parts that need one.

## 2026-07-23 — VPN core: integrate an audited tun2socks engine
Android VpnService only provides raw IP packets. Rather than writing a full
TCP/IP stack ourselves, we will integrate a well-maintained, audited open-source
engine to translate packets into SOCKS5/HTTP proxy connections. Rule: do not
reinvent mature networking components without a compelling reason.

## 2026-07-23 — Version 1 scope
V1 supports SOCKS5, HTTP, and HTTPS proxies with profile management, connection
control, live info, logs, and settings. Advanced protocols (Shadowsocks, VMess,
VLESS, Trojan, WireGuard, DoH, etc.) are deferred until V1 is stable.
