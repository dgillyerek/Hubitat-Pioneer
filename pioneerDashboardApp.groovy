/**
 * Pioneer AVR Dashboard — Hubitat companion app
 *
 * Installs a local web remote at:
 *   http://[hub-ip]/local/pioneer-avr-dashboard.html
 *
 * Writes discovery/settings for the page at:
 *   http://[hub-ip]/local/pioneer-avr-token.json
 *
 * The dashboard uses Hubitat Maker API to run Pioneer zone commands
 * (inputNext, volumeUp, on/off, etc.) on your Main and HD Zone child devices.
 */

import groovy.transform.Field

definition(
    name:         "Pioneer AVR Dashboard",
    namespace:    "PioneerDG",
    author:       "Derek Gilbert",
    description:  "Local web remote for Pioneer AVR zones (Main + HD Zone).",
    category:     "Convenience",
    iconUrl:      "",
    iconX2Url:    "",
    oauthEnabled: false
)

preferences {
    page(name: "mainPage")
}

@Field static final String APP_VERSION         = "1.1.0"
@Field static final String DASHBOARD_FILENAME  = "pioneer-avr-dashboard.html"
@Field static final String TOKEN_FILENAME      = "pioneer-avr-token.json"
@Field static final String DEFAULT_DASHBOARD_URL = "https://raw.githubusercontent.com/dgillyerek/Hubitat-Pioneer/main/tools/pioneer-avr-dashboard.html"

@Field static final Map CORS_HEADERS = [
    "Access-Control-Allow-Origin":  "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
]

mappings {
    path("/pioneer-token") {
        action: [ GET: "getToken", OPTIONS: "preflight" ]
    }
    path("/pioneer-version") {
        action: [ GET: "getVersion", OPTIONS: "preflight" ]
    }
}

def preflight() {
    render contentType: "text/plain", headers: CORS_HEADERS, data: ""
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Pioneer AVR Dashboard", install: true, uninstall: true, refreshInterval: 0) {
        section("Open dashboard") {
            def hubIp = location.hubs[0]?.localIP ?: "your-hub-ip"
            paragraph "After saving, open on any device on your LAN:\n\n" +
                "<a href=\"http://${hubIp}/local/${DASHBOARD_FILENAME}\" target=\"_blank\">" +
                "http://${hubIp}/local/${DASHBOARD_FILENAME}</a>"
        }

        section("Devices") {
            input name: "mainZoneDevice", type: "device", title: "Main zone device", required: true,
                description: "Pioneer AVR Main child device"
            input name: "hdZoneDevice", type: "device", title: "HD Zone device (optional)", required: false,
                description: "Pioneer AVR Zone 4 child device"
        }

        section("Maker API") {
            paragraph "Enable Maker API under Settings. Include your Pioneer zone devices in the allowed list."
            input name: "makerAppId", type: "number", title: "Maker API App ID", required: true
            input name: "makerAccessToken", type: "password", title: "Maker API Access Token", required: true
        }

        section("Dashboard file") {
            input name: "dashboardUrl", type: "text", title: "Dashboard HTML URL", required: true,
                defaultValue: DEFAULT_DASHBOARD_URL
            input "updateDashboard", "button", title: "Download / update dashboard file"
        }

        section("Status") {
            def hubIp = location.hubs[0]?.localIP ?: "?"
            paragraph "App: v${APP_VERSION}\n" +
                "Dashboard: ${state.dashboardInstalled ? 'installed (v' + (state.dashboardVersion ?: '?') + ')' : 'not installed — click Done or Update'}\n" +
                "Token file: ${state.tokenWritten ? 'written (refreshes every 2s)' : 'not written'}\n" +
                "Hub IP: ${hubIp}"
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "updateDashboard") {
        downloadDashboard(true)
    }
}

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    unschedule()
    downloadDashboard(false)
    writeTokenFile()
    runIn(2, "refreshTokenLoop")
    log.info "Pioneer AVR Dashboard v${APP_VERSION} ready — app ID ${app.id}"
}

def refreshTokenLoop() {
    writeTokenFile()
    runIn(2, "refreshTokenLoop")
}

