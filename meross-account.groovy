/**
 *  Meross Account - Hubitat Elevation driver (parent)
 *
 *  Signs in to your Meross account once to fetch your device list and the
 *  account's device key, then finds each light strip on your LAN by itself
 *  and creates a child device for it ("Meross Light Strip (Local)").
 *
 *  The cloud is only used for sign-in/discovery. Day-to-day control is local:
 *  the child devices talk straight to the strips over HTTP on your network.
 *
 *  Requires the "Meross Light Strip (Local)" driver to be installed as well.
 *
 *  Version: 1.0 Beta (2026-10-10)
 *  Author:  Sunny Ghai (https://github.com/sunnyghai), created with AI assistance
 *  Source:  https://github.com/sunnyghai/hubitat-meross
 *  Copyright (c) 2026 Sunny Ghai
 *
 *  BETA SOFTWARE - provided as-is, with no support and no warranty of any kind.
 *  Use at your own risk. Not affiliated with or endorsed by Meross or Hubitat.
 *  Community testing, bug reports and improvements are welcome: https://github.com/sunnyghai/hubitat-meross/issues
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String DRIVER_VERSION = "1.0 Beta"
@Field static final String MEROSS_SECRET = "23x17ahWarFH6w29"
@Field static final String CLIENT_VERSION = "0.4.10.4"
@Field static final String CHILD_NAMESPACE = "sunnyghai"
@Field static final String CHILD_DRIVER = "Meross Light Strip (Local)"
@Field static final int SCAN_BATCH = 32
@Field static final long RESCAN_MIN_MS = 900000L   // automatic rescans at most every 15 minutes

metadata {
    definition(name: "Meross Account", namespace: "sunnyghai", author: "Sunny Ghai",
               importUrl: "https://raw.githubusercontent.com/sunnyghai/hubitat-meross/main/meross-account.groovy") {
        capability "Initialize"

        command "discoverDevices"
        command "scanNetwork"

        attribute "status", "string"
    }

    preferences {
        input name: "email", type: "text", title: "Meross account email", required: true
        input name: "password", type: "password", title: "Meross account password", required: true
        input name: "region", type: "enum", title: "Account region", defaultValue: "us",
              options: ["us": "Americas", "eu": "Europe", "ap": "Asia-Pacific"],
              description: "A wrong choice is corrected automatically at sign-in"
        input name: "mfaCode", type: "text", title: "Two-step verification code", required: false,
              description: "Only if your Meross account has two-step verification turned on. Enter a fresh code, save, then run Discover Devices straight away."
        input name: "typePrefix", type: "text", title: "Device types to add", defaultValue: "msl320",
              description: "Comma-separated model prefixes. msl320 covers the MSL320 strip family."
        input name: "subnet", type: "text", title: "Network to scan", required: false,
              description: "First three numbers of your LAN, e.g. 192.168.1. Leave blank to use the hub's own network."
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: false
    }
}

/* ---------- lifecycle ---------- */

void installed() {
    log.info "${device.displayName} installed (driver version ${DRIVER_VERSION})"
    recordVersion()
    setStatus("Enter your Meross sign-in details, save, then run Discover Devices")
}

void updated() {
    log.info "${device.displayName} preferences updated (driver version ${DRIVER_VERSION})"
    recordVersion()
    if (logEnable) runIn(1800, "logsOff")
}

void initialize() {
    recordVersion()
    // Hub restart: IP addresses may have changed while the hub was down
    if (state.key && state.devices) runIn(30, "scanNetwork")
}

void logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* ---------- commands ---------- */

void discoverDevices() {
    recordVersion()
    if (!email || !password) {
        setStatus("Enter your Meross email and password in preferences first")
        return
    }
    setStatus("Signing in to Meross")
    login("https://iotx-${region ?: 'us'}.meross.com", true)
}

void scanNetwork() {
    if (!state.key || !state.devices) {
        setStatus("Run Discover Devices first")
        return
    }
    String prefix = subnetPrefix()
    if (!prefix) {
        setStatus("Could not work out which network to scan - fill in 'Network to scan'")
        return
    }
    state.scanPrefix = prefix
    state.lastScan = now()
    setStatus("Scanning ${prefix}.1-254 for your strips")
    scanBatch([start: 1])
}

/* ---------- called by child devices ---------- */

String getMerossKey() {
    return state.key ?: ""
}

void childUnreachable(String dni) {
    long last = (state.lastScan ?: 0) as long
    if (now() - last < RESCAN_MIN_MS) return
    log.info "${device.displayName}: ${getChildDevice(dni)?.displayName ?: dni} stopped answering - rescanning in case its IP address changed"
    scanNetwork()
}

/* ---------- Meross cloud: sign-in and device list ---------- */

private void login(String baseUrl, boolean allowRedirect) {
    Map params = [
        email             : (email as String).trim(),
        password          : md5(password as String),
        accountCountryCode: "us",
        encryption        : 1,
        agree             : 0,
        mobileInfo        : [
            deviceModel    : "Hubitat",
            mobileOsVersion: (location?.hub?.firmwareVersionString ?: "") as String,
            mobileOs       : "Hubitat",
            uuid           : clientUuid(),
            carrier        : ""
        ]
    ]
    if (mfaCode) params.mfaCode = (mfaCode as String).trim()
    cloudPost(baseUrl, "/v1/Auth/signIn", params, null, "handleLogin", [baseUrl: baseUrl, allowRedirect: allowRedirect])
}

