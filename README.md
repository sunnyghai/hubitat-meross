# Meross Light Strips for Hubitat

By [Sunny Ghai](https://github.com/sunnyghai), created with AI assistance. Source and updates: [github.com/sunnyghai/hubitat-meross](https://github.com/sunnyghai/hubitat-meross)

Two Hubitat Elevation drivers that add Meross Wi-Fi LED light strips (MSL320 family) to your hub. You sign in once with your Meross account; the strips are then found on your network and added automatically. No IP addresses or device keys to type in, and no Hubitat app to install.

> ⚠️ **BETA — use at your own risk.** These drivers are in beta. They are provided as-is, with **no support and no warranty** of any kind. They are community-created and unofficial, and are not affiliated with, endorsed by or supported by Meross or Hubitat. So far they have been tested mainly against a simulated Meross service and network, not widely on real hubs and strips.
>
> **Community help wanted:** if you try them, please share what works, what doesn't, and any fixes on [GitHub Issues](https://github.com/sunnyghai/hubitat-meross/issues). See [Help improve these drivers](#help-improve-these-drivers).

## What you get

- On/off, brightness, RGB color and color temperature for each strip
- The strip's built-in effects (Party, Night, Reading, Relaxing, Colorful…) picked by name from a drop-down, plus any custom effects you made in the Meross app
- Works with Hubitat dashboards, Rule Machine, Room Lighting and voice assistants linked to the hub
- Local control: commands go from the hub straight to the strip over your Wi-Fi
- Automatic recovery if a strip's IP address changes

Not included: creating or editing effects (do that in the Meross app), per-segment control, and instant status updates (status is polled; see [Good to know](#good-to-know)).

## The two files

| File | Driver name in Hubitat | What it does |
|---|---|---|
| `meross-account.groovy` | Meross Account | Signs in to Meross, gets your device list, finds the strips on your network, creates a device for each |
| `meross-light-strip.groovy` | Meross Light Strip (Local) | Controls one strip |

**Both are required.** The account driver creates the strip devices using the strip driver, so discovery fails if the strip driver isn't installed.

## Before you start

- A Hubitat Elevation hub
- Each strip already set up and working in the Meross phone app
- Your Meross account email and password
- Hub and strips on the same network (same first three numbers of the IP address, e.g. `192.168.1.x`)

## Installation

### 1. Install the driver code

Do this once for each file. The easiest way is to import it straight from GitHub:

1. In the Hubitat web interface, open **Drivers Code** (under *Developer tools* or *For Developers*, depending on your hub's version).
2. Click **New Driver**, then **Import**.
3. Paste the file's link and click **Import**:
   - `https://raw.githubusercontent.com/sunnyghai/hubitat-meross/main/meross-account.groovy`
   - `https://raw.githubusercontent.com/sunnyghai/hubitat-meross/main/meross-light-strip.groovy`
4. Click **Save**.

Alternatively, open the file on GitHub, click **Raw**, copy everything, and paste it into a new driver.

### 2. Create the account device

1. Go to **Devices → Add Device → Virtual**.
2. Give it a name, such as `Meross Account`.
3. For **Type**, choose **Meross Account** (user drivers are at the bottom of the list).
4. Click **Save Device**.

You only need one account device, however many strips you have.

### 3. Sign in and discover

1. On the account device's page, enter your **Meross account email** and **password**.
2. Click **Save Preferences**.
3. Click **Discover Devices**.
4. Watch the **status** value under *Current States*. It takes roughly 20–30 seconds and should finish at:
   `Ready: N strip(s) found on the network`

Each strip now appears as its own device, listed under the account device and named as it is in the Meross app. Open one and try **On**, **Off**, **Set Level** and **Select Color**.

## Settings

### Account device

| Setting | Use |
|---|---|
| Meross account email / password | Your Meross app sign-in |
| Account region | Americas, Europe or Asia-Pacific. A wrong choice is corrected automatically |
| Two-step verification code | Only if your Meross account uses two-step verification (see below) |
| Device types to add | Model prefixes to import, comma-separated. Default `msl320` |
| Network to scan | Leave blank to use the hub's network. Otherwise the first three numbers, e.g. `192.168.20` |
| Enable debug logging | Extra detail in Logs; turns itself off after 30 minutes |

Commands:

- **Discover Devices** signs in, refreshes the device list, adds any new strips and scans for them. Run it again after adding a strip in the Meross app.
- **Scan Network** looks for the already-known strips again without signing in.

### Strip devices

| Setting | Use |
|---|---|
| Poll interval | How quickly changes made in the Meross app, Apple Home or by voice show up in Hubitat. Default 1 minute. Commands sent from Hubitat update right away |
| Color temperature method | *Mix white from RGB* for the standard MSL320 (RGB only). *Dedicated white LEDs* for the MSL320 Pro (RGBWW) |
| Fade between colors/levels | On: the strip fades smoothly to each new color or brightness. Off: it changes instantly |
| Device IP address / Meross device key (manual override) | Leave both blank. They are filled in automatically |
| Logging options | Description text (a line in Logs for each change) and debug logging (extra detail for troubleshooting) |

### Everyday controls

| Command | What it does |
|---|---|
| **On** / **Off** | Turns the strip on or off. On returns to the last color, white or effect |
| **Set Level** | Brightness from 1 (dimmest) to 100 (brightest). 0 turns the strip off. Also dims a running effect |
| **Select Color** (drop-down) | The easiest way to pick a color: Red, Orange, Yellow, Green, Cyan, Blue, Purple, Pink, or a white (Warm White 2700 K, Cool White 4000 K, Daylight 6500 K). Optional brightness box |
| **Set Color** | Hubitat's color picker, for any exact color |
| **Set Hue** / **Set Saturation** | Fine-tune a color. Hue runs 0-100 around the color wheel (0 red, 17 yellow, 33 green, 50 cyan, 67 blue, 78 purple, 92 pink). Saturation runs from 0 (white) to 100 (full, vivid color) |
| **Set Color Temperature** | Any white from 2700 to 6500 K (see below) |
| **Refresh** | Reads the strip's current state right away, and reloads its effect list (e.g. after making a new effect in the Meross app) |

The `colorName` attribute shows the current color in plain words, e.g. `Blue`, `Warm White`, or the name of a running effect.

### White light (Set Color Temperature)

Use **Set Color Temperature** on a strip's device page to switch it to white light.

| Box | What to enter |
|---|---|
| **White light color (2700-6500 K)** (required) | A number from **2700** (warm) to **6500** (cool daylight). Anything outside that range is limited to it, so `34` becomes `2700` |
| **Brightness (1-100 %)** | Optional. Leave blank to keep the current brightness. `0` turns the strip off |

There's no transition-time box: Meross strips can't fade over a set time. Fading is switched on or off with **Fade between colors/levels** in Preferences. (Rules and voice assistants that send a transition time still work; it's simply ignored.)

| Kelvin | Looks like |
|---|---|
| 2700 | Warm, like an incandescent bulb |
| 3000 | Soft white |
| 4000 | Neutral white |
| 5000 | Bright, daylight-ish |
| 6500 | Cool daylight |

Example: for a cozy evening, enter **2700** with brightness **40**, or use **Select Color → Warm White**.

Setting a color temperature stops any running effect. Afterwards `colorMode` shows `CT` and `colorTemperature` shows the value used. On the standard MSL320 the white is mixed from red, green and blue, so it's approximate. On the MSL320 Pro, set **Color temperature method** to *Dedicated white LEDs* for true white.

### Effects (including Party)

Each strip device has these extra commands:

| Command | What it does |
|---|---|
| **Select Effect** (drop-down) | Pick a built-in effect by name: Night (dim warm glow), Reading (bright warm white), Working (bright cool white), Relaxing (slow color fade), Party (fast color changes), Colorful (color cycle). Turns the strip on if needed. **Off** stops the effect and goes back to the color or white you had before |
| **Set Effect** (number) | Starts any effect by number, including custom effects made in the Meross app. The numbers are listed in the `lightEffects` attribute |
| **Set Next Effect** / **Set Previous Effect** | Cycles through the effects |

While an effect is running:

- `colorMode` shows `EFFECTS` and `effectName` shows its name (`none` when no effect is running).
- **Set Level** dims or brightens the effect without stopping it.
- **Select Color**, **Set Color** or **Set Color Temperature** stops the effect and switches to that color.
- **Off** turns the strip off; **On** resumes the effect.

In Rule Machine, use **Run Custom Action → selectEffect** with a string parameter such as `Party` or `Off`, and trigger rules on the `effectName` attribute (e.g. *effectName is Party*). Dashboards and other apps that support Hubitat's standard light-effects controls can pick effects too.

### Two-step verification

If your Meross account has it turned on, the status will ask for a code:

1. Get a fresh code from your authenticator.
2. Enter it in **Two-step verification code** and click **Save Preferences**.
3. Click **Discover Devices** straight away, before the code expires.

The code is cleared after use.

## Good to know

- **Status is polled.** Changes made in the Meross app, Apple Home or by voice through Meross show up in Hubitat at the next poll (within a minute by default), not instantly. Commands sent from Hubitat update right away.
- **IP changes fix themselves.** If a strip stops answering three polls in a row, the account device rescans the network, at most once every 15 minutes. A DHCP reservation is optional, though it avoids that short gap.
- **After a hub restart** the account device rescans automatically.
- **White on the standard MSL320 is approximate.** That model has no white LEDs, so color temperature is mixed from red, green and blue.

## Privacy and security

- The Meross cloud is contacted **only** when you run Discover Devices, to fetch your device list and your account's device key. The driver signs out again immediately afterwards.
- Your Meross password is stored on your hub, in the account device's preferences. The device key is kept in the account device's state. Neither is sent anywhere except to Meross at sign-in.
- Finding the strips works by sending one small request to each of the 254 addresses on the scanned network. Other devices simply ignore it.
- Anyone with admin access to your hub can see these settings, so treat hub access accordingly.

## Troubleshooting

Start with the **status** value on the account device and the **Logs** page. Most problems fall into one of the groups below. The [quick reference](#quick-reference) at the end maps specific messages to fixes.

### Can't sign in

Status shows `Sign-in failed: wrong email or password`, or sign-in never completes.

1. **Check the email and password in the Meross app.** Sign in to the Meross app on another phone or tablet with exactly the same email and password. If that fails too, the details are wrong. Use **Forgot password** in the Meross app to reset the password, then enter the new one in Hubitat.
2. **Retype the password.** Pasting can add a hidden space at the start or end, and that makes the password wrong. Type it by hand, click **Save Preferences**, then **Discover Devices**.
3. **Use the account that owns the strips.** If someone else in the household set the strips up, or they were shared to you from another account, sign in with the account that added them in the Meross app.
4. **Accounts made with a social sign-in.** If you created your Meross account through a "Sign in with Apple/Google"-style button, it may have no password of its own. Use **Forgot password** to set one.
5. **Two-step verification.** If it's on, the status asks for a code. Follow [Two-step verification](#two-step-verification), entering a fresh code and running **Discover Devices** right away.
6. **Region.** A wrong **Account region** is normally corrected automatically. If sign-in still fails, try the region where you created the account.
7. **Too many attempts.** After repeated failures Meross may block sign-ins for a while (`too many attempts` or `too many active sign-ins`). Stop retrying and wait: an hour for the first message, a few hours for the second.
8. **Hub internet access.** Signing in needs the hub to reach the internet. Check that other cloud-based Hubitat features work, and that no firewall or DNS filter (e.g. Pi-hole) blocks `meross.com`.

### Strips not found on the network

Status shows `Found 0 of 2…` or `Found 1 of 2. Not found on 192.168.1.x: <name>`.

1. **Check the strip works in the Meross app**, with your phone on the same home Wi-Fi. If the app shows it offline, fix that first: power it off and on at the plug, and wait a minute for it to reconnect.
2. **Test the Wi-Fi signal at the strip.** A weak signal is the most common cause of a strip that can't be found or keeps dropping out.
   - Stand next to the strip's controller with your phone. If Wi-Fi shows only one or two bars, or a speed test there is slow, the strip probably has a weak signal too.
   - Many routers and mesh apps list each connected device with its signal strength. Find the strip (often named after its model or "Meross") and look for at least *fair*. As a guide, -67 dBm or stronger is good, while -75 dBm or weaker is unreliable.
   - To improve it: move the router or a mesh point closer, add an extender, or move the controller away from metal (fridges, appliances, metal shelving and channels).
3. **2.4 GHz only.** The strips connect only to 2.4 GHz Wi-Fi. That's fine on most dual-band routers. If you split your bands into separate network names, the strip must stay on the 2.4 GHz one.
4. **Same network as the hub.** The hub can only find strips on its own network: same first three numbers of the IP address, e.g. `192.168.1.x`. Strips on a **guest network**, a separate **IoT VLAN**, or behind **client/AP isolation** can't be reached.
   - Move the strips to your main network, allow traffic between the networks, or
   - If they are on another network the hub can reach, enter its first three numbers in **Network to scan** on the account device.
5. **Run Scan Network again** after any change. It takes about 20–30 seconds.
6. **Check the model filter.** If the status says `no devices matching`, your strips may report a different model code. Check the model in the Meross app and adjust **Device types to add**.
7. **Last resort: enter the IP by hand.** Find the strip's IP address in your router's device list, give it a DHCP reservation, and enter it in **Device IP address (manual override)** on the strip device. The account device still supplies the key.

### Strip goes offline or responds slowly

Usually Wi-Fi signal or power:

- Run through the **Wi-Fi signal** checks in step 2 above.
- Check the power adapter and connections. A loose connector can make the controller restart.
- After a router restart or firmware update, strips may come back with new IP addresses. The account device rescans by itself (at most every 15 minutes); click **Scan Network** to do it immediately.
- If commands work but Hubitat shows old state, remember status is polled. Lower **Poll interval** or click **Refresh**.

### Commands do nothing or colors look wrong

- **Log shows `rejected the message signature (error 5001)`.** The strip was re-paired or moved to another Meross account. Run **Discover Devices** to fetch the current key.
- **White looks tinted** on a standard MSL320. That's expected: white is mixed from red, green and blue. On an MSL320 Pro, set **Color temperature method** to *Dedicated white LEDs*.
- **An effect won't start.** Click **Refresh** to reload the strip's effect list, then try again.

### Quick reference

| What you see | Likely cause | What to do |
|---|---|---|
| `Sign-in failed: wrong email or password` | Typo, or a different account than the app uses | Re-enter both and save. Confirm they work in the Meross app |
| `Sign-in needs a two-step verification code` | Two-step verification is on | Follow [Two-step verification](#two-step-verification) |
| `Sign-in refused: too many attempts` / `too many active sign-ins` | Meross is rate-limiting the account | Wait (a few hours for the second message) before trying again |
| `Could not create the strip devices - install the 'Meross Light Strip (Local)' driver code` | Strip driver missing | Install `meross-light-strip.groovy`, then run Discover Devices again |
| `Signed in, but no devices matching 'msl320'...` | Strips are a different model, or on another account | Check the model in the Meross app and adjust **Device types to add** |
| `Found 1 of 2. Not found on 192.168.1.x: <name>` | Strip is unplugged, off Wi-Fi, or on another network/VLAN | Power it up and run **Scan Network**; set **Network to scan** if it's on a different network |
| Strip device shows `healthStatus: offline` | Strip unreachable | Check power and Wi-Fi. It recovers on its own once reachable |
| Log shows `rejected the message signature (error 5001)` | Strip was re-paired or moved to another Meross account | Run **Discover Devices** again to refresh the key |
| Select Effect logs `this strip has no Party effect` (or another name) | That effect was deleted in the Meross app | Re-add it in the app and click **Refresh**, or pick another effect with **Set Effect** |
| On/off works but color temperature does nothing | Wrong color temperature method for the model | Change **Color temperature method** on the strip device |

### Still stuck?

Turn on **Enable debug logging** on both the account device and the strip device, repeat what failed, and check **Logs**. If you can't solve it, open an issue on GitHub as described in [Help improve these drivers](#help-improve-these-drivers).

## Help improve these drivers

These drivers are a beta, and community testing, debugging and improvements are very welcome. Reports from real MSL320 and MSL320 Pro strips are especially useful.

- **Problems and test results:** open an issue at [github.com/sunnyghai/hubitat-meross/issues](https://github.com/sunnyghai/hubitat-meross/issues)
- **Code fixes and improvements:** open a pull request at [github.com/sunnyghai/hubitat-meross/pulls](https://github.com/sunnyghai/hubitat-meross/pulls)

When reporting a problem, please include:

- Your strip model (shown in the strip device's **Data** section as `model`) and its firmware version (`firmware`)
- Your Hubitat platform version
- What you did, what you expected, and what happened
- The log lines from the attempt, with **Enable debug logging** turned on for both the account device and the strip device

Before posting logs, check them for anything private. The drivers never log your password, but logs can include your strips' IP addresses and device names.

Fixes and improvements to the code are welcome too. Please describe what you changed and how you tested it.

## Updating

Open the driver in **Drivers Code**, click **Import** (the driver remembers its GitHub link) or paste in the new version, then click **Save**. Existing devices and settings are kept. Each device's **Data** section shows the installed `driverVersion`.

## Removing

Delete the strip devices, then the account device, then remove both drivers from **Drivers Code**. Hubitat won't let you delete a driver that is still in use.

## Optional: using a strip without the account driver

`meross-light-strip.groovy` also works on its own. Create a virtual device with type **Meross Light Strip (Local)** and fill in **Device IP address** and **Meross device key** by hand. Give the strip a DHCP reservation in this mode, since nothing will re-find it if its address changes.

## Disclaimer

This software is beta and is provided "as is", without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose and non-infringement. No support is provided. You use it entirely at your own risk. The authors and contributors are not liable for any claim, damage or other liability arising from its use, including any effect on your devices, your hub, your network or your Meross account.

Meross and Hubitat are trademarks of their respective owners. This project is not affiliated with or endorsed by either company.

## Credits

Created by [Sunny Ghai](https://github.com/sunnyghai) with AI assistance. The Meross local and cloud message formats were worked out with reference to the open-source community projects [meross_lan](https://github.com/krahabb/meross_lan) and [MerossIot](https://github.com/albertogeniola/MerossIot).
