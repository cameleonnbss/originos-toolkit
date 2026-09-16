# Permissions

This is the complete permission set of the Android app. It is **locked**: the CI workflow
`.github/workflows/android.yml` greps `AndroidManifest.xml` and fails the build if a new
`uses-permission` appears that is not listed here. That is how
[SECURITY.md](../SECURITY.md) keeps its promise that privilege never grows silently.

| Permission | Why it exists | Used by | Optional? |
| --- | --- | --- | --- |
| `android.permission.SYSTEM_ALERT_WINDOW` | Draw the floating frame-rate readout over other apps. | `FpsOverlayService` | Yes — the overlay switch prompts for it and does nothing until granted |
| `android.permission.PACKAGE_USAGE_STATS` | Know which app is in the foreground, so per-app refresh profiles know what to apply. | `PerAppRefreshService` | Yes — only the per-app watcher needs it |
| `android.permission.QUERY_ALL_PACKAGES` | Show the real package list in the debloat screen and the per-app picker. | `DebloatScreen`, `RefreshScreen` | No, but it grants no privilege: any app can list packages it can see, this just removes the API 30+ visibility filter |
| `android.permission.FOREGROUND_SERVICE` | Keep the overlay and the refresh watcher alive while you use other apps. | both services | Structural |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ requires a declared type for these two services. | both services | Structural |
| `android.permission.POST_NOTIFICATIONS` | Show the foreground-service notification on Android 13+. | both services | Yes — the services run silently if denied |
| `moe.shizuku.manager.permission.API_V23` | Speak to Shizuku's binder API. | `ShizukuShell` | Required for any tweak |

## Permissions that are deliberately **not** requested

| Not requested | Consequence |
| --- | --- |
| `android.permission.INTERNET` | The app cannot reach the network. There is no analytics, no update check, no crash reporting. |
| `android.permission.ACCESS_NETWORK_STATE` | Nothing to report. |
| `android.permission.READ_/WRITE_EXTERNAL_STORAGE` | The app only writes to its own `SharedPreferences`. |
| `android.permission.REQUEST_INSTALL_PACKAGES` | It never installs anything. |
| `android.permission.REBOOT`, `WRITE_SECURE_SETTINGS`, … | `WRITE_SECURE_SETTINGS` would let the app skip Shizuku entirely, and would also be a genuine privilege escalation vector. It is intentionally absent: every write goes through Shizuku's mediated binder instead. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Nothing auto-starts. You decide when a service runs. |

## Adding a permission

1. Justify it in the pull request against the table above.
2. Add the row to this file.
3. Add the permission name to the `allowed` regex in `.github/workflows/android.yml`.

A pull request that adds a permission without all three will fail CI, on purpose.