def downloadDashboard(Boolean force) {
    def url = settings?.dashboardUrl ?: DEFAULT_DASHBOARD_URL
    if (!force && state.dashboardInstalled) {
        return
    }
    try {
        httpGet([uri: url, textParser: true, timeout: 30]) { response ->
            if (response.status == 200) {
                def content = response.data.text
                uploadHubFile(DASHBOARD_FILENAME, content.getBytes("UTF-8"))
                def versionMatch = (content =~ /PIONEER_DASHBOARD_VERSION\s*=\s*['"]([^'"]+)['"]/)
                state.dashboardVersion = versionMatch ? versionMatch[0][1] : APP_VERSION
                state.dashboardInstalled = true
                log.info "Pioneer dashboard v${state.dashboardVersion} installed → /local/${DASHBOARD_FILENAME}"
            } else {
                log.warn "Pioneer dashboard download failed — HTTP ${response.status} from ${url}"
            }
        }
    } catch (e) {
        log.warn "Pioneer dashboard download failed: ${e.message}"
    }
}

def writeTokenFile() {
    try {
        def hubIp = location.hubs[0]?.localIP
        if (!hubIp) {
            state.tokenWritten = false
            return
        }
        def mainId = resolveDeviceId(settings?.mainZoneDevice)
        if (!mainId || !settings?.makerAppId || !settings?.makerAccessToken) {
            state.tokenWritten = false
            log.warn "Pioneer dashboard: set Main device, Maker API ID, and token, then Save"
            return
        }
        def payload = [
            hubIp:              hubIp,
            makerAppId:         settings.makerAppId.toString(),
            makerAccessToken:   settings.makerAccessToken.toString(),
            mainDeviceId:       mainId,
            hdZoneDeviceId:     resolveDeviceId(settings?.hdZoneDevice) ?: "",
            mainState:          buildZoneState(settings?.mainZoneDevice),
            hdState:            buildZoneState(settings?.hdZoneDevice),
            dashboardVersion:   state.dashboardVersion ?: APP_VERSION,
            appVersion:         APP_VERSION
        ]
        def json = new groovy.json.JsonBuilder(payload).toString()
        uploadHubFile(TOKEN_FILENAME, json.getBytes("UTF-8"))
        state.tokenWritten = true
        log.info "Pioneer dashboard token file written → /local/${TOKEN_FILENAME}"
    } catch (e) {
        state.tokenWritten = false
        log.warn "Pioneer dashboard token write failed: ${e.message}"
    }
}

private String resolveDeviceId(deviceRef) {
    if (!deviceRef) {
        return null
    }
    if (deviceRef instanceof String) {
        return deviceRef
    }
    return deviceRef.id?.toString()
}

def getToken() {
    writeTokenFile()
    def hubIp = location.hubs[0]?.localIP
    def payload = [
        hubIp:              hubIp,
        makerAppId:         settings?.makerAppId?.toString() ?: "",
        makerAccessToken:   settings?.makerAccessToken?.toString() ?: "",
        mainDeviceId:       resolveDeviceId(settings?.mainZoneDevice) ?: "",
        hdZoneDeviceId:     resolveDeviceId(settings?.hdZoneDevice) ?: "",
        mainState:          buildZoneState(settings?.mainZoneDevice),
        hdState:            buildZoneState(settings?.hdZoneDevice),
        dashboardVersion:   state.dashboardVersion ?: APP_VERSION,
        appVersion:         APP_VERSION
    ]
    render contentType: "application/json", headers: CORS_HEADERS,
           data: new groovy.json.JsonBuilder(payload).toString()
}

private Map buildZoneState(deviceRef) {
    if (!deviceRef) {
        return null
    }
    try {
        return [
            switch:           deviceRef.currentValue("switch"),
            volumeDb:         deviceRef.currentValue("volumeDb"),
            volume:           deviceRef.currentValue("volume"),
            mute:             deviceRef.currentValue("mute"),
            input:            deviceRef.currentValue("input"),
            mediaInputSource: deviceRef.currentValue("mediaInputSource")
        ]
    } catch (e) {
        log.warn "Pioneer dashboard buildZoneState failed: ${e.message}"
        return null
    }
}

def getVersion() {
    render contentType: "application/json", headers: CORS_HEADERS,
           data: new groovy.json.JsonBuilder([
               app:                "Pioneer AVR Dashboard",
               appVersion:         APP_VERSION,
               dashboardInstalled: state.dashboardInstalled == true,
               dashboardVersion:   state.dashboardVersion ?: "",
               tokenWritten:       state.tokenWritten == true
           ]).toString()
}
