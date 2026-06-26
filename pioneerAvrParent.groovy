 /*

    Most of this code was copied from: 
    "Copyright © 2020 Steve Vibert (@SteveV)
    https://github.com/stevevib/Hubitat"

    I modified it to work for the telenet commands of multizone pioneer

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    Version History:
    ================

    Date            Version             By                  Changes
    --------------------------------------------------------------------------------
    2021-02-08      0.5.0               Derek Gilbert       Initial Version
    2026-05-31      0.6.0               Derek Gilbert       Zone routing, reconnect, telnet fixes
    2026-05-31      0.6.1               Derek Gilbert       Staggered refresh, refresh on connect
    2026-06-21      0.7.0               Derek Gilbert       Input switching, zone power commands
    2026-06-21      0.7.1               Derek Gilbert       HD Zone (zone 4) input support
    2026-06-21      0.7.2               Derek Gilbert       Zone select before input, ZEC/ZEB
    2026-06-21      0.7.4               Derek Gilbert       Telnet line parsing, status query fixes
    2026-06-21      0.7.5               Derek Gilbert       Query ?RGBxx input names from AVR
    2026-06-21      0.7.6               Derek Gilbert       Input next queue/hold, no ZEO on cycle
    2026-06-21      0.7.7               Derek Gilbert       Input name display sync after RGB load
    2026-06-21      0.7.8               Derek Gilbert       Match RGB response code to query index
    2026-06-21      0.7.9               Derek Gilbert       Fast single-attribute status query after commands
    2026-06-21      0.7.12              Derek Gilbert       Fix main zone dB formula (was 2dB per step)
  
*/

import groovy.transform.Field

