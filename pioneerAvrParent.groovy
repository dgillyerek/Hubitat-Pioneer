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
	definition (name: "Pioneer Multi-Zone AVR Parent", namespace: "SteveV", author: "Steve Vibert")
	{
		capability "Initialize"
		capability "Telnet"

        command "refresh"
	}

    preferences 
	{   
		input name: "PioneerIP", type: "text", title: "Pioneer IP", required: true, displayDuringSetup: true
		input name: "eISCPPort", type: "number", title: "EISCP Port", defaultValue: 60128, required: true, displayDuringSetup: true
		input name: "eISCPTermination", type: "enum", options: [[1:"CR"],[2:"LF"],[3:"CRLF"],[4:"EOF"]] ,title: "EISCP Termination Option", required: true, displayDuringSetup: true, description: "Most receivers should work with CR termination"
		input name: "eISCPVolumeRange", type: "enum", options: [[50:"0-50 (0x00-0x32)"],[80:"0-80 (0x00-0x50)"],[100:"0-100 (0x00-0x64)"],[200:"0-100 Half Step (0x00-0xC8)"]],defaultValue:80, title: "Supported Volume Range", required: true, displayDuringSetup: true, description:"(see Pioneer EISCP Protocol doc for model specific values)"
        input name: "enabledReceiverZones", type: "enum", title: "Enabled Zones", required: true, multiple: true, options: [[1: "Main"],[2:"Zone 2"],[3:"Zone 3"],[4: "Zone 4"]]
 		input name: "textLogging",  type: "bool", title: "Enable description text logging ", required: true, defaultValue: true
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

@Field static List zoneNames = ["N/A", "Main", "Zone 2", "Zone 3", "Zone 4" ]
@Field static Map zoneNumbers = ["Main":1, "Zone 2":2, "Zone 3":3, "Zone 4":4 ] 

def getVersion()
{
    return "0.9.210320.1"
}

void parse(String description) 
{
    writeLogDebug("parse ${description}")
    handleReceiverResponse(description)
}

@Field static List PowerCommand = ["P", "AP", "BP", "ZE" ]
@Field static List VolumeCommand = ["V", "Z", "Y" ]
@Field static List Mute = ["M", "Z2M", "Z3M"]
//@field static List InputSet ["FN", "ZS", "ZT"]

String getCommand(Integer zone, String command){

    writeLogDebug("parent getCommand ${zone} ${command}")
    String[] a = command.split("\\.")
    def zoneIndex = zone - 1

    if (zone ==4 && (a[0]=="mute" || a[0] == "volume")){
        writeLogDebug("Zone4 can't mute nor volumn")
    }

    def sb = StringBuilder.newInstance()

    if (a[1]=="query")
        {
            sb.append("?")
        }

    if (a[0]=="power"){
        sb.append(PowerCommand[zoneIndex])
    }else if(a[0] == "mute" ){
        sb.append(Mute[zoneIndex])
    }else if (a[0] == "volume" ){
        sb.append(VolumeCommand[zoneIndex])
        if (a[1] == "query" && (zone == 2 || zone ==3 )){
            sb.append("V")
        }
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

def handleReceiverResponse(String description) 
{
    writeLogDebug("handleReceiverResponse ${description}")

    

    // String zoneName = getCommandZoneName(cmdPre)
    // // Forward the command to the appropriate zone...
    // if(zone != -1 && zoneName.length() > 0)
    // {
    //     def child = getChild(zoneName)

    //     if(child != null)
    //     {
    //         def cmdMap = ["zone":zone, "data":data]
    //         child.forwardResponse(cmdMap)
    //     }
    // }
}

def initialize()
{
	String ip = settings?.PioneerIP as String
	Integer port = settings?.eISCPPort as Integer

	writeLogDebug("ip: ${ip} port: ${port}")

	telnetConnect(ip, port, null, null)
	writeLogDebug("Opening telnet connection with ${ip}:${port}")

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
    //device.updateSetting("enabledReceiverZones",[value:"false",type:"enum"])	

    initialize()
}

void refresh()
{
    writeLogDebug("refresh")
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
                if (logEnable) 
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
    //writeLogDebug("getChild with ${zoneName}")
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

def fromChild(String msg)
{
    writeLogDebug("Received message from child: ${msg}")
}

def sendTelnetMsg(String msg) 
{
    writeLogDebug("Child called sendTelnetMsg with ${msg}")
    sendHubCommand(new hubitat.device.HubAction(finalizeTelnetMessage(msg), hubitat.device.Protocol.TELNET))
}
 
def finalizeTelnetMessage(command)
{
    def sb = StringBuilder.newInstance()
    sb.append(command)
    sb.append((char)Integer.parseInt("0D", 16))   //CR
    return sb.toString()
}

def Integer getEiscpVolumeMaxSetting()
{
    Integer maxIscpHexValue = settings?.eISCPVolumeRange?.toBigDecimal()
	writeLogDebug("settings?.eISCPVolumeRange: ${maxIscpHexValue}")
    return maxIscpHexValue
}

def getName()
{
    return device.getName()
}