void handleLogin(resp, data) {
    Map json = cloudJson(resp)
    if (json == null) {
        setStatus("Sign-in failed: no usable reply from Meross (${data?.baseUrl})")
        return
    }
    int code = (json.apiStatus != null ? json.apiStatus : -1) as int
    switch (code) {
        case 0:
            break
        case 1030:
            String other = normaliseUrl(json.data?.domain as String)
            if (data?.allowRedirect && other) {
                if (logEnable) log.debug "${device.displayName}: account lives in another region, retrying at ${other}"
                login(other, false)
            } else {
                setStatus("Sign-in failed: Meross says this account is in a different region")
            }
            return
        case 1033:
            setStatus("Sign-in needs a two-step verification code: enter a fresh one in preferences, save, then run Discover Devices")
            return
        case 1032:
            setStatus("Sign-in failed: the two-step verification code was wrong or expired")
            clearMfa()
            return
        case 1000: case 1001: case 1002: case 1004: case 1005: case 1008:
            setStatus("Sign-in failed: wrong email or password")
            return
        case 1003:
            setStatus("Sign-in failed: this Meross account is disabled")
            return
        case 1028:
            setStatus("Sign-in refused: too many attempts. Wait a while before trying again")
            return
        case 1301:
            setStatus("Sign-in refused: too many active sign-ins on this account. Wait a few hours before trying again")
            return
        default:
            setStatus("Sign-in failed: Meross returned code ${code} ${json.info ?: ''}")
            return
    }

    clearMfa()
    Map d = json.data as Map
    if (!d?.token || d?.key == null) {
        setStatus("Sign-in failed: reply did not contain the expected details")
        return
    }
    state.key = d.key as String
    state.userId = d.userid as String
    String domain = normaliseUrl(d.domain as String) ?: (data.baseUrl as String)
    setStatus("Signed in - fetching device list")
    cloudPost(domain, "/v1/Device/devList", [:], d.token as String, "handleDevList", [baseUrl: domain, token: d.token as String])
}

void handleDevList(resp, data) {
    Map json = cloudJson(resp)

    // The sign-in is only needed for this one lookup; sign out so tokens don't pile up on the account
    cloudPost(data.baseUrl as String, "/v1/Profile/logout", [:], data.token as String, "handleLogout", [:])

    if (json == null || (json.apiStatus as Integer) != 0 || !(json.data instanceof List)) {
        setStatus("Could not fetch the device list (code ${json?.apiStatus})")
        return
    }

    List<String> prefixes = ((typePrefix ?: "msl320") as String).toLowerCase().split(",").collect { it.trim() }.findAll { it }
    Map devices = [:]
    int skipped = 0
    (json.data as List).each { dev ->
        String type = ((dev.deviceType ?: "") as String).toLowerCase()
        String uuid = dev.uuid as String
        if (!uuid) return
        if (prefixes.any { type.startsWith(it) }) {
            devices[uuid] = [name: (dev.devName ?: type) as String, type: type]
        } else {
            skipped++
        }
    }
    state.devices = devices
    if (logEnable) log.debug "${device.displayName}: ${devices.size()} matching device(s), ${skipped} other Meross device(s) ignored"

    if (devices.isEmpty()) {
        setStatus("Signed in, but no devices matching '${prefixes.join(', ')}' are on this account (${skipped} other device(s) ignored)")
        return
    }

    int created = 0
    boolean driverMissing = false
    devices.each { uuid, info ->
        String dni = childDni(uuid as String)
        def child = getChildDevice(dni)
        if (!child) {
            try {
                child = addChildDevice(CHILD_NAMESPACE, CHILD_DRIVER, dni,
                        [name: "Meross ${(info.type as String).toUpperCase()}", label: info.name, isComponent: false])
                created++
            } catch (Exception e) {
                driverMissing = true
                log.error "${device.displayName}: could not create '${info.name}': ${e.message}"
            }
        }
        if (child) {
            child.updateDataValue("uuid", uuid as String)
            child.updateDataValue("model", info.type as String)
        }
    }
    if (driverMissing) {
        setStatus("Could not create the strip devices - install the '${CHILD_DRIVER}' driver code, then run Discover Devices again")
        return
    }
    log.info "${device.displayName}: ${devices.size()} strip(s) on the account, ${created} new device(s) created"
    scanNetwork()
}

void handleLogout(resp, data) {
    if (logEnable) log.debug "${device.displayName}: signed out of Meross cloud (HTTP ${resp?.getStatus()})"
}

