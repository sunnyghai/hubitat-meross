/**
 *  Meross Light Strip (Local LAN) - Hubitat Elevation driver
 *
 *  Talks to the strip directly over HTTP on your LAN (POST http://<ip>/config)
 *  using the Meross signed-message protocol. No cloud connection is used here.
 *
 *  Two ways to use it:
 *   - As a child of the "Meross Account" driver, which signs in to Meross once,
 *     supplies the device key and finds the strip's IP address automatically.
 *   - Stand-alone, by typing the IP address and device key into preferences.
 *
 *  Intended for MSL320 / MSL320 Pro style RGB(+white) strips.
 *
 *  Supports: on/off, level, RGB color, color temperature, the strip's built-in
 *  effects (Party, Relaxing, Night, ... plus any custom ones made in the Meross app)
 *  chosen by name from a drop-down or by number, and polling.
 *  Not included: creating/editing effects, per-segment control, instant push updates.
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

import groovy.transform.Field
import hubitat.helper.ColorUtils
import java.security.MessageDigest

@Field static final String DRIVER_VERSION = "1.0 Beta"

@Field static final int CAP_RGB = 1
@Field static final int CAP_TEMP = 2
@Field static final int CAP_LUMINANCE = 4
@Field static final int CAP_EFFECT = 8
@Field static final int CHANNEL = 0   // MSL320 strips have a single channel

// Select Color drop-down: [hue, saturation] on Hubitat's 0-100 scale, or [kelvin] for whites
@Field static final Map NAMED_COLORS = [
    "Red"       : [0, 100],
    "Orange"    : [8, 100],
    "Yellow"    : [17, 100],
    "Green"     : [33, 100],
    "Cyan"      : [50, 100],
    "Blue"      : [67, 100],
    "Purple"    : [78, 100],
    "Pink"      : [92, 60],
    "Warm White": [2700],
    "Cool White": [4000],
    "Daylight"  : [6500]
]

// Built-in effects offered in the Select Effect drop-down, matched on the strip's
// fixed icon names (the display names can be translated by the Meross app)
@Field static final Map EFFECT_ICONS = [
    "Night"   : "night",
    "Reading" : "read",
    "Working" : "work",
    "Relaxing": "relax",
    "Party"   : "party",
    "Colorful": "colorful"
]

@Field static final int KELVIN_MIN = 2700
@Field static final int KELVIN_MAX = 6500

metadata {
    definition(name: "Meross Light Strip (Local)", namespace: "sunnyghai", author: "Sunny Ghai",
               importUrl: "https://raw.githubusercontent.com/sunnyghai/hubitat-meross/main/meross-light-strip.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "LightEffects"
        capability "Refresh"
        capability "Initialize"

        attribute "healthStatus", "enum", ["online", "offline"]
        // Standard capability commands re-declared so the device page explains each box and hides
        // boxes the strip can't use. Apps that send the extra values (transition time, duration) still work.
        command "setLevel", [
            [name: "Brightness (1-100 %)*", type: "NUMBER",
             description: "1 = dimmest, 100 = brightest. 0 turns the strip off. Also dims a running effect."]
        ]
        command "setColorTemperature", [
            [name: "White light color (2700-6500 K)*", type: "NUMBER",
             description: "Switches the strip to white light. 2700 = warm, candle-like ... 4000 = neutral ... 6500 = cool daylight. Values outside 2700-6500 are limited to that range. Stops any running effect."],
            [name: "Brightness (1-100 %)", type: "NUMBER",
             description: "Optional. Leave blank to keep the current brightness. 0 turns the strip off."]
        ]
        command "setHue", [
            [name: "Hue (0-100)*", type: "NUMBER",
             description: "Position on Hubitat's color wheel: 0 = red, 8 = orange, 17 = yellow, 33 = green, 50 = cyan, 67 = blue, 78 = purple, 92 = pink, 100 = red again. Tip: Select Color is easier."]
        ]
        command "setSaturation", [
            [name: "Saturation (0-100 %)*", type: "NUMBER",
             description: "How strong the color is: 0 = white, 50 = pastel, 100 = full, vivid color."]
        ]
        command "selectColor", [
            [name: "Color*", type: "ENUM",
             constraints: ["Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "Warm White", "Cool White", "Daylight"],
             description: "Pick a color or white by name. Warm White = 2700 K, Cool White = 4000 K, Daylight = 6500 K. Stops any running effect."],
            [name: "Brightness (1-100 %)", type: "NUMBER",
             description: "Optional. Leave blank to keep the current brightness."]
        ]
        command "selectEffect", [[name: "Effect*", type: "ENUM",
                                  constraints: ["Night", "Reading", "Working", "Relaxing", "Party", "Colorful", "Off"],
                                  description: "Night: dim warm glow | Reading: bright warm white | Working: bright cool white | Relaxing: slow color fade | Party: fast color changes | Colorful: color cycle | Off: stop the effect and return to the previous color"]]
    }

    preferences {
        input name: "ipAddress", type: "text", title: "Device IP address (manual override)", required: false,
              description: "Leave blank when this device was created by Meross Account - the address is found automatically"
        input name: "deviceKey", type: "password", title: "Meross device key (manual override)", required: false,
              description: "Leave blank when this device was created by Meross Account - the key comes from your sign-in"
        input name: "pollInterval", type: "enum", title: "Poll interval", defaultValue: "1",
              options: ["0": "Disabled", "1": "1 minute", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes", "30": "30 minutes"],
              description: "How quickly changes made in the Meross app, Apple Home or by voice show up here. Commands sent from Hubitat update right away."
        input name: "whiteMode", type: "enum", title: "Color temperature method", defaultValue: "rgb",
              options: ["rgb": "Mix white from RGB (standard MSL320)", "native": "Dedicated white LEDs (MSL320 Pro / RGBWW)"],
              description: "Standard MSL320 strips have no white LEDs, so white is mixed from red, green and blue. Choose dedicated white LEDs only for an MSL320 Pro."
        input name: "gradual", type: "bool", title: "Fade between colors/levels", defaultValue: true,
              description: "On: the strip fades smoothly to each new color or brightness. Off: it changes instantly."
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true,
              description: "Writes a line to Logs whenever the strip changes, e.g. 'switch is on'."
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: false,
              description: "Extra detail in Logs for troubleshooting."
    }
}

/* ---------- lifecycle ---------- */