metadata 
{
	definition (name: "Pioneer Multi-Zone AVR Parent", namespace: "PioneerDGs", author: "Derek Gilbert")
	{
		capability "Initialize"
		capability "Telnet"

        command "refresh"
        command "refreshInputNames"
        command "turnOnZone", [[name:"Zone Number*", type: "NUMBER", description: "1=Main, 2=Zone 2, 3=Zone 3, 4=Zone 4"]]
        command "turnOffZone", [[name:"Zone Number*", type: "NUMBER", description: "1=Main, 2=Zone 2, 3=Zone 3, 4=Zone 4"]]
        command "setZoneInput", [[name:"Zone Number*", type: "NUMBER"], [name:"Source Code or Name*", type: "STRING"]]
	}

    preferences 
	{   
		input name: "PioneerIP", type: "text", title: "Pioneer IP", required: true, displayDuringSetup: true
		input name: "eISCPPort", type: "number", title: "EISCP Port", defaultValue: 60128, required: true, displayDuringSetup: true
		input name: "eISCPTermination", type: "enum", options: [[1:"CR"],[2:"LF"],[3:"CRLF"],[4:"EOF"]] ,title: "EISCP Termination Option", required: true, displayDuringSetup: true, description: "Most receivers should work with CR termination"
		input name: "eISCPVolumeRange", type: "enum", options: [[50:"0-50 (0x00-0x32)"],[80:"0-80 (0x00-0x50)"],[100:"0-100 (0x00-0x64)"],[200:"0-100 Half Step (0x00-0xC8)"]],defaultValue:80, title: "Supported Volume Range", required: true, displayDuringSetup: true, description:"(see Pioneer EISCP Protocol doc for model specific values)"
        input name: "enabledReceiverZones", type: "enum", title: "Enabled Zones", required: true, multiple: true, options: [[1: "Main"],[2:"Zone 2"],[3:"Zone 3"],[4: "Zone 4"]]
        input name: "inputSourceMap", type: "text", title: "Input source map (code:name, comma-separated)", required: false, defaultValue: "00:Phono,01:CD,02:Tuner,03:CD-R/Tape,04:DVD,05:TV/SAT,10:Video 1,14:Video 2,15:DVR/BDR,17:iPod/USB,19:HDMI 1,20:HDMI 2,21:HDMI 3,22:HDMI 4,25:BD,26:HMG,33:Adapter,38:Net Radio", description: "Fallback names when device query is disabled or missing a code. Device names override these when 'Load input names from AVR' is on."
        input name: "loadInputNamesFromDevice", type: "bool", title: "Load input names from AVR", required: true, defaultValue: true, description: "Queries ?RGB00-?RGB59 on connect for names configured on the receiver (HDMI labels, etc.)"
        input name: "maxInputNameId", type: "number", title: "Max input ID to query", required: false, defaultValue: 60, description: "Upper bound for ?RGBxx queries (codes 00-59)"
        input name: "zoneSelectCommands", type: "text", title: "Zone select commands before input", required: false, defaultValue: "4:ZEO", description: "Telnet command sent before input changes on a zone. Format: zoneNum:command pairs, comma-separated. Example: 4:ZEO selects/focuses HD Zone before input. Adjust for your model if input changes the wrong zone."
        input name: "zoneSelectDelay", type: "number", title: "Delay after zone select (seconds)", required: false, defaultValue: 1, description: "Wait time between zone-select and input command"
        input name: "inputCycleCodes", type: "text", title: "Input cycle list (optional)", required: false, description: "Comma-separated input codes to cycle for Input Next/Previous (e.g. 06,22,19,25). Leave blank to use native FU/ZEC. Recommended if native cycle skips audio or jumps to unused inputs."
 		input name: "textLogging",  type: "bool", title: "Enable description text logging ", required: true, defaultValue: true
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

@Field static List zoneNames = ["N/A", "Main", "Zone 2", "Zone 3", "Zone 4" ]
@Field static List InputCommand = ["FN", "ZS", "ZT", "ZEA"]
@Field static List InputUpCommand = ["FU", "ZSFU", "ZTFU", "ZEC"]
@Field static List InputDownCommand = ["FD", "ZSFD", "ZTFD", "ZEB"]

def getVersion()
{
    return "0.7.12"
}

List getInputCycleCodes() {
    def pref = settings?.inputCycleCodes?.trim()
    if (!pref) {
        return null
    }
    return pref.split(",").collect { it.trim().padLeft(2, "0") }.findAll { it }
}

String getZoneSelectCommand(Integer zone) {
    def pref = settings?.zoneSelectCommands ?: ""
    if (!pref?.trim()) {
        return null
    }
    def match = null
    pref.split(",").each { entry ->
        def parts = entry.trim().split(":", 2)
        if (parts.size() >= 2 && (parts[0].trim() as Integer) == zone) {
            match = parts[1].trim()
        }
    }
    return match ?: null
}

Integer getZoneSelectDelaySec() {
    def delay = settings?.zoneSelectDelay as Integer ?: 1
    return Math.max(delay, 1)
}

Map getInputSourceMap() {
    def map = [:]
    map.putAll(getManualInputSourceMap())
    if (settings?.loadInputNamesFromDevice != false && state.deviceInputMap) {
        map.putAll(state.deviceInputMap)
    }
    return map
}

private Map getManualInputSourceMap() {
    def map = [:]
    def src = settings?.inputSourceMap ?: ""
    if (!src?.trim()) {
        return map
    }
    src.split(",").each { entry ->
        def parts = entry.trim().split(":", 2)
        if (parts.size() >= 2) {
            map[parts[0].trim().padLeft(2, "0")] = parts[1].trim()
        }
    }
    return map
}

String getInputName(String code) {
    if (!code) {
        return "Unknown"
    }
    def normalized = code.toString().trim().padLeft(2, "0")
    if (settings?.loadInputNamesFromDevice != false && state.deviceInputMap?.get(normalized)) {
        return state.deviceInputMap[normalized]
    }
    def manual = getManualInputSourceMap()
    if (manual[normalized]) {
        return manual[normalized]
    }
    return "Input ${normalized}"
}

String resolveInputCode(String source) {
    if (!source) {
        return null
    }
    def trimmed = source.trim()
    if (trimmed ==~ /[0-9A-Fa-f]{1,2}/) {
        return trimmed.padLeft(2, "0").toUpperCase()
    }
    def map = getInputSourceMap()
    def match = map.find { k, v -> v.equalsIgnoreCase(trimmed) }
    return match ? match.key : null
}

void parse(String resp) 
{
    if (!resp) {
        return
    }
    writeLogDebug("parse raw: ${resp}")

    state.telnetBuffer = (state.telnetBuffer ?: "") + resp
    def buffer = state.telnetBuffer
    def lines = buffer.split(/[\r\n]+/) as List

    if (lines && !buffer.endsWith("\r") && !buffer.endsWith("\n")) {
        state.telnetBuffer = lines.remove(lines.size() - 1)
    } else {
        state.telnetBuffer = ""
    }

    lines.each { line ->
        line = line?.trim()
        if (!line || line.length() < 2 || line == "R" || line.startsWith("E")) {
            return
        }
        writeLogDebug("parse line: ${line}")
        handlePower(line)
        handleMute(line)
        handleVolume(line)
        handleInput(line)
        handleRgb(line)
    }
}

@Field static List PowerCommand = ["P", "AP", "BP", "ZE" ]
@Field static List PowerQueryCommand = ["P", "AP", "BP", "ZEP" ]
@Field static List VolumeCommand = ["V", "Z", "Y" ]
@Field static List Mute = ["M", "Z2M", "Z3M"]
//ZONE1
//VOL***
//  (1step = 0.5dB)
//  185:+12.0dB
//  184:+11.5dB
//  161:0.0dB
//  001:-80.0dB
//  000:---.-dB  (MIN)

//ZONE2 & ZONE3
//ZV**, YV**
// 00 to 81 by ASCII code.
// ( 1step=1dB)
//  81:0.0dB
//  01:-80.0dB
//  00:---dB(MIN)
String TranslateVolumeLevel(Float step, Integer val, Integer zeroVal){

    if (val == 000 || val == 00){
        return "MIN-db"
    }

    def db = (val - zeroVal) * step
    return String.format("%.1f", db) + "dB"
}

String getCommand(Integer zone, String command){

    writeLogDebug("parent getCommand ${zone} ${command}")
    String[] a = command.split("\\.")
    def zoneIndex = zone - 1

    if (zone == 4 && (a[0] == "mute" || a[0] == "volume")) {
        writeLogDebug("Zone 4 does not support ${a[0]}")
        return null
    }

    def sb = StringBuilder.newInstance()

    if (a[1]=="query")
        {
            sb.append("?")
        }

    if (a[0]=="power"){
        sb.append(a[1] == "query" ? PowerQueryCommand[zoneIndex] : PowerCommand[zoneIndex])
    }else if(a[0] == "mute" ){
        sb.append(Mute[zoneIndex])
    }else if (a[0] == "volume" ){
        sb.append(VolumeCommand[zoneIndex])
        if (a[1] == "query" && (zone == 2 || zone ==3 )){
            sb.append("V")
        }
    }else if (a[0] == "input") {
        if (a[1] == "query") {
            if (zone == 1) {
                if (a.size() > 2 && a[2] == "alt") {
                    return "?SLI"
                }
                return "?F"
            }
            return "?${InputCommand[zoneIndex]}"
        }
        if (a[1] == "set" && a.size() > 2) {
            return "${a[2].padLeft(2, "0")}${InputCommand[zoneIndex]}"
        }
        if (a[1] == "up") {
            return InputUpCommand[zoneIndex]
        }
        if (a[1] == "down") {
            return InputDownCommand[zoneIndex]
        }
        return null
    }

    if (a[1]== "on"){
            sb.append("O")
    }else if (a[1]=="off"){
            sb.append("F")
    }else if (a[1] == "up"){
        sb.append("U")
    }else if (a[1] == "down"){
        sb.append("D")
    }

    return sb.toString()
}

def sendChildZone(Integer zone, String command){
    def z = getZone(zone)
    if (!z) {
        writeLogDebug("sendChildZone: no child for zone ${zone}")
        return
    }
    writeLogDebug("sendChildZone zone ${zone}: ${command}")
    z.fromA(command)
}

def sendChildZone(Integer zone, String command, String value ){
    def z = getZone(zone)
    if (!z) {
        writeLogDebug("sendChildZone: no child for zone ${zone}")
        return
    }
    writeLogDebug("sendChildZone zone ${zone}: ${command} ${value}")
    z.fromA(command, value)
}

def getZone(Integer zone){
    writeLogDebug("getZone ${zone}")
    if (zone == null || zone < 1 || zone >= zoneNames.size()) {
        writeLogDebug("getZone: invalid zone ${zone}")
        return null
    }
    return getChild(zoneNames[zone])
}

@Field static List PowerResponse = ["PWR", "APR", "BPR", "ZEP" ]

private boolean parsePowerOn(String param, Integer zone) {
    if (!param) {
        return false
    }
    param = param.trim()
    // Main zone telnet: PWR0=on. Sub-zones (APR/BPR/ZEP): 1=on, 0=off on many models.
    if (param.length() <= 2 && param ==~ /[0-2]/) {
        if (zone == 1) {
            return param == "0"
        }
        return param == "1"
    }
    def normalized = param.replaceFirst(/^0+/, "") ?: "0"
    return normalized != "0"
}

def handlePower(String line){
    writeLogDebug("handlePower ${line}")
    PowerResponse.eachWithIndex { code, idx ->
        if (line.startsWith(code) && line.length() > code.length()) {
            def zone = idx + 1
            if (state.suppressSecondaryZoneStatus && zone >= 2) {
                writeLogDebug("handlePower suppressed zone ${zone} during input command")
            } else {
                def param = line.substring(code.length())
                def isOn = parsePowerOn(param, zone)
                sendChildZone(zone, "power." + (isOn ? "on" : "off"))
            }
        }
    }
}

private String muteStateFromDigit(String digit) {
    // MUT0 = muted, MUT1 = unmuted (Pioneer telnet / ISCP)
    return digit == "1" ? "mute.off" : "mute.on"
}

def handleMute(String line){
    writeLogDebug("handleMute ${line}")
    if (line.startsWith("Z3MUT") && line.length() >= 6) {
        sendChildZone(3, muteStateFromDigit(line.substring(5, 6)))
        return
    }
    if (line.startsWith("Z2MUT") && line.length() >= 6) {
        sendChildZone(2, muteStateFromDigit(line.substring(5, 6)))
        return
    }
    def muteIdx = line.indexOf("MUT")
    if (muteIdx >= 0 && line.length() >= muteIdx + 4) {
        sendChildZone(1, muteStateFromDigit(line.substring(muteIdx + 3, muteIdx + 4)))
    }
}

@Field static List VolumeResponse = ["VOL", "ZV", "YV"]
private String parseInputCode(String line, int prefixLen) {
    if (line.length() <= prefixLen) {
        return null
    }
    def raw = line.substring(prefixLen).trim()
    if (raw.length() >= 2) {
        return raw[-2..-1]
    }
    return raw.padLeft(2, "0")
}

def handleInput(String line) {
    writeLogDebug("handleInput ${line}")

    if (line.startsWith("SLI")) {
        def code = parseInputCode(line, 3)
        if (code) sendChildZone(1, "input", code)
        return
    }

    if (line.startsWith("FN")) {
        def code = parseInputCode(line, 2)
        if (code) sendChildZone(1, "input", code)
        return
    }

    if (line.startsWith("Z2F") && line.length() >= 5) {
        sendChildZone(2, "input", line.substring(3, 5))
        return
    }

    if (line.startsWith("Z3F") && line.length() >= 5) {
        sendChildZone(3, "input", line.substring(3, 5))
        return
    }

    if (line.startsWith("ZEA") && line.length() >= 5) {
        sendChildZone(4, "input", line.substring(3, 5))
        return
    }

    if (line.startsWith("ZS") && line.length() >= 4) {
        def code = parseInputCode(line, 2)
        if (code) sendChildZone(2, "input", code)
        return
    }

    if (line.startsWith("ZT") && line.length() >= 4) {
        def code = parseInputCode(line, 2)
        if (code) sendChildZone(3, "input", code)
    }
}

def handleRgb(String line) {
    if (!line.startsWith("RGB") || line.length() <= 6) {
        return
    }
    def responseCode = line.substring(3, 5)
    def name = line.substring(6).trim()
    if (!name || name ==~ /^\d+$/) {
        return
    }
    def code = null
    if (state.pendingRgbIndex != null) {
        def expected = state.pendingRgbIndex.toString().padLeft(2, "0")
        if (responseCode != expected) {
            writeLogDebug("handleRgb skip ${line} (waiting for RGB${expected}..., got RGB${responseCode}...)")
            return
        }
        code = expected
        state.pendingRgbIndex = null
        unschedule("clearPendingRgbIndex")
    } else {
        code = responseCode
    }
    if (!state.deviceInputMap) {
        state.deviceInputMap = [:]
    }
    state.deviceInputMap[code] = name
    writeLogDebug("input name ${code} = ${name}")
    syncChildrenInputDisplay()
}

def clearPendingRgbIndex() {
    if (state.pendingRgbIndex != null) {
        writeLogDebug("RGB query timed out for index ${state.pendingRgbIndex}")
        state.pendingRgbIndex = null
    }
}

def handleVolume(String resp){
    writeLogDebug("handleVolume ${resp}")
    if (resp.indexOf("VOL") == 0 && resp.length() >= 6){
        try {
            sendChildZone(1, "volume", TranslateVolumeLevel(0.5f, resp.substring(3, 6).toInteger(), 161))
        } catch (e) {
            writeLogDebug("handleVolume main zone parse error: ${e.message}")
        }
    }

    if (resp.length() >= 4) {
        def val = resp.substring(0, 2)
        if (val == "ZV" || val == "YV"){
            try {
                sendChildZone(val == "ZV" ? 2 : 3, "volume", TranslateVolumeLevel(1f, resp.substring(2, 4).toInteger(), 81))
            } catch (e) {
                writeLogDebug("handleVolume zone 2/3 parse error: ${e.message}")
            }
        }
    }
}

def initialize()
{
	String ip = settings?.PioneerIP as String
	Integer port = settings?.eISCPPort as Integer

	if (!ip || !port) {
        log.warn "${device.getName()} Pioneer IP and port must be configured"
        return
    }

	writeLogDebug("ip: ${ip} port: ${port}")

    try {
        telnetClose()
        def termChars = getTelnetTermChars()
        if (termChars) {
            telnetConnect([termChars: termChars], ip, port, null, null)
        } else {
            telnetConnect(ip, port, null, null)
        }
        writeLogDebug("Opening telnet connection with ${ip}:${port}")
    } catch (e) {
        logConnectionFailure(e.message)
        state.connectionState = "disconnected"
        scheduleReconnect()
        return
    }

    try 
    {
        childDevices.each { it ->
            it.initialize()
        }
    } 

    catch(e) 
    {
        log.error "initialize caused the following exception: ${e}"
    }
}

void telnetStatus(String message) 
{
	writeLogDebug("${device.getName()} telnetStatus ${message}")

    if (message.startsWith("status: open")) {
        state.connectionState = "connected"
        state.reconnectPending = false
        state.reconnectDelay = 2
        runIn(2, "refresh")
        runIn(5, "loadInputNamesFromDevice")
    } else if (message.startsWith("failure:") || message.contains("closed")) {
        state.connectionState = "disconnected"
        logConnectionFailure(message)
        scheduleReconnect()
    }
}

private void logConnectionFailure(String detail) {
    def ip = settings?.PioneerIP
    def port = settings?.eISCPPort
    if (detail?.contains("Connection refused")) {
        log.warn "${device.getName()} connection refused at ${ip}:${port} — receiver may be off, network standby disabled, or wrong port (try 60128, 23, or 8102)"
    } else if (detail?.contains("No route to host") || detail?.contains("Host unreachable")) {
        log.warn "${device.getName()} cannot reach ${ip} — verify IP in Google Home device list or Pioneer network menu; Hubitat and receiver must be on the same LAN"
    } else {
        log.warn "${device.getName()} telnet connection failed to ${ip}:${port} — ${detail}"
    }
}

private void scheduleReconnect() {
    if (state.reconnectPending) return
    state.reconnectPending = true
    def delay = state.reconnectDelay ?: 2
    state.reconnectDelay = Math.min(delay * 2, 300)
    writeLogInfo("Scheduling telnet reconnect in ${delay}s")
    runIn(delay, "reconnectTelnet")
}

def reconnectTelnet() {
    state.reconnectPending = false
    telnetClose()
    runIn(1, "initialize")
}

def installed()
{
    log.warn "${device.getName()} installed..."
	//initialize()
    updated()
}

def updated()
{
	writeLogInfo("updated...")
    state.version = getVersion()
    unschedule()

	// disable debug logs after 30 min
	if (debugOutput) 
		runIn(1800,logsOff)

    updateChildren()

    if (settings?.loadInputNamesFromDevice != false) {
        runIn(8, "loadInputNamesFromDevice")
    }
    initialize()
    runIn(1800, "healthCheck")
}

def turnOnZone(zoneNumber) {
    def zone = zoneNumber as Integer
    def child = getZone(zone)
    if (!child) {
        log.warn "${device.getName()} turnOnZone: no child for zone ${zone}"
        return
    }
    child.on()
}

def turnOffZone(zoneNumber) {
    def zone = zoneNumber as Integer
    def child = getZone(zone)
    if (!child) {
        log.warn "${device.getName()} turnOffZone: no child for zone ${zone}"
        return
    }
    child.off()
}

def setZoneInput(zoneNumber, source) {
    def zone = zoneNumber as Integer
    def child = getZone(zone)
    if (!child) {
        log.warn "${device.getName()} setZoneInput: no child for zone ${zone}"
        return
    }
    child.setInputSource(source as String)
}

void refresh()
{
    writeLogInfo("refresh all enabled zones")
    def queries = []
    enabledReceiverZones?.each { zoneNum ->
        queries.addAll(buildZoneRefreshQueries(zoneNum as Integer))
    }
    startRefreshQueries(queries)
}

def requestZoneRefresh(Integer zone) {
    if (!zone) {
        return
    }
    def queries = buildZoneRefreshQueries(zone)
    if (state.refreshInProgress) {
        state.pendingRefreshQueries = (state.pendingRefreshQueries ?: []) + queries
        writeLogDebug("queued refresh for zone ${zone} (${queries.size()} queries)")
        return
    }
    startRefreshQueries(queries)
}

def requestFastStatusQuery(Integer zone, String attributeGroup) {
    if (!zone || !attributeGroup) {
        return
    }
    def cmd = getCommand(zone, "${attributeGroup}.query")
    if (!cmd) {
        return
    }
    def delaySec = attributeGroup == "power" ? 0.6 : 0.35
    runIn(delaySec, "sendFastStatusQuery", [data: [zone: zone, attr: attributeGroup, cmd: cmd]])
}

def sendFastStatusQuery(data) {
    if (state.refreshHoldCount > 0) {
        runIn(1, "sendFastStatusQuery", [data: data])
        return
    }
    writeLogDebug("fast status query zone ${data.zone} ${data.attr}: ${data.cmd}")
    sendTelnetMsg(data.cmd as String)
}

private List buildZoneRefreshQueries(Integer zone) {
    def queries = []
    queries << [zone: zone, cmd: "power.query"]
    if (zone != 4) {
        queries << [zone: zone, cmd: "volume.query"]
        queries << [zone: zone, cmd: "mute.query"]
    }
    queries << [zone: zone, cmd: "input.query"]
    if (zone == 1) {
        queries << [zone: zone, cmd: "input.query.alt"]
    }
    return queries
}

private void startRefreshQueries(List queries, String queueType = "zone") {
    unschedule("refreshNextQuery")
    if (!queries) {
        state.refreshInProgress = false
        state.refreshQueueType = null
        return
    }
    state.refreshInProgress = true
    state.refreshQueueType = queueType
    state.refreshQueries = queries
    state.refreshQueryIndex = 0
    refreshNextQuery()
}

def refreshNextQuery() {
    if (state.refreshHoldCount > 0) {
        runIn(1, "refreshNextQuery")
        return
    }
    def queries = state.refreshQueries ?: []
    def index = state.refreshQueryIndex as Integer ?: 0
    if (index >= queries.size()) {
        if (state.refreshQueueType == "inputNames") {
            finishInputNameLoad()
        }
        state.refreshInProgress = false
        state.refreshQueueType = null
        state.refreshQueryIndex = 0
        def pending = state.pendingRefreshQueries
        state.pendingRefreshQueries = null
        if (pending) {
            startRefreshQueries(pending)
        } else if (state.pendingInputNameLoad) {
            state.pendingInputNameLoad = false
            loadInputNamesFromDevice()
        }
        return
    }
    def item = queries[index]
    def telnetCmd = null
    if (item.type == "inputName") {
        state.pendingRgbIndex = item.index as Integer
        unschedule("clearPendingRgbIndex")
        runIn(3, "clearPendingRgbIndex")
        telnetCmd = "?RGB${state.pendingRgbIndex.toString().padLeft(2, '0')}"
    } else {
        telnetCmd = getCommand(item.zone as Integer, item.cmd as String)
    }
    if (telnetCmd) {
        writeLogDebug("refresh query ${item.type ?: item.cmd} zone ${item.zone}: ${telnetCmd}")
        sendTelnetMsg(telnetCmd)
    }
    state.refreshQueryIndex = index + 1
    if (state.refreshQueryIndex < queries.size()) {
        def delay = state.refreshQueueType == "inputNames" ? 1 : 1
        runIn(delay, "refreshNextQuery")
    } else {
        runIn(1, "refreshNextQuery")
    }
}

def loadInputNamesFromDevice() {
    if (settings?.loadInputNamesFromDevice == false) {
        writeLogDebug("loadInputNamesFromDevice disabled in preferences")
        return
    }
    def maxId = settings?.maxInputNameId as Integer ?: 60
    maxId = Math.min(Math.max(maxId, 1), 99)
    def queries = []
    def priorityIndices = [] as Set
    enabledReceiverZones?.each { zoneNum ->
        def child = getZone(zoneNum as Integer)
        def code = child?.currentValue("input")?.toString()?.trim()
        if (code && code ==~ /[0-9A-Fa-f]{1,2}/) {
            priorityIndices << Integer.parseInt(code.padLeft(2, "0"))
        }
    }
    priorityIndices.sort().each { i ->
        queries << [type: "inputName", index: i]
    }
    (0..<maxId).each { i ->
        if (!priorityIndices.contains(i)) {
            queries << [type: "inputName", index: i]
        }
    }
    if (state.refreshInProgress) {
        state.pendingInputNameLoad = true
        writeLogDebug("input name load queued until refresh completes")
        return
    }
    writeLogInfo("Querying AVR for input names (?RGB00-?RGB${(maxId - 1).toString().padLeft(2, '0')})")
    startRefreshQueries(queries, "inputNames")
}

def refreshInputNames() {
    state.remove("deviceInputMapLoaded")
    state.deviceInputMap = [:]
    loadInputNamesFromDevice()
}

private void finishInputNameLoad() {
    def count = state.deviceInputMap?.size() ?: 0
    state.deviceInputMapLoaded = true
    writeLogInfo("Loaded ${count} input name(s) from AVR")
    syncChildrenInputDisplay()
    childDevices.each { child ->
        try {
            child.publishSupportedInputs()
        } catch (e) {
            writeLogDebug("finishInputNameLoad publish: ${e.message}")
        }
    }
}

private void syncChildrenInputDisplay() {
    childDevices.each { child ->
        try {
            child.updateInputDisplay()
        } catch (e) {
            writeLogDebug("syncChildrenInputDisplay: ${e.message}")
        }
    }
}

def healthCheck() {
    writeLogDebug("healthCheck")
    if (state.connectionState == "disconnected") {
        reconnectTelnet()
    } else {
        refresh()
    }
    runIn(1800, "healthCheck")
}

def logsOff() 
{
    log.warn "${device.getName()} debug logging disabled..."
    device.updateSetting("debugOutput",[value:"false",type:"bool"])	
}

private writeLogDebug(msg) 
{
    if (settings?.debugOutput || settings?.debugOutput == null)
        log.debug "$msg"
}

private writeLogInfo(msg)
{
    if (settings?.textLogging || settings?.textLogging == null)
        log.info "$msg"
}

def updateChildren()
{
    writeLogDebug("updateChildren...")

    try 
    {
        writeLogDebug("enabledReceiverZones: ${enabledReceiverZones}")

        enabledReceiverZones.each { it ->
        
            writeLogDebug("Checking if zone ${it} child exists...")
            Integer childZone = it as Integer
            String childName = zoneNames[childZone]
  
            // Get child device...
            def child = getChild(childName)

            // ...or create it if it doesn't exist
            if(child == null) 
            {
                if (debugOutput) 
                    writeLogDebug("Child with id ${childName} does not exist.  Creating...")
                
                def childType = "Pioneer Multi-Zone AVR Child Zone"
                createChildDevice(childZone, childName, childType)
                child = getChild(childName)

                if(child != null)
                {
                    //writeLogDebug("Sending hello message to child...")
                    //child.fromParent ("Hello ${childName}")
                    writeLogDebug("Child with id ${childName} successfully created")
                }

                else
                {
                    writeLogDebug("Unable to create child with id ${childName}")
                }
            }

            else
                writeLogDebug("Found child with id ${childName}.")

        }
    }

    catch(e) 
    {
        log.error "Failed to find child without exception: ${e}"
    }    
}

private def getChild(String zoneName)
{
    writeLogDebug("getChild with ${zoneName}")
    def child = null
    
    try 
    {
        childDevices.each { it ->
            
            //writeLogDebug("child: ${it.deviceNetworkId}")
            if(it.deviceNetworkId == "${device.deviceNetworkId}-${zoneName}")
            {
                child = it
            }
        }
        
        return child
    } 

    catch(e) 
    {
        log.error "getChild caused the following exception: ${e}"
        return null
    }
}

private void createChildDevice(Integer zone, String zoneName, String type) 
{
    writeLogInfo ("Attempting to create child with zoneName ${zoneName} of type ${type}")
    
    try 
    {
        def child = addChildDevice("${type}", "${device.deviceNetworkId}-${zoneName}",
            [label: "Pioneer AVR ${zoneName}",  isComponent: false, name: "${zoneName}"])
        
        writeLogInfo ("Child device with network id: ${device.deviceNetworkId}-${zoneName} successfully created.")
        // Assign the zone number to the child.  The child will use the to filter responses from the AVR
        child.setZone(zone)
    } 

    catch(e) 
    {
        log.error "createChildDevice caused the following exception: ${e}"
    }
}

def sendTelnetMsg(String msg) 
{
    writeLogDebug("sendTelnetMsg ${msg}")
    sendHubCommand(new hubitat.device.HubAction(finalizeTelnetMessage(msg), hubitat.device.Protocol.TELNET))
}

private void holdRefreshForCommand() {
    state.refreshHoldCount = (state.refreshHoldCount ?: 0) + 1
    unschedule("refreshNextQuery")
}

private void releaseRefreshHold() {
    def count = (state.refreshHoldCount ?: 0) - 1
    state.refreshHoldCount = Math.max(count, 0)
    if (state.refreshHoldCount == 0 && state.refreshInProgress) {
        runIn(1, "refreshNextQuery")
    }
}

def clearStatusSuppress() {
    state.suppressSecondaryZoneStatus = false
}

def cycleZoneInput(Integer zone, Integer direction) {
    def codes = getInputCycleCodes()
    if (!codes) {
        def cmd = getCommand(zone, direction > 0 ? "input.up" : "input.down")
        sendZoneCommand(zone, cmd, false)
        return
    }
    def child = getZone(zone)
    if (!child) {
        return
    }
    def current = child.currentValue("input")?.toString()?.padLeft(2, "0")
    def idx = current ? codes.indexOf(current) : -1
    if (idx < 0) {
        idx = 0
    } else {
        idx = (idx + direction) % codes.size()
        if (idx < 0) {
            idx += codes.size()
        }
    }
    def nextCode = codes[idx]
    def setCmd = getCommand(zone, "input.set.${nextCode}")
    if (setCmd) {
        writeLogInfo("${device.getName()} cycle zone ${zone} input -> ${nextCode}")
        sendZoneCommand(zone, setCmd, false)
    }
}

def sendZoneCommand(Integer zone, String msg, Boolean withZoneSelect = false) {
    if (!msg) {
        return
    }
    holdRefreshForCommand()
    state.suppressSecondaryZoneStatus = true
    unschedule("clearStatusSuppress")
    runIn(4, "clearStatusSuppress")

    def selectCmd = withZoneSelect ? getZoneSelectCommand(zone) : null
    if (selectCmd) {
        writeLogInfo("${device.getName()} zone ${zone} select: ${selectCmd}, then: ${msg}")
        state.pendingZoneCommand = msg
        sendTelnetMsg(selectCmd)
        runIn(getZoneSelectDelaySec(), "sendPendingZoneCommand")
        runIn(getZoneSelectDelaySec() + 2, "releaseRefreshHold")
    } else {
        writeLogDebug("${device.getName()} zone ${zone} command: ${msg}")
        sendTelnetMsg(msg)
        runIn(2, "releaseRefreshHold")
    }
}

def sendPendingZoneCommand() {
    def msg = state.pendingZoneCommand
    state.pendingZoneCommand = null
    if (msg) {
        sendTelnetMsg(msg)
    }
}
 
private List getTelnetTermChars() {
    switch (settings?.eISCPTermination as String) {
        case "2": return [10]
        case "3": return [13, 10]
        case "4": return null
        default: return [13]
    }
}

def finalizeTelnetMessage(command)
{
    if (getTelnetTermChars()) {
        return command
    }

    if ((settings?.eISCPTermination as String) == "4") {
        return command
    }

    def sb = StringBuilder.newInstance()
    sb.append(command)
    switch (settings?.eISCPTermination as String) {
        case "2":
            sb.append((char) 10)
            break
        case "3":
            sb.append((char) 13)
            sb.append((char) 10)
            break
        default:
            sb.append((char) 13)
            break
    }
    return sb.toString()
}

def getName()
{
    return device.getName()
}