 /*
    Copyright © 2020 Steve Vibert (@SteveV)

    Portions of this code are based on Mike Maxwell's PioneerIP device handler for SmartThings
    taken from this post: https://community.smartthings.com/t/itach-integration/25470/23
    
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    Pioneer eISCP Protocol Specifications Documents which include zone specific commands can 
    be found at: https://github.com/stevevib/Hubitat/Devices/PioneerMultiZoneAVR/Docs/


    Version History:
    ================

    Date            Version             By                  Changes
    --------------------------------------------------------------------------------
    2020-12-14      0.9.201214.1        Steve Vibert        Initial Beta Release
    2021-03-20      0.9.210320.1        Steve Vibert        Fix: text logging settings being ignored
    2021-03-24      0.9.210324.0        Steve Vibert        Fix: Lower case or single digit volume level command values cause ZZZN/A response (where ZZZ represents the zone command prefix)

    WARNING!
        In addition to controlling basic receiver functionality, this driver also includes 
        the ability to set volume levels and other settings using raw eISCP commands. Some 
        commands such as volume level, allow you to enter a value from a given min/max value 
        range. Randomly trying these commands without fully understanding these values may 
        lead to unintended consequences that could damage your receiver and/or your speakers.

        Please make sure you read *and understand* the eISCP protocal documents before trying 
        a command to see what it does.   
*/

import groovy.transform.Field

metadata 
{
	definition (name: "Pioneer Multi-Zone AVR Child Zone", namespace: "SteveV", author: "Steve Vibert")
	{
		capability "Initialize"
		capability "Switch"
		capability "AudioVolume"
		capability "Actuator"

		//attribute "mediaSource", "STRING"
		attribute "mute", "string"
      //  attribute "input", "string"     
		attribute "volume", "string"
		//attribute "mediaInputSource", "string"

        command "muteToggle"
		// command "setInputSource", [[name:"Source Index*", type: "NUMBER", description: "Input ID by Index" ]]
		// command "setInputSourceRaw", [[name:"Source Hex*", type: "STRING", description: "Input ID by HEX Value" ]]
     //   command "editCurrentInputName", [[name:"New name*", type: "STRING", description: "Display name for this input" ]]
	}

	preferences 
	{   
		input name: "textLogging",  type: "bool", title: "Enable description text logging ", required: true, defaultValue: true
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def getVersion()
{
    return "0.9.210324.0"
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

	// disable debug logs after 30 min
	if (debugOutput) 
		runIn(1800,logsOff)

    initialize()
}

void refresh()
{
    writeLogInfo ("${getFullDeviceName()} refresh")

    // Get the current state for the zone...
    sendCommand( getCommand("power.query") )
	sendCommand( getCommand("volume.query") )
	sendCommand( getCommand("mute.query") )
	//sendCommand( getCommand(InputQuery) )
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
}

def off()
{
    sendCommand(getCommand("power.off"))
}

def mute()
{
    sendCommand(getCommand("mute.on"))
}

def unmute()
{
    sendCommand(getCommand("mute.off"))
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
}

def volumeDown()
{
    sendCommand(getCommand("volume.down"))    
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
    writeLogDebug("child sendCommand ${command}")
    parent.sendTelnetMsg(command)
}


def fromParent(String msg)
{
    writeLogInfo("from parent." + msg)
}

def fromA(Integer blah) {
    writeLogDebug("blah...")
    writeLogDebug(blah)
}

def fromA(String command) {
    writeLogDebug("handle response")
    writeLogDebug("handleReceiverResponse " + command)
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
        writeLogInfo("${getFullDeviceName()} power is unmuted")
        sendEvent(name: "mute", value: "unmuted")
    }
}

def fromA(String command, String value) {
    writeLogDebug("handle response")
    writeLogDebug("handleReceiverResponse " + command + " " + value)

    if(command == "volume"){
        writeLogInfo("${getFullDeviceName()} volume is ${value}")
        sendEvent(name: "volume", value: value)
    }   
}

def String getFullDeviceName()
{
    return "${parent.getName()} - ${device.getName()}"
}