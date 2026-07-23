# ProxyX — Requirements

This document describes what ProxyX must do. It is the single source of truth
for the app's scope. Version 1 (v1.0) features come first; future ideas are
listed separately and stay out of scope until v1.0 is stable.

## 1. Vision

ProxyX is a lightweight, fast, secure, and privacy-focused Android proxy client
for personal use. It routes Android network traffic through user-configured
proxy servers using Android's VpnService. It may be published to the Google Play
Store later if it proves stable and useful.

## 2. Goals

The app must be lightweight, fast, stable, battery-efficient, secure, ad-free,
privacy-focused, and easy to use with one hand.

## 3. Version 1 features

### 3.1 Proxy support
- SOCKS5
- HTTP
- HTTPS

### 3.2 Authentication
- No authentication
- Username + password

### 3.3 Proxy profiles
- Add, edit, delete, duplicate
- Favorite
- Search
- Sort

### 3.4 Connection
- Connect / disconnect
- Auto-reconnect
- Connection timeout
- Connection status

### 3.5 Information
- Ping
- Upload speed
- Download speed
- Connected duration

### 3.6 Logs
- Connection logs
- Error logs
- Authentication logs
- DNS logs
- Reconnection logs

### 3.7 Settings
- Dark mode
- Auto-connect
- Auto-reconnect
- DNS settings
- Notifications
- Background service

## 4. Security requirements
- No advertisements, analytics, or tracking
- Never upload the user's proxy information
- Store credentials securely (Android Keystore / EncryptedSharedPreferences)
- Never write passwords or credentials to logs
- Validate all user input
- Minimize exported Android components
- Request only the permissions actually needed
- Review permissions before each release

## 5. UI requirements
- Minimal, modern, professional
- Dark-first
- Responsive
- Easy to use with one hand
- Avoid unnecessary animations; prioritize speed and usability

## 6. Technology stack
- Language: Kotlin
- IDE: Android Studio
- UI: Jetpack Compose
- Architecture: MVVM
- Database: Room
- Async: Kotlin Coroutines
- Networking: OkHttp (where appropriate)
- VPN: Android VpnService
- Version control: Git + GitHub (private)
- Build system: Gradle

## 7. Future features (out of scope until v1.0 is stable)

Shadowsocks, VMess, VLESS, Trojan, WireGuard, DNS over HTTPS, split tunnel,
kill switch, QR code import, config import, backup & restore, Material You,
speed graph, statistics, auto-select fastest proxy.
