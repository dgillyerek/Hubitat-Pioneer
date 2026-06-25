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
    2026-05-31      0.6.0               Derek Gilbert       muteToggle, log fixes
    2026-05-31      0.6.1               Derek Gilbert       Staggered refresh queries
    2026-06-21      0.7.0               Derek Gilbert       Input switching, power-on-before-input
    2026-06-21      0.7.1               Derek Gilbert       HD Zone input cycling
    2026-06-21      0.7.2               Derek Gilbert       Zone select before input, native ZEC/ZEB
    2026-06-21      0.7.3               Derek Gilbert       PushableButton for Hubitat dashboard tiles
    2026-06-21      0.7.4               Derek Gilbert       Refresh via parent query queue
    2026-06-21      0.7.6               Derek Gilbert       Input next power-on, no ZEO on cycle
    2026-06-21      0.7.7               Derek Gilbert       updateInputDisplay for custom names
    2026-06-21      0.7.8               Derek Gilbert       RGB response code validation
    2026-06-21      0.7.9               Derek Gilbert       Optimistic mute, fast status query after commands
  
*/

import groovy.transform.Field

metadata 
{
	definition (name: "Pioneer Multi-Zone AVR Child Zone", namespace: "PioneerDG", author: "Derek Gilbert")
	{
		capability "Initialize"
		capability "Switch"
		capability "AudioVolume"
		capability "Actuator"
		capability "MediaInputSource"
		capability "PushableButton"

		attribute "input", "string"
		attribute "mute", "string"
		attribute "volume", "string"
		attribute "numberOfButtons", "number"

        command "muteToggle"
        command "setInputSourceRaw", [[name:"Source Code*", type: "STRING", description: "Two-digit Pioneer input code (e.g. 05, 19, 25)"]]
        command "inputNext"
        command "inputPrevious"
        command "push", [[name: "buttonNumber", type: "NUMBER", description: "1=Input+, 2=Input-, 3=Vol+, 4=Vol-, 5=Mute"]]
	}

	preferences 
	{   
		input name: "textLogging",  type: "bool", title: "Enable description text logging ", required: true, defaultValue: true
        input name: "powerOnBeforeInput", type: "bool", title: "Power on zone before changing input", required: true, defaultValue: true
        input name: "selectZoneBeforeInput", type: "bool", title: "Send zone-select command before direct input set", required: true, defaultValue: true, description: "Uses parent 'Zone select commands before input' for setInputSource only. Input Next/Previous does not send zone-select (ZEO can disrupt audio)."
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def getVersion()
{
    return "0.7.9"
}

void parse(String description) 
{
    writeLogDebug("${state.zone.Zone} received ${description}")
}

def initialize()
{
    log.warn "${getFullDeviceName()} initialize..."
    refresh()
}

def installed()
{
    log.warn "${getFullDeviceName()} installed..."
    publishButtonCount()
    publishSupportedInputs()
    updated()
}

/*
def reset()
{
    log.warn "${getFullDeviceName()} reset..."

    // Clear the custom input source names...
    state.inputSources = [:]
    state.currentInput =[:]
}
*/

def updated()
{
	writeLogInfo("${getFullDeviceName()} updated...")
    state.version = getVersion()
    unschedule()
    publishSupportedInputs()
    publishButtonCount()

	// disable debug logs after 30 min
	if (debugOutput) 
		runIn(1800,logsOff)

    initialize()
}

void refresh()
{
    writeLogInfo("${getFullDeviceName()} refresh")
    unschedule("refreshNextAttribute")
    parent.requestZoneRefresh(getCurrentZone())
}

def logsOff() 
{
    log.warn "${getFullDeviceName()} debug logging disabled..."
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

def setZone(Integer targetZone)
{
    state.zone = ["Zone":targetZone]
}


def on() 
{
    sendCommand(getCommand("power.on"))
    parent.requestFastStatusQuery(getCurrentZone(), "power")
}

def off()
{
    sendCommand(getCommand("power.off"))
    parent.requestFastStatusQuery(getCurrentZone(), "power")
}

def mute()
{
    sendEvent(name: "mute", value: "muted")
    sendCommand(getCommand("mute.on"))
    parent.requestFastStatusQuery(getCurrentZone(), "mute")
}

def unmute()
{
    sendEvent(name: "mute", value: "unmuted")
    sendCommand(getCommand("mute.off"))
    parent.requestFastStatusQuery(getCurrentZone(), "mute")
}

def muteToggle()
{
    if (device.currentValue("mute") == "muted") {
        unmute()
    } else {
        mute()
    }
}

def setInputSource(String inputName) {
    def code = parent.resolveInputCode(inputName)
    if (!code) {
        log.warn "${getFullDeviceName()} unknown input source: ${inputName}"
        return
    }
    queueInputSource(code)
}

def setInputSourceRaw(String code) {
    if (!code) {
        return
    }
    queueInputSource(code.trim().padLeft(2, "0"))
}

private void queueInputSource(String code) {
    if (settings?.powerOnBeforeInput != false && device.currentValue("switch") == "off") {
        on()
        runIn(2, "applyInputSource", [data: [code: code]])
    } else {
        applyInputSource([code: code])
    }
}

def applyInputSource(data) {
    def telnetCmd = getCommand("input.set.${data.code}")
    if (telnetCmd) {
        def zone = getCurrentZone()
        def withSelect = settings?.selectZoneBeforeInput != false && zone >= 2
        sendInputCommand(telnetCmd, withSelect)
    }
}

def inputNext() {
    queueInputChange(1)
}

def inputPrevious() {
    queueInputChange(-1)
}

private void queueInputChange(Integer direction) {
    if (settings?.powerOnBeforeInput != false && device.currentValue("switch") == "off") {
        on()
        runIn(2, "applyInputChange", [data: [direction: direction]])
    } else {
        applyInputChange([direction: direction])
    }
}

def applyInputChange(data) {
    def direction = data.direction as Integer
    if (parent.getInputCycleCodes()) {
        parent.cycleZoneInput(getCurrentZone(), direction)
        return
    }
    def telnetCmd = getCommand(direction > 0 ? "input.up" : "input.down")
    if (telnetCmd) {
        sendInputCommand(telnetCmd, false)
    }
}

def push(button) {
    pushed(button)
}

def pushed(button) {
    def zone = getCurrentZone()
    switch (button as Integer) {
        case 1: inputNext(); break
        case 2: inputPrevious(); break
        case 3:
            if (zone != 4) volumeUp()
            break
        case 4:
            if (zone != 4) volumeDown()
            break
        case 5:
            if (zone != 4) muteToggle()
            break
        default:
            log.warn "${getFullDeviceName()} unknown button ${button}"
    }
    sendEvent(name: "pushed", value: button, descriptionText: "Button ${button} pushed")
}

def publishButtonCount() {
    def zone = state.zone?.Zone as Integer
    def count = (zone == 4) ? 2 : 5
    sendEvent(name: "numberOfButtons", value: count)
}

private void sendInputCommand(String telnetCmd, Boolean withZoneSelect = false) {
    parent.sendZoneCommand(getCurrentZone(), telnetCmd, withZoneSelect)
}

def publishSupportedInputs() {
    def map = parent.getInputSourceMap()
    if (!map) {
        return
    }
    def names = map.values() as List
    sendEvent(name: "supportedInputs", value: names)
    state.supportedInputNames = names
    state.inputCodeByName = map.collectEntries { k, v -> [(v): k] }
}

def refreshVolume()
{
    String volQry = getCommand("volume.query")
    writeLogDebug("VolumeQuery = ${volQry}")
    sendCommand(volQry)
}

def volumeUp()
{
    sendCommand(getCommand("volume.up"))
    parent.requestFastStatusQuery(getCurrentZone(), "volume")
}

def volumeDown()
{
    sendCommand(getCommand("volume.down"))
    parent.requestFastStatusQuery(getCurrentZone(), "volume")
}

def getCommand(String command){
    def zone = getCurrentZone()
    writeLogDebug("getcommand ${zone} ${command}")
    return parent.getCommand(zone, command)
}

def int getCurrentZone()
{
	return state.zone.Zone as Integer
}

def sendCommand(String command)
{
    if (!command) {
        writeLogDebug("child sendCommand skipped empty command")
        return
    }
    writeLogDebug("child sendCommand ${command}")
    parent.sendTelnetMsg(command)
}


def fromA(String command) {
    writeLogDebug("handleReceiverResponse ${command}")
    if(command == "power.on"){
        writeLogInfo("${getFullDeviceName()} power is on")
        sendEvent(name: "switch", value: "on")
    }else if (command == "power.off"){
        writeLogInfo("${getFullDeviceName()} power is off")
        sendEvent(name: "switch", value: "off")
    }else if (command =="mute.on"){
        writeLogInfo("${getFullDeviceName()} is muted")
        sendEvent(name: "mute", value: "muted")
    }else if (command == "mute.off"){
        writeLogInfo("${getFullDeviceName()} is unmuted")
        sendEvent(name: "mute", value: "unmuted")
    }
}

def fromA(String command, String value) {
    writeLogDebug("handleReceiverResponse ${command} ${value}")

    if(command == "volume"){
        writeLogInfo("${getFullDeviceName()} volume is ${value}")
        sendEvent(name: "volume", value: value)
    } else if (command == "input") {
        applyInputDisplay(value)
    }
}

def updateInputDisplay() {
    def code = device.currentValue("input")?.toString()?.trim()
    if (code) {
        applyInputDisplay(code)
    }
}

private void applyInputDisplay(String code) {
    def normalized = code.toString().trim().padLeft(2, "0")
    def sourceName = parent.getInputName(normalized)
    writeLogInfo("${getFullDeviceName()} input is ${sourceName} (${normalized})")
    sendEvent(name: "input", value: normalized)
    sendEvent(name: "mediaInputSource", value: sourceName)
}

def String getFullDeviceName()
{
    return "${parent.getName()} - ${device.getName()}"
}