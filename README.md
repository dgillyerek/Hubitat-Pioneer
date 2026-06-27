# Hubitat-Pioneer

Hubitat drivers and tools for Pioneer multi-zone AVRs (telnet / ISCP).

## Drivers

| File | Purpose |
|------|---------|
| `pioneerAvrParent.groovy` | Telnet connection, zone children, input names from AVR |
| `pioneerAvrChild.groovy` | Per-zone device (power, volume, input next/prev, PushableButton) |

Paste into **Hubitat → Drivers Code**, then configure the parent device.

## Pioneer AVR Dashboard (local web remote)

Wall/tablet remote with Main + HD Zone controls (power, input ±, volume ±, mute, live input names).

| File | Purpose |
|------|---------|
| `pioneerDashboardApp.groovy` | Hubitat app — installs HTML on hub, writes Maker API token file |
| `tools/pioneer-avr-dashboard.html` | Remote UI (served from hub at `/local/pioneer-avr-dashboard.html`) |

### Setup

1. Paste **`pioneerDashboardApp.groovy`** into **Apps Code** → Save  
2. **Apps → Add User App → Pioneer AVR Dashboard**  
3. Select **Main zone** (and optional **HD Zone**) Pioneer child devices  
4. Enter **Maker API App ID** and **Access Token** (Settings → Maker API; include Pioneer devices)  
5. Click **Done**, then **Download / update dashboard file** (requires HTML on GitHub, or push this repo first)  
6. Open **`http://[hub-ip]/local/pioneer-avr-dashboard.html`** on a phone or tablet on your LAN  

Settings are also written to `/local/pioneer-avr-token.json` for automatic configuration.

## Widget Dashboard (flexible layout builder)

Drag-and-drop dashboard with customizable widgets. Layout is saved in the Hubitat app and synced to `/local/widget-dashboard-config.json`.

| File | Purpose |
|------|---------|
| `widgetDashboardApp.groovy` | Hubitat app — Maker API creds, config storage, serves HTML |
| `tools/widget-dashboard.html` | Widget UI (served from hub at `/local/widget-dashboard.html`) |

### Setup

1. Paste **`widgetDashboardApp.groovy`** into **Apps Code** → Save  
2. **Apps → Add User App → Widget Dashboard**  
3. Enter **Maker API App ID** and **Access Token** (Settings → Maker API; include devices you want on the dashboard)  
4. Click **Done**, then **Download / update dashboard file**  
5. Open **`http://[hub-ip]/local/widget-dashboard.html`** on your LAN  
6. Click **Edit layout** → **Add** a **Switches** widget → configure devices → **Save to Hub**

### Widget templates (v1)

| Template | Description |
|----------|-------------|
| **Switches** | Table of device names with a colored status light (green = on, gray = off). Tap the light to toggle. |

More widget types can be added later (buttons, text labels, etc.).

## Other tools

| File | Purpose |
|------|---------|
| `tools/query_pioneer.py` | Quick telnet command tester |
| `tools/pioneer_rgb_server.py` + `pioneer_rgb_viewer.html` | Debug `?RGBxx` input name responses |