void installed() {
    log.info "${device.displayName} installed (driver version ${DRIVER_VERSION})"
    recordVersion()
    state.useToggleX = true
}

void updated() {
    log.info "${device.displayName} preferences updated"
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    recordVersion()
    unschedule()
    if (logEnable) runIn(1800, "logsOff")
    if (state.useToggleX == null) state.useToggleX = true

    switch ((pollInterval ?: "1") as String) {
        case "1":  runEvery1Minute("poll"); break
        case "5":  runEvery5Minutes("poll"); break
        case "10": runEvery10Minutes("poll"); break
        case "15": runEvery15Minutes("poll"); break
        case "30": runEvery30Minutes("poll"); break
        default: break
    }
    // Tidy up things left behind by earlier versions of this driver
    try { device.deleteCurrentState("partyMode") } catch (Exception ignored) { }
    try { if (settings?.channel != null) device.removeSetting("channel") } catch (Exception ignored) { }
    refresh()
}

void logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* ---------- commands ---------- */

// Refresh button: current state plus the effect list (picks up effects newly made in the Meross app)
void refresh() {
    poll()
    refreshEffects()
}

// Scheduled polling: current state only
void poll() {
    sendMeross("GET", "Appliance.System.All", [:])
}

void selectColor(String name, level = null) {
    List spec = NAMED_COLORS[name] as List
    if (!spec) {
        log.warn "${device.displayName}: unknown color '${name}' (choose from ${NAMED_COLORS.keySet().join(', ')})"
        return
    }
    if (spec.size() == 1) {
        setColorTemperature(spec[0], level)
    } else {
        Map color = [hue: spec[0], saturation: spec[1]]
        if (level != null) color.level = level
        setColor(color)
    }
}

void on() {
    setOnOff(1)
}

void off() {
    setOnOff(0)
}

void setLevel(level, duration = null) {
    int lvl = clamp(level as Integer, 0, 100)
    if (lvl == 0) {
        off()
        return
    }
    if (state.effectActive) {
        // Dim or brighten the running effect instead of leaving it
        setEffectLevel(lvl)
        return
    }
    state.luminance = lvl
    sendLight()
}

/* ---------- effects ---------- */