private void cloudPost(String baseUrl, String path, Map params, String token, String callback, Map data) {
    String encoded = JsonOutput.toJson(params).getBytes("UTF-8").encodeBase64().toString()
    long timestamp = now()
    String nonce = md5(UUID.randomUUID().toString()).substring(0, 16).toUpperCase()
    String sign = md5(MEROSS_SECRET + timestamp + nonce + encoded)

    Map req = [
        uri               : baseUrl + path,
        headers           : [
            "AppVersion"   : CLIENT_VERSION,
            "Authorization": (token ? "Basic ${token}" : "Basic") as String,
            "vender"       : "meross",
            "AppType"      : "MerossIOT",
            "AppLanguage"  : "EN",
            "User-Agent"   : "MerossIOT/${CLIENT_VERSION}" as String
        ],
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : [params: encoded, sign: sign, timestamp: timestamp, nonce: nonce],
        timeout           : 20
    ]
    // Never log the request itself: it contains the sign-in details
    if (logEnable) log.debug "${device.displayName} -> ${baseUrl}${path}"
    try {
        asynchttpPost(callback, req, data)
    } catch (Exception e) {
        setStatus("Request to Meross failed: ${e.message}")
    }
}

private Map cloudJson(resp) {
    if (resp == null || resp.hasError()) {
        log.warn "${device.displayName}: Meross cloud request failed: ${resp?.getErrorMessage()}"
        return null
    }
    try {
        return resp.getJson() as Map
    } catch (Exception e) {
        log.warn "${device.displayName}: could not read Meross reply: ${e.message}"
        return null
    }
}

/* ---------- LAN scan ---------- */

void scanBatch(Map data) {
    String prefix = state.scanPrefix as String
    if (!prefix) return
    int start = (data?.start ?: 1) as int
    int end = Math.min(start + SCAN_BATCH - 1, 254)
    for (int host = start; host <= end; host++) {
        probe("${prefix}.${host}")
    }
    if (end < 254) {
        runInMillis(1500, "scanBatch", [data: [start: end + 1]])
    } else {
        runIn(6, "scanFinished")
    }
}

private void probe(String ip) {
    String messageId = md5(UUID.randomUUID().toString())
    long timestamp = now().intdiv(1000)
    String sign = md5(messageId + (state.key ?: "") + timestamp)
    Map req = [
        uri               : "http://${ip}/config",
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : [
            header : [
                from          : "http://${ip}/config",
                messageId     : messageId,
                method        : "GET",
                namespace     : "Appliance.System.All",
                payloadVersion: 1,
                sign          : sign,
                timestamp     : timestamp
            ],
            payload: [:]
        ],
        timeout           : 4
    ]
    try {
        asynchttpPost("handleProbe", req, [ip: ip])
    } catch (Exception ignored) {
        // nothing at this address
    }
}

void handleProbe(resp, data) {
    if (resp == null || resp.hasError()) return   // nothing there, or not a Meross device
    Map json
    try {
        json = resp.getJson() as Map
    } catch (Exception ignored) {
        return
    }
    String uuid = json?.payload?.all?.system?.hardware?.uuid
    if (!uuid || !(state.devices as Map)?.containsKey(uuid)) return

    String ip = data.ip as String
    def child = getChildDevice(childDni(uuid))
    if (!child) return
    if (child.getDataValue("ip") != ip) {
        log.info "${device.displayName}: found ${child.displayName} at ${ip}"
        child.updateDataValue("ip", ip)
    }
    child.initialize()
}

void scanFinished() {
    Map devices = (state.devices ?: [:]) as Map
    List<String> missing = []
    devices.each { uuid, info ->
        if (!getChildDevice(childDni(uuid as String))?.getDataValue("ip")) missing << (info.name as String)
    }
    if (missing.isEmpty()) {
        setStatus("Ready: ${devices.size()} strip(s) found on the network")
    } else {
        setStatus("Found ${devices.size() - missing.size()} of ${devices.size()}. Not found on ${state.scanPrefix}.x: ${missing.join(', ')}")
    }
}

/* ---------- utilities ---------- */

// Shown in the device's Data section so users can tell which version is installed
private void recordVersion() {
    if (getDataValue("driverVersion") != DRIVER_VERSION) updateDataValue("driverVersion", DRIVER_VERSION)
}

private String subnetPrefix() {
    String source = ((subnet ?: "") as String).trim()
    if (!source) source = (location?.hub?.localIP ?: "") as String
    def m = (source =~ /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})/)
    return m.find() ? "${m.group(1)}.${m.group(2)}.${m.group(3)}".toString() : null
}

private String childDni(String uuid) {
    return "meross-${uuid}".toString()
}

private String clientUuid() {
    if (!state.clientUuid) {
        state.clientUuid = md5(UUID.randomUUID().toString()).substring(0, 30) + UUID.randomUUID().toString()
    }
    return state.clientUuid as String
}

private String normaliseUrl(String domain) {
    if (!domain) return null
    String d = domain.trim()
    if (!d.toLowerCase().startsWith("http")) d = "https://" + d
    return d.endsWith("/") ? d.substring(0, d.length() - 1) : d
}

private void clearMfa() {
    if (mfaCode) device.removeSetting("mfaCode")
}

private void setStatus(String text) {
    log.info "${device.displayName}: ${text}"
    sendEvent(name: "status", value: text)
}

private static String md5(String s) {
    return MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).encodeHex().toString()
}
