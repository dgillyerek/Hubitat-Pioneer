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
    oauthEnabled: true
)

preferences {
    page(name: "mainPage")
}

@Field static final String APP_VERSION          = "1.0.4"
@Field static final String DASHBOARD_FILENAME   = "widget-dashboard.html"
@Field static final String CONFIG_FILENAME       = "widget-dashboard-config.json"
@Field static final String DEFAULT_DASHBOARD_URL = "https://raw.githubusercontent.com/dgillyerek/Hubitat-Pioneer/main/widgetDashboard/widget-dashboard.html"

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
    ensureAccessToken()
    if (state.accessToken) {
        writeConfigFile()
    }
    dynamicPage(name: "mainPage", title: "Widget Dashboard", install: true, uninstall: true, refreshInterval: 0) {
        section("Open dashboard") {
            def hubIp = location.hubs[0]?.localIP ?: "your-hub-ip"
            paragraph "After saving, open on any device on your LAN:\n\n" +
                "<a href=\"http://${hubIp}/local/${DASHBOARD_FILENAME}\" target=\"_blank\">" +
                "http://${hubIp}/local/${DASHBOARD_FILENAME}</a>\n\n" +
                "Use <strong>Edit layout</strong> in the dashboard to add widgets and save your layout."
        }

        section("Maker API") {
            paragraph "Used by the dashboard to read devices and send commands (on/off, setLevel, etc.).<br><br>" +
                "<strong>This is not the same token</strong> as the Widget App API token below."
            input name: "makerAppId", type: "number", title: "Maker API App ID", required: true
            input name: "makerAccessToken", type: "password", title: "Maker API Access Token", required: true
        }

        section("Dashboard save key (optional)") {
            paragraph "Optional backup for <strong>Save to Hub</strong>. If OAuth save fails, set a save key here — " +
                "the dashboard will send it along with the app API token."
            input name: "dashboardSaveKey", type: "password", title: "Dashboard Save Key", required: false
        }

        section("Dashboard file") {
            input name: "dashboardUrl", type: "text", title: "Dashboard HTML URL", required: true,
                defaultValue: DEFAULT_DASHBOARD_URL
            input "updateDashboard", "button", title: "Download / update dashboard file"
        }

        section("Import layout (fallback)") {
            paragraph "If <strong>Save to Hub</strong> fails in the dashboard, export JSON from the dashboard and paste it here."
            input name: "importConfigJson", type: "text", title: "Layout JSON", required: false
            input "importLayout", "button", title: "Import layout from JSON"
        }

        section("API access") {
            paragraph "Required for <strong>Save to Hub</strong> from the dashboard browser UI.<br><br>" +
                "<strong>This is a separate token</strong> from the Maker API token above.<br><br>" +
                "<strong>One-time setup in Apps Code:</strong><br>" +
                "1. Open this app's code under <strong>Apps Code</strong><br>" +
                "2. Click <strong>OAuth</strong> (top right) and enable OAuth<br>" +
                "3. Save the app code<br>" +
                "4. Return here and click <strong>Done</strong><br><br>" +
                "The token is written to <code>/local/widget-dashboard-config.json</code> as <code>appAccessToken</code>."
            input "regenToken", "button", title: "Regenerate API access token"
        }

        section("Status") {
            def hubIp = location.hubs[0]?.localIP ?: "?"
            def widgetCount = countWidgets()
            def configUrl = getLocalConfigUrl()
            def tokenStatus = state.accessToken ? "ready (${state.accessToken.take(8)}…)" : "missing — enable OAuth in Apps Code, Save, then click Done here"
            paragraph "App: v${APP_VERSION}\n" +
                "Dashboard: ${state.dashboardInstalled ? 'installed (v' + (state.dashboardVersion ?: '?') + ')' : 'not installed — click Done or Update'}\n" +
                "Config file: ${state.configWritten ? 'written' : 'not written'}\n" +
                "Widgets: ${widgetCount}\n" +
                "Hub IP: ${hubIp}\n" +
                "App install ID: ${app.id}\n" +
                "API token: ${tokenStatus}\n" +
                "Config API: ${configUrl ?: '(waiting for API token)'}"
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "updateDashboard") {
        downloadDashboard(true)
    } else if (btn == "importLayout") {
        importLayoutFromSettings()
    } else if (btn == "regenToken") {
        regenerateAccessToken()
    }
}

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    unschedule()
    ensureAccessToken()
    downloadDashboard(false)
    writeConfigFile()
    runIn(2, "refreshConfigLoop")
    log.info "Widget Dashboard v${APP_VERSION} ready — install ID ${app.id}"
}

