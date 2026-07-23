# ProxyX — Architecture

This document explains how ProxyX is structured. It will grow as the app is
built. For now it records the intended design so every decision has a home.

## Overview

ProxyX follows the MVVM pattern (Model – View – ViewModel). The main rule is
separation of concerns: the UI never talks to the database or the network
directly — it always goes through a ViewModel and a Repository.

## Layers (top to bottom)

1. UI (View) — Jetpack Compose screens. Show state and forward user actions.
   Contain no business logic.
2. ViewModel — Prepares and holds UI state (using Kotlin StateFlow). Survives
   screen rotation. Contains no Android View/UI code.
3. Repository — The single source of truth. Decides where data comes from and
   exposes it to ViewModels.
4. Data sources:
   - Room database — proxy profiles, settings, logs, favorites.
   - Proxy / VPN engine — the connection layer built on Android VpnService.

## Data flow (one direction)

1. The user acts in the UI (for example, taps "Connect").
2. The UI tells the ViewModel.
3. The ViewModel asks the Repository to do the work.
4. The Repository updates the database and/or the VPN engine.
5. The new state flows back up to the ViewModel, then to the UI.

This one-way flow makes the app predictable and easier to debug.

## The VPN / proxy core (planned, Milestones 8–10)

Android's VpnService gives us a TUN interface: a stream of raw IP packets.
To send that traffic through a SOCKS5 / HTTP proxy, we need a userspace layer
that translates packets into proxy connections (called "tun2socks"). We will
integrate a well-maintained, audited open-source engine for this instead of
writing a full TCP/IP stack ourselves. See DecisionLog.md for the reasoning.

## Folder structure

The Android project's folder structure will be added in Milestone 1 and kept
up to date here as the app grows.
