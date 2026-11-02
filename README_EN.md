# HMWLX

**hello my world launcher X** — a Minecraft launcher.

Supports Microsoft accounts, third-party auth services (LittleSkin, etc.) and offline
accounts. Download game versions, switch accounts and launch the game — all from the UI.

> Current version `0.1-dev2`. Still in development — not for daily use yet.

---

## Features

### Accounts

All three account types live in one list:

| Type | How to sign in |
|---|---|
| **Microsoft** | Click "Sign in with Microsoft". Your browser opens the authorization page automatically and the window closes once it's done. |
| **Third-party auth** | For skin services like [LittleSkin](https://littleskin.cn). Enter the server address, username and password. |
| **Offline** | No network needed — just pick a name. Up to 16 characters; Chinese and emoji are fine. |

Click the avatar bar at the top to switch accounts at any time. Deleting a Microsoft or
third-party account means you'll have to authorize again to add it back.

### Downloads

Pulls the full version list from Mojang's official manifest, split into five tabs:

- **Releases**
- **Snapshots**
- **April Fools**
- **Legacy**
- **All**

Hit "Download" to install a version locally — it shows up in your version list right after.

> ⚠️ **You must be signed in to a valid Microsoft account to open the Downloads page.**
>
> This isn't our restriction — it's Mojang's EULA. Only accounts that own the game may
> download game files. So we verify your Microsoft account before letting you in. If
> verification fails, the page tells you *why* (expired credentials / no connection /
> rate limited) instead of just turning you away.

### Launching

Pick a version on the home screen and hit "Launch Game".

Your account token is checked first. If it couldn't be refreshed (network hiccup,
Microsoft rate limiting, or an expired login), a dialog explains the impact and lets you decide:

| Situation | Impact | Can you continue? |
|---|---|---|
| Network problem | Single-player fine; verified multiplayer servers may reject you | Yes — "Launch anyway" |
| Login expired | Token can't be renewed; retrying won't help | No — sign in again |
| Third-party login expired | Runs in offline mode; multiplayer servers will reject you | Yes (single-player only) |

### Developer settings

Under Settings → Developer Settings:

- **Debug mode** — emits DEBUG-level logs, including the underlying headless MC logs.
  Makes things slower; leave it off unless you need it.
- **Jiahao Terminal** — a black-on-green command line with direct access to the whole
  underlying engine, including features not yet exposed in the GUI.
  Requires command-line experience — **not intended for general users.**

---

## Installation

Download the file for your platform from
[GitHub Releases](https://github.com/xiaofanforfabric/HMWLX/releases) or
[CNB Releases](https://cnb.cool/xiaofanforandroid/HMWLX/HMWLX/-/releases):

| Platform | File | How to run |
|---|---|---|
| Windows | `HMWLX-<version>-windows-x64.exe` | Double-click; it self-extracts |
| Linux | `HMWLX-<version>-x86_64.AppImage` | `chmod +x` then run it |
| Debian / Ubuntu | `hmwlx_<version>_amd64.deb` | `sudo dpkg -i file.deb` |
| Fedora / openSUSE | `hmwlx-<version>.x86_64.rpm` | `sudo rpm -i file.rpm` |
| Linux (portable) | `HMWLX-linux-x64.tar.gz` | Extract and run |

No separate Java installation needed — the runtime is bundled.

---

## FAQ

**The download page says I don't have a valid account, but I'm signed in?**

Your token has probably expired. Sign in to that Microsoft account again from the account
bar. If the message mentions a network problem instead, wait a few minutes — Microsoft's
auth servers rate-limit occasionally.

**Launching says the token couldn't be refreshed. Can I still play?**

Yes. The token only matters for verified multiplayer servers; single-player and offline
servers are unaffected. Pick "Launch anyway" in the dialog.

**I think my Microsoft account was hacked?**

Check whether a proxy is involved first. If your network changes egress IP during sign-in
(e.g. turning a proxy on and off), Microsoft's risk engine may flag it as suspicious and
temporarily freeze the account. Try again on a stable network.

**Can I change the game directory?**

The default is `~/.minecraft` (`%APPDATA%\.minecraft` on Windows), matching the official
launcher — so both launchers see the same installed versions.

---

## Reporting issues

When opening an [issue](https://github.com/xiaofanforfabric/HMWLX/issues), please include:

1. Your OS and version
2. Steps to reproduce
3. Logs, if it crashes or errors (Settings → Developer Settings → enable Debug mode →
   restart and reproduce)

---

## About

HMWLX is built on top of [headlessmc](https://github.com/headlesshq/headlessmc), a headless
Minecraft manager — HMWLX wraps it in a graphical interface.

This project is not affiliated with Mojang or Microsoft. Minecraft is a trademark of
Mojang Studios.

Please read the [EULA](LEGAL/用户协议.txt) before use.

---

[简体中文](README.md)