def ensureAccessToken() {
    if (state.accessToken) {
        return
    }
    try {
        def token = createAccessToken()
        if (token) {
            state.accessToken = token
            writeConfigFile()
        }
        if (state.accessToken) {
            log.info "Widget Dashboard API token created"
        } else {
            log.warn "Widget Dashboard: createAccessToken returned null — enable OAuth in Apps Code (OAuth button, top right), Save, then click Done on this app"
        }
    } catch (Exception e) {
        log.error "Widget Dashboard: createAccessToken failed (${e.message}). Enable OAuth in Apps Code, Save, then click Done."
    }
}

def regenerateAccessToken() {
    try {
        if (state.accessToken) {
            revokeAccessToken()
        }
    } catch (Exception ignored) {
        // first install or token already cleared
    }
    state.accessToken = null
    ensureAccessToken()
    writeConfigFile()
    log.info "Widget Dashboard API token regenerated"
}

def importLayoutFromSettings() {
    def json = settings?.importConfigJson?.trim()
    if (!json) {
        log.warn "Widget Dashboard import: paste layout JSON first"
        return
    }
    try {
        def parsed = new JsonSlurper().parseText(json)
        state.dashboardConfigJson = JsonOutput.toJson(parsed)
        writeConfigFile()
        log.info "Widget Dashboard imported layout (${countWidgets()} widgets)"
    } catch (Exception e) {
        log.warn "Widget Dashboard import failed: ${e.message}"
    }
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
    if (params?.layout || params?.dashboard) {
        saveConfig()
        return
    }
    writeConfigFile()
    render contentType: "application/json", headers: CORS_HEADERS,
           data: JsonOutput.toJson(buildConfigPayload())
}

def saveConfig() {
    try {
        if (!isSaveAuthorized()) {
            render status: 401, contentType: "application/json", headers: CORS_HEADERS,
                   data: JsonOutput.toJson([error: "Unauthorized — dashboard save key mismatch"])
            return
        }
        def body = parseSaveRequestBody()
        if (!body) {
            render status: 400, contentType: "application/json", headers: CORS_HEADERS,
                   data: JsonOutput.toJson([error: "Missing dashboard JSON body"])
            return
        }
        def layout = extractDashboardLayout(body)
        if (!layout) {
            render status: 400, contentType: "application/json", headers: CORS_HEADERS,
                   data: JsonOutput.toJson([error: "Missing dashboard layout in request body"])
            return
        }
        state.dashboardConfigJson = JsonOutput.toJson(layout)
        writeConfigFile()
        def saved = loadDashboardConfig()
        log.info "Widget Dashboard config saved (${countWidgets()} widgets)"
        render contentType: "application/json", headers: CORS_HEADERS,
               data: JsonOutput.toJson([ok: true, widgets: countWidgets(), dashboard: saved])
    } catch (e) {
        log.warn "Widget Dashboard saveConfig failed: ${e.message}"
        render status: 500, contentType: "application/json", headers: CORS_HEADERS,
               data: JsonOutput.toJson([error: e.message])
    }
}

private Boolean isSaveAuthorized() {
    def expected = settings?.dashboardSaveKey?.toString()
    if (!expected) {
        return true
    }
    return params?.saveKey?.toString() == expected
}

private Map extractDashboardLayout(Map body) {
    if (body?.dashboard instanceof Map) {
        return body.dashboard
    }
    if (body?.widgets instanceof List) {
        return body
    }
    return null
}

private Map parseSaveRequestBody() {
    if (request?.JSON instanceof Map) {
        return request.JSON
    }
    if (params?.dashboard) {
        return new JsonSlurper().parseText(params.dashboard.toString())
    }
    if (params?.layout) {
        def decoded = new String(params.layout.toString().decodeBase64())
        return new JsonSlurper().parseText(decoded)
    }
    if (request?.postBody) {
        return new JsonSlurper().parseText(request.postBody.toString())
    }
    if (request?.body) {
        return new JsonSlurper().parseText(request.body.toString())
    }
    return null
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
        appAccessToken:   state.accessToken?.toString() ?: "",
        hasSaveKey:       settings?.dashboardSaveKey ? true : false,
        saveKey:          settings?.dashboardSaveKey?.toString() ?: "",
        dashboard:        loadDashboardConfig(),
        dashboardVersion: state.dashboardVersion ?: APP_VERSION,
        appVersion:       APP_VERSION
    ]
}

private String getLocalConfigUrl() {
    if (!state.accessToken) {
        return null
    }
    return "${getFullLocalApiServerUrl()}/config?access_token=${state.accessToken}"
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
