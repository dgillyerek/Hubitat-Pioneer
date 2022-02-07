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
		attribute "volume", "number"
		//attribute "mediaInputSource", "string"

        //command "muteToggle"
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

@Field static Map zoneNumbers = ["Main":1, "Zone 2":2, "Zone 3":3, "Zone 4":4 ] 

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
    
    state.inputSources = [:]
    state.currentInput =[:]
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

def fromParent(String msg)
{
    parent.fromChild("received ${msg}")
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

def forwardResponse(Map cmdMap)
{

    writeLogDebug("forwardRespose ${cmdMap}")
	Integer targetZone = cmdMap.zone
	String command = cmdMap.data

    // ISCP commands consist of 2 parts: a 3 character command code such as PWR (power) or MVL (master volume level)
    // and a value of variable length. We'll split the command into separate parts... 
    String cmdPre = command.substring(0, 3)
	String cmdVal = command.substring(3)
	
    // Next, we'll check if the 3 character command prefix is meant for this zone...
    if(!isValidCommandPrefixForZone(targetZone, cmdPre))
	{
		writeLogDebug("Ignoring ${command} - not for this zone")
		return
	}

    // Some responses have a value of N/A if the command that initiated the response is invalid for
    // the zone's current state such as attempting to set the volume level if the zone is currently
    // powered off. Including out of range values with a command can also result in this response value.  
    // We'll ignore these...
	if(cmdVal == "N/A")
	{
		writeLogDebug("Received ${command} - ignoring...")
		return
	}

	boolean handled = true

	switch(command)
	{
        case zoneCmdMap[targetZone].PowerOn:
			sendEvent(name: "switch", value: "on")
			writeLogInfo("${getFullDeviceName()} power is on")
			break

        case zoneCmdMap[targetZone].PowerOff:
			sendEvent(name: "switch", value: "off")
			writeLogInfo("${getFullDeviceName()} power is off")
			break

		case zoneCmdMap[targetZone].MuteOn:
			sendEvent(name: "mute", value: "muted")
			writeLogInfo("${getFullDeviceName()} is muted")
			break

		case zoneCmdMap[targetZone].MuteOff:
			sendEvent(name: "mute", value: "unmuted")
			writeLogInfo("${getFullDeviceName()} is unmuted")
			break

		default:
			handled = false
	}

	if(!handled)
	{
        // Some responses have a value of N/A if the command that initiated the response is invalid for
        // the zone's current state such as attempting to set the volume level if the zone is currently
        // powered off. Including out of range values with a command can also result in this response value.  
        // We'll ignore these...
		if(cmdVal == "N/A")
			return

        else if(cmdVal == "QSTN")
            return

        // Check if the command is a "setter" command type such as Volume Set (ZVLNN) 
        //  or Input Set (SLZNN).  
		switch(cmdPre)
		{
			case zoneCmdPrefixes[targetZone].Volume:
				Integer vol = PioneerVolumeToVolumePercent(cmdVal)
			    writeLogInfo("${getFullDeviceName()} volume is ${vol}")
				sendEvent(name: "volume", value: vol)
				break

			case zoneCmdPrefixes[targetZone].Input:
                String sourceName = getSourceName(cmdVal)
			    writeLogInfo("${getFullDeviceName()} input source is ${sourceName}")
				sendEvent(name: "mediaInputSource", value: sourceName)
				break
		}
	}
}

def boolean isValidCommandPrefixForZone(int zone, String commandPrefix)
{
    return zoneCmdPrefixes[zone].containsValue(commandPrefix)
}

def getSourceName(String sourceHexVal)
{
    try
    {
        // state.inputSources maps an Pioneer input source hex value to a custom input index and name. For example:
        // state.inputSources[1:[code:"00", name:"HTPC"], 2:[code:"22", name: "Video Game"], 3:[code:"0A", name: "Blu-ray"]] 
        // This allows the user to select an input source by entering an index number such as 1, 2, or 3 instead of the
        // Pioneer input source hex values (00, 22, 0A, etc.).

        if(state.inputSources == null)
            state.inputSources = [:]

        Integer val = Integer.parseInt(sourceHexVal, 16)
        //writeLogDebug("sourceVal: ${sourceVal}  sourceHexVal: ${sourceHexVal}")

        boolean sourceNameExists = false
        String inputName = ""
        Integer inputNumber = 1
        //writeLogDebug("state.inputSources contains ${state.inputSources.size()} entries.")
        
        if(state.inputSources.size() > 0)
        {
            def sourceNameMap = state.inputSources.find { it.value.code == sourceHexVal }

            if(sourceNameMap != null)
            {
                inputName = sourceNameMap.value.name
                inputNumber = sourceNameMap.key as Integer

                sourceNameExists = true                
            }
        }

        if(!sourceNameExists)
        {
            inputNumber = (state.inputSources.size() as Integer) + 1
            inputName = "Input ${inputNumber}"
            writeLogDebug("creating default sourceName for this input: ${inputName}")

            def inputMap = ["code":sourceHexVal, "name": inputName]          
            state.inputSources.put((inputNumber), inputMap)
            writeLogDebug("state.inputSources contains ${state.inputSources.size()} entries.")
        }

		state.currentInput = [:]
		state.currentInput.index = inputNumber
		state.currentInput.name = inputName

        writeLogDebug("getSourceName::state.currentInput: ${state.currentInput}")
        return inputName
    }

    catch(ex)
    {
        writeLogDebug("getSourceName caused the following exception: ${ex}")
    }
}

def editCurrentInputName(String inputName)
{
    writeLogDebug("editCurrentInputName: ${inputName}")
    writeLogDebug("editCurrentInputName:state.currentInput: ${state.currentInput}")

    if(state.currentInput.size() == 0)
    {
        writeLogInfo("No Pioneer inputs have been selected yet.  Use your remote to switch to each available input.")
        return
    }

	Integer inputNumber = state.currentInput.index ?: -1

	if(inputNumber == -1)
	{
		writeLogDebug("inputNum == -1")
		return
	}

	writeLogDebug("inputNum ${inputNumber}")

	def map = state.inputSources[inputNumber.toString()]
	writeLogDebug("map = ${map}")

	writeLogDebug("curInputName = ${map.name}")
	map.name = inputName

   	state.currentInput = [:]
	state.currentInput.index = inputNumber
	state.currentInput.name = inputName

	sendEvent(name: "mediaInputSource", value: inputName)
}

def String getInputSourceFromIndex(Integer sourceIndex)
{
    writeLogDebug("Getting Pioneer source number for index ${sourceIndex}...")
    def sourceNameMap = state.inputSources[sourceIndex.toString()]

    if(sourceNameMap == null)
    {
        writeLogDebug("  Pioneer source number for this index could not be found.  Confirm that a custom source name has been created.")
        return null
    }

    else
    {
        String code = sourceNameMap.code
        writeLogDebug("  Found Pioneer source number ${code}")
        return code
    }
}

def PioneerVolumeToVolumePercent(String hexValue)
{
	writeLogDebug("PioneerVolumeToVolumePercent(String hexValue)  hexValue: ${hexValue} ")
	
	float hexVolumeValue = (float)Integer.parseInt(hexValue, 16)
	writeLogDebug("hexVolumeValue: ${hexVolumeValue}")
	
	float maxIscpHexValue = (float)parent.getEiscpVolumeMaxSetting()
	writeLogDebug("maxIscpHexValue: ${maxIscpHexValue}")

	if(hexVolumeValue > maxIscpHexValue)
		return 100

	Integer volPercent = (Integer)( ((hexVolumeValue / maxIscpHexValue) * 100.0 ) + 0.5)
	writeLogDebug("volPercent: ${volPercent}")

	if(volPercent > 100)
		volPercent = 100

	else if(volPercent < 0)
		volPercent = 0

	return volPercent
}

def String getFullDeviceName()
{
    return "${parent.getName()} - ${device.getName()}"
}