void selectEffect(String name) {
    if (!name) return
    if (name == "Off") {
        stopEffect()
        return
    }
    Integer idx = findEffect((EFFECT_ICONS[name] ?: name.toLowerCase()) as String, name)
    if (idx == null) {
        if (!state.effects) {
            // Effect list not loaded yet: fetch it, then start this effect
            state.pendingEffect = name
            refreshEffects()
        } else {
            log.warn "${device.displayName}: this strip has no ${name} effect (available: ${effectNames().join(', ')})"
        }
        return
    }
    activateEffect(idx)
}

private void stopEffect() {
    if (state.effectActive) {
        // Any normal light command ends the effect; resend the last color/white
        sendLight()
    } else if (txtEnable != false) {
        log.info "${device.displayName}: no effect is running"
    }
}

void setEffect(effectNumber) {
    int n = (effectNumber ?: 0) as int
    List effects = (state.effects ?: []) as List
    if (!effects) {
        state.pendingEffect = n
        refreshEffects()
        return
    }
    if (n < 1 || n > effects.size()) {
        log.warn "${device.displayName}: no effect number ${n} (choose 1-${effects.size()}: ${effectNames().join(', ')})"
        return
    }
    activateEffect(n - 1)
}

void setNextEffect() {
    stepEffect(1)
}

void setPreviousEffect() {
    stepEffect(-1)
}

// Internal: reload the effect list from the strip
void refreshEffects() {
    sendMeross("GET", "Appliance.Control.Light.Effect", [effect: [:]])
}

private void stepEffect(int dir) {
    List effects = (state.effects ?: []) as List
    if (!effects) {
        refreshEffects()
        return
    }
    int current = state.effectActive ? ((state.effectIndex ?: 0) as int) : (dir > 0 ? -1 : 0)
    int next = Math.floorMod(current + dir, effects.size())
    activateEffect(next)
}

private void activateEffect(int idx) {
    List effects = (state.effects ?: []) as List
    if (idx < 0 || idx >= effects.size()) return
    Map eff = new LinkedHashMap((Map) effects[idx])
    eff.enable = 1
    boolean isOff = device.currentValue("switch") != "on"
    state.effectActive = true
    state.effectIndex = idx
    sendMeross("SET", "Appliance.Control.Light.Effect", [effect: [eff]])
    if (isOff) setOnOff(1)
}

private void setEffectLevel(int lvl) {
    List effects = (state.effects ?: []) as List
    int idx = (state.effectIndex ?: 0) as int
    if (idx < 0 || idx >= effects.size()) return
    Map eff = new LinkedHashMap((Map) effects[idx])
    eff.member = ((eff.member ?: []) as List).collect { m ->
        Map copy = new LinkedHashMap((Map) m)
        copy.luminance = lvl
        copy
    }
    eff.enable = 1
    List updated = new ArrayList(effects)
    updated[idx] = eff
    state.effects = updated
    sendMeross("SET", "Appliance.Control.Light.Effect", [effect: [eff]])
}

private Integer findEffect(String iconKey, String displayName) {
    List effects = (state.effects ?: []) as List
    String k = iconKey.toLowerCase()
    // The icon name is fixed; the display name can be translated by the Meross app
    int i = effects.findIndexOf { ((it.iconName ?: "") as String).toLowerCase().endsWith("_${k}") }
    if (i < 0) i = effects.findIndexOf { ((it.effectName ?: "") as String).equalsIgnoreCase(displayName) }
    return i < 0 ? null : i
}

private List<String> effectNames() {
    return ((state.effects ?: []) as List).collect { (it.effectName ?: "?") as String }
}

private void parseEffects(Map payload) {
    if (!(payload?.effect instanceof List)) return
    List effects = (payload.effect as List).collect { e ->
        [Id: e.Id, effectName: e.effectName, iconName: e.iconName, enable: 0,
         mode: e.mode, speed: e.speed, member: e.member]
    }
    state.effects = effects
    Map numbered = [:]
    effects.eachWithIndex { e, i -> numbered[(i + 1).toString()] = e.effectName }
    sendEvt("lightEffects", groovy.json.JsonOutput.toJson(numbered))
    if (logEnable) log.debug "${device.displayName}: effects ${numbered}"

    def pending = state.pendingEffect
    state.remove("pendingEffect")
    if (pending instanceof String) {
        selectEffect(pending == "party" ? "Party" : pending)
    } else if (pending instanceof Number) {
        setEffect(pending)
    }
}

