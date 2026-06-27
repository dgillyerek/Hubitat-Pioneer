/**
 * Widget Dashboard — flexible Hubitat dashboard builder
 *
 * Serves a configurable widget dashboard at:
 *   http://[hub-ip]/local/widget-dashboard.html
 *
 * Configuration (layout, widgets) is stored in app state and mirrored to:
 *   http://[hub-ip]/local/widget-dashboard-config.json
 *
 * The dashboard uses Hubitat Maker API to query devices and run commands.
 */

import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

definition(
    name:         "Widget Dashboard",
    namespace:    "PioneerDG",
    author:       "Derek Gilbert",
    description:  "Flexible drag-and-drop dashboard with customizable widgets.",
    category:     "Convenience",
    iconUrl:      "",
    iconX2Url:    "",
    oauthEnabled: false
)

preferences {
    page(name: "mainPage")
}

@Field static final String APP_VERSION          = "1.0.0"
@Field static final String DASHBOARD_FILENAME   = "widget-dashboard.html"
@Field static final String CONFIG_FILENAME       = "widget-dashboard-config.json"
@Field static final String DEFAULT_DASHBOARD_URL = "https://raw.githubusercontent.com/dgillyerek/Hubitat-Pioneer/main/tools/widget-dashboard.html"

@Field static final Map CORS_HEADERS = [
    "Access-Control-Allow-Origin":  "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
]

mappings {
    path("/config") {
        action: [ GET: "getConfig", POST: "saveConfig", OPTIONS: "preflight" ]
    }
    path("/version") {
        action: [ GET: "getVersion", OPTIONS: "preflight" ]
    }
}

def preflight() {
    render contentType: "text/plain", headers: CORS_HEADERS, data: ""
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Widget Dashboard", install: true, uninstall: true, refreshInterval: 0) {
        section("Open dashboard") {
            def hubIp = location.hubs[0]?.localIP ?: "your-hub-ip"
            paragraph "After saving, open on any device on your LAN:\n\n" +
                "<a href=\"http://${hubIp}/local/${DASHBOARD_FILENAME}\" target=\"_blank\">" +
                "http://${hubIp}/local/${DASHBOARD_FILENAME}</a>\n\n" +
                "Use <strong>Edit layout</strong> in the dashboard to add widgets and save your layout."
        }

        section("Maker API") {
            paragraph "Enable Maker API under Settings. Include the devices you want on the dashboard in the allowed list."
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
            def widgetCount = countWidgets()
            paragraph "App: v${APP_VERSION}\n" +
                "Dashboard: ${state.dashboardInstalled ? 'installed (v' + (state.dashboardVersion ?: '?') + ')' : 'not installed — click Done or Update'}\n" +
                "Config file: ${state.configWritten ? 'written' : 'not written'}\n" +
                "Widgets: ${widgetCount}\n" +
                "Hub IP: ${hubIp}\n" +
                "App install ID: ${app.id}"
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
    writeConfigFile()
    runIn(2, "refreshConfigLoop")
    log.info "Widget Dashboard v${APP_VERSION} ready — install ID ${app.id}"
}

def refreshConfigLoop() {
    writeConfigFile()
    runIn(2, "refreshConfigLoop")
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
                def versionMatch = (content =~ /WIDGET_DASHBOARD_VERSION\s*=\s*['"]([^'"]+)['"]/)
                state.dashboardVersion = versionMatch ? versionMatch[0][1] : APP_VERSION
                state.dashboardInstalled = true
                log.info "Widget dashboard v${state.dashboardVersion} installed → /local/${DASHBOARD_FILENAME}"
            } else {
                log.warn "Widget dashboard download failed — HTTP ${response.status} from ${url}"
            }
        }
    } catch (e) {
        log.warn "Widget dashboard download failed: ${e.message}"
    }
}

def getConfig() {
    writeConfigFile()
    render contentType: "application/json", headers: CORS_HEADERS,
           data: JsonOutput.toJson(buildConfigPayload())
}

def saveConfig() {
    try {
        def body = request?.JSON
        if (!body && request?.postBody) {
            body = new JsonSlurper().parseText(request.postBody.toString())
        }
        if (!body) {
            render status: 400, contentType: "application/json", headers: CORS_HEADERS,
                   data: JsonOutput.toJson([error: "Missing JSON body"])
            return
        }
        if (body.dashboard != null) {
            state.dashboardConfigJson = JsonOutput.toJson(body.dashboard)
        }
        writeConfigFile()
        log.info "Widget Dashboard config saved (${countWidgets()} widgets)"
        render contentType: "application/json", headers: CORS_HEADERS,
               data: JsonOutput.toJson([ok: true, widgets: countWidgets()])
    } catch (e) {
        log.warn "Widget Dashboard saveConfig failed: ${e.message}"
        render status: 500, contentType: "application/json", headers: CORS_HEADERS,
               data: JsonOutput.toJson([error: e.message])
    }
}

def getVersion() {
    render contentType: "application/json", headers: CORS_HEADERS,
           data: JsonOutput.toJson([
               app:                "Widget Dashboard",
               appVersion:         APP_VERSION,
               dashboardInstalled: state.dashboardInstalled == true,
               dashboardVersion:   state.dashboardVersion ?: "",
               configWritten:      state.configWritten == true,
               widgetCount:        countWidgets()
           ])
}

def writeConfigFile() {
    try {
        def hubIp = location.hubs[0]?.localIP
        if (!hubIp) {
            state.configWritten = false
            return
        }
        def payload = buildConfigPayload()
        uploadHubFile(CONFIG_FILENAME, JsonOutput.toJson(payload).getBytes("UTF-8"))
        state.configWritten = true
    } catch (e) {
        state.configWritten = false
        log.warn "Widget Dashboard config write failed: ${e.message}"
    }
}

private Map buildConfigPayload() {
    def hubIp = location.hubs[0]?.localIP
    return [
        hubIp:            hubIp,
        makerAppId:       settings?.makerAppId?.toString() ?: "",
        makerAccessToken: settings?.makerAccessToken?.toString() ?: "",
        appInstallId:     app.id.toString(),
        dashboard:        loadDashboardConfig(),
        dashboardVersion: state.dashboardVersion ?: APP_VERSION,
        appVersion:       APP_VERSION
    ]
}

private Map loadDashboardConfig() {
    if (!state.dashboardConfigJson) {
        return defaultDashboardConfig()
    }
    try {
        return new JsonSlurper().parseText(state.dashboardConfigJson)
    } catch (e) {
        log.warn "Widget Dashboard invalid stored config, using default: ${e.message}"
        return defaultDashboardConfig()
    }
}

private Map defaultDashboardConfig() {
    return [
        version: 1,
        widgets: []
    ]
}

private int countWidgets() {
    def cfg = loadDashboardConfig()
    def widgets = cfg?.widgets
    return (widgets instanceof List) ? widgets.size() : 0
}