void setColor(Map colorMap) {
    if (colorMap == null) return
    int hue = clamp(((colorMap.hue != null) ? colorMap.hue : (device.currentValue("hue") ?: 0)) as Integer, 0, 100)
    int sat = clamp(((colorMap.saturation != null) ? colorMap.saturation : (device.currentValue("saturation") ?: 100)) as Integer, 0, 100)
    if (colorMap.level != null) {
        int lvl = clamp(colorMap.level as Integer, 0, 100)
        if (lvl == 0) {
            off()
            return
        }
        state.luminance = lvl
    }
    // Brightness is sent separately as luminance, so encode the color at full value
    List rgb = ColorUtils.hsvToRGB([hue, sat, 100])
    state.rgb = ((rgb[0] as int) << 16) | ((rgb[1] as int) << 8) | (rgb[2] as int)
    state.mode = "RGB"
    state.ctEmulated = false
    sendLight()
}

void setHue(hue) {
    setColor([hue: hue])
}

void setSaturation(saturation) {
    setColor([saturation: saturation])
}

void setColorTemperature(kelvin, level = null, transitionTime = null) {
    int k = clamp(kelvin as Integer, KELVIN_MIN, KELVIN_MAX)
    if (level != null) {
        int lvl = clamp(level as Integer, 0, 100)
        if (lvl == 0) {
            off()
            return
        }
        state.luminance = lvl
    }
    state.ctKelvin = k
    state.mode = "CT"
    if (whiteMode == "native") {
        state.ctEmulated = false
        state.temperature = kelvinToMeross(k)
    } else {
        // Standard MSL320 has RGB LEDs only: approximate the white point with an RGB mix
        state.ctEmulated = true
        state.rgb = kelvinToRgb(k)
    }
    sendLight()
}

/* ---------- command helpers ---------- */

private void setOnOff(int onoff) {
    int ch = CHANNEL
    if (state.useToggleX != false) {
        sendMeross("SET", "Appliance.Control.ToggleX", [togglex: [channel: ch, onoff: onoff]])
    } else {
        sendMeross("SET", "Appliance.Control.Light", [light: [channel: ch, onoff: onoff]])
    }
}

private void sendLight() {
    int ch = CHANNEL
    int lum = clamp((state.luminance ?: device.currentValue("level") ?: 100) as Integer, 1, 100)
    Map light = [channel: ch, gradual: (gradual == false ? 0 : 1), luminance: lum]
    state.effectActive = false   // a normal color/white command ends any running effect

    if (state.mode == "CT" && !state.ctEmulated) {
        light.capacity = CAP_TEMP | CAP_LUMINANCE
        light.temperature = clamp((state.temperature ?: 50) as Integer, 1, 100)
    } else {
        light.capacity = CAP_RGB | CAP_LUMINANCE
        light.rgb = (state.rgb != null ? state.rgb : 0xFFFFFF) as Integer
    }

    boolean isOff = device.currentValue("switch") != "on"
    if (state.useToggleX == false) {
        // Devices without ToggleX take on/off inside the light payload
        light.onoff = 1
        sendMeross("SET", "Appliance.Control.Light", [light: light])
    } else {
        sendMeross("SET", "Appliance.Control.Light", [light: light])
        if (isOff) setOnOff(1)
    }
}

/* ---------- transport ---------- */

private void sendMeross(String method, String namespace, Map payload) {
    String ip = ((ipAddress ?: getDataValue("ip") ?: "") as String).trim()
    if (!ip) {
        if (parent) {
            if (logEnable) log.debug "${device.displayName}: not found on the network yet - waiting for Meross Account to locate it"
        } else {
            log.warn "${device.displayName}: set the device IP address in preferences"
        }
        return
    }
    String key = (deviceKey ?: parent?.getMerossKey() ?: "") as String
    String messageId = md5(UUID.randomUUID().toString())
    long timestamp = now().intdiv(1000)
    String sign = md5(messageId + key + timestamp)

    Map body = [
        header : [
            from          : "http://${ip}/config",
            messageId     : messageId,
            method        : method,
            namespace     : namespace,
            payloadVersion: 1,
            sign          : sign,
            timestamp     : timestamp
        ],
        payload: payload
    ]

    Map params = [
        uri               : "http://${ip}/config",
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : body,
        timeout           : 5
    ]

    if (logEnable) log.debug "${device.displayName} -> ${method} ${namespace} ${payload}"
    try {
        asynchttpPost("handleResponse", params, [method: method, namespace: namespace])
    } catch (Exception e) {
        log.error "${device.displayName}: request failed: ${e.message}"
    }
}

void handleResponse(resp, data) {
    if (resp.hasError()) {
        log.warn "${device.displayName}: no response for ${data?.namespace} (${resp.getErrorMessage()})"
        setHealth("offline")
        // If the address was found automatically, ask the account device to look for the strip again
        int fails = ((state.failures ?: 0) as int) + 1
        state.failures = fails
        if (parent && !ipAddress && (fails == 3 || fails % 60 == 0)) {
            parent.childUnreachable(device.deviceNetworkId)
        }
        return
    }
    if (state.failures) state.failures = 0

    Map json
    try {
        json = resp.getJson() as Map
    } catch (Exception e) {
        log.warn "${device.displayName}: could not parse response: ${e.message}"
        return
    }
    if (logEnable) log.debug "${device.displayName} <- ${json}"
    setHealth("online")

    String method = json?.header?.method
    String namespace = json?.header?.namespace

    if (method == "ERROR") {
        def code = json?.payload?.error?.code
        if (code == 5001) {
            log.error "${device.displayName}: device rejected the message signature (error 5001) - the device key is wrong"
        } else {
            log.error "${device.displayName}: device returned error ${json?.payload?.error}"
        }
        return
    }

    if (method == "GETACK" && namespace == "Appliance.System.All") {
        parseSystemAll(json.payload as Map)
    } else if (method == "GETACK" && namespace == "Appliance.Control.Light.Effect") {
        parseEffects(json.payload as Map)
    } else if (method == "SETACK") {
        // Confirm the result from the device itself
        runInMillis(600, "poll")
    }
}

/* ---------- parsing ---------- */

private void parseSystemAll(Map payload) {
    Map all = payload?.all as Map
    if (!all) return
    int ch = CHANNEL

    Map sys = all.system as Map
    if (sys?.hardware?.type) updateDataValue("model", sys.hardware.type as String)
    if (sys?.hardware?.version) updateDataValue("hardware", sys.hardware.version as String)
    if (sys?.firmware?.version) updateDataValue("firmware", sys.firmware.version as String)
    if (sys?.hardware?.macAddress) updateDataValue("mac", sys.hardware.macAddress as String)

    Map digest = all.digest as Map
    if (!digest) return

    // on/off
    Integer onoff = null
    if (digest.togglex instanceof List) {
        state.useToggleX = true
        Map t = (digest.togglex as List).find { (it.channel as Integer) == ch } as Map
        if (t?.onoff != null) onoff = t.onoff as Integer
    } else if (digest.light?.onoff != null) {
        state.useToggleX = false
        onoff = digest.light.onoff as Integer
    }
    if (onoff != null) sendEvt("switch", onoff == 1 ? "on" : "off")

    // light state
    Map light = digest.light as Map
    if (!light) return

    int capacity = (light.capacity ?: 0) as Integer
    if (capacity & CAP_EFFECT) {
        // A built-in effect is running: report it, but keep the remembered color for later
        int idx = (light.effect ?: 0) as Integer
        state.effectActive = true
        state.effectIndex = idx
        sendEvt("colorMode", "EFFECTS")
        List effects = (state.effects ?: []) as List
        if (idx < effects.size()) {
            Map eff = effects[idx] as Map
            sendEvt("effectName", eff.effectName as String)
            sendEvt("colorName", eff.effectName as String)
            def lum = ((eff.member ?: []) as List)?.find()?.luminance
            if (lum != null && (lum as Integer) > 0) sendEvt("level", clamp(lum as Integer, 1, 100), "%")
        } else if (now() - ((state.effectsFetched ?: 0) as long) > 600000L) {
            // Unknown effect (e.g. a new one made in the app): reload the list
            state.effectsFetched = now()
            refreshEffects()
        }
        return
    }
    state.effectActive = false
    sendEvt("effectName", "none")

    if (light.luminance != null && (light.luminance as Integer) > 0) {
        int lum = clamp(light.luminance as Integer, 1, 100)
        state.luminance = lum
        sendEvt("level", lum, "%")
    }

    if ((capacity & CAP_TEMP) && !(capacity & CAP_RGB)) {
        state.mode = "CT"
        sendEvt("colorMode", "CT")
        if (light.temperature != null) {
            state.temperature = light.temperature as Integer
            sendEvt("colorTemperature", merossToKelvin(light.temperature as Integer), "K")
            sendEvt("colorName", whiteName(merossToKelvin(light.temperature as Integer)))
        }
    } else if (capacity & CAP_RGB) {
        if (state.ctEmulated && light.rgb != null && (light.rgb as Integer) == (state.rgb as Integer)) {
            // Still showing the RGB-mixed white we set: keep reporting it as color temperature
            state.mode = "CT"
            sendEvt("colorMode", "CT")
            if (state.ctKelvin) {
                sendEvt("colorTemperature", state.ctKelvin as Integer, "K")
                sendEvt("colorName", whiteName(state.ctKelvin as Integer))
            }
            return
        }
        state.ctEmulated = false
        state.mode = "RGB"
        sendEvt("colorMode", "RGB")
        if (light.rgb != null) {
            int rgb = light.rgb as Integer
            state.rgb = rgb
            List hsv = ColorUtils.rgbToHSV([(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF])
            sendEvt("hue", Math.round(hsv[0] as double) as Integer)
            sendEvt("saturation", Math.round(hsv[1] as double) as Integer, "%")
            sendEvt("colorName", colorNameFor(hsv[0] as double, hsv[1] as double))
        }
    }
}

/* ---------- utilities ---------- */

// Shown in the device's Data section so users can tell which version is installed
private void recordVersion() {
    if (getDataValue("driverVersion") != DRIVER_VERSION) updateDataValue("driverVersion", DRIVER_VERSION)
}

// Plain-English name for a hue/saturation pair (Hubitat 0-100 scale)
private static String colorNameFor(double hue, double sat) {
    if (sat < 15) return "White"
    if ((hue >= 84 && hue < 96) || ((hue >= 96 || hue < 4) && sat < 70)) return "Pink"
    if (hue < 4 || hue >= 96) return "Red"
    if (hue < 12) return "Orange"
    if (hue < 22) return "Yellow"
    if (hue < 42) return "Green"
    if (hue < 58) return "Cyan"
    if (hue < 72) return "Blue"
    return "Purple"
}

private static String whiteName(int kelvin) {
    if (kelvin <= 3300) return "Warm White"
    if (kelvin <= 5000) return "Cool White"
    return "Daylight"
}

private void sendEvt(String name, value, String unit = null) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        String text = "${device.displayName} ${name} is ${value}${unit ?: ''}"
        if (txtEnable != false) log.info text
        Map evt = [name: name, value: value, descriptionText: text]
        if (unit) evt.unit = unit
        sendEvent(evt)
    }
}

private void setHealth(String status) {
    if (device.currentValue("healthStatus") != status) {
        if (status == "offline") log.warn "${device.displayName} is offline"
        else if (txtEnable != false) log.info "${device.displayName} is online"
        sendEvent(name: "healthStatus", value: status)
    }
}

private static String md5(String s) {
    return MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).encodeHex().toString()
}

private static int clamp(Integer v, int lo, int hi) {
    if (v == null) return lo
    return Math.max(lo, Math.min(hi, v))
}

// Meross color temperature is a 1-100 scale (warm -> cool)
private static int kelvinToMeross(int kelvin) {
    double pct = (kelvin - KELVIN_MIN) / (double) (KELVIN_MAX - KELVIN_MIN)
    return Math.max(1, Math.min(100, (int) Math.round(pct * 99) + 1))
}

// Approximate RGB value for a white point in kelvin (Tanner Helland's curve fit)
private static int kelvinToRgb(int kelvin) {
    double t = kelvin / 100.0d
    double r
    double g
    double b
    if (t <= 66) {
        r = 255
        g = 99.4708025861d * Math.log(t) - 161.1195681661d
    } else {
        r = 329.698727446d * Math.pow(t - 60, -0.1332047592d)
        g = 288.1221695283d * Math.pow(t - 60, -0.0755148492d)
    }
    if (t >= 66) {
        b = 255
    } else if (t <= 19) {
        b = 0
    } else {
        b = 138.5177312231d * Math.log(t - 10) - 305.0447927307d
    }
    int ri = Math.max(0, Math.min(255, (int) Math.round(r)))
    int gi = Math.max(0, Math.min(255, (int) Math.round(g)))
    int bi = Math.max(0, Math.min(255, (int) Math.round(b)))
    return (ri << 16) | (gi << 8) | bi
}

private static int merossToKelvin(int t) {
    int v = Math.max(1, Math.min(100, t))
    double k = KELVIN_MIN + ((v - 1) / 99.0d) * (KELVIN_MAX - KELVIN_MIN)
    return ((int) Math.round(k / 50.0d)) * 50
}
