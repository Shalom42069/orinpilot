package de.marlon.orinpilot.data

import de.marlon.orinpilot.data.Parsers.q

/**
 * Alle Shell-Befehle, die die App auf dem Jetson ausführt, an einer Stelle.
 * Hinweis: In Kotlin-Strings wird ein Dollar vor Buchstaben/{ als ${'$'} geschrieben.
 */
object Scripts {
    private const val D = "$"

    val SYSINFO = listOf(
        "echo HOST=$(hostname)",
        "echo MODEL=$(tr -d '\\0' < /proc/device-tree/model 2>/dev/null)",
        "echo OS=$(. /etc/os-release 2>/dev/null; echo ${D}PRETTY_NAME)",
        "echo KERNEL=$(uname -r)",
        "echo L4T=$(head -n 1 /etc/nv_tegra_release 2>/dev/null)",
        "echo JETPACK=$(dpkg-query --showformat='${D}{Version}' --show nvidia-jetpack 2>/dev/null)",
        "echo UPTIME=$(uptime -p)",
        "echo IP=$(hostname -I 2>/dev/null)",
        "echo DISK=$(df -h / | awk 'NR==2{print ${D}3\"/\"${D}2\" (\"${D}5\")\"}')",
        "echo TARGET=$(systemctl get-default)",
        "echo LOAD=$(cut -d' ' -f1-3 /proc/loadavg)",
    ).joinToString("; ")

    const val TEGRASTATS = "tegrastats --interval 1000 2>&1 || /usr/bin/tegrastats --interval 1000 2>&1"

    const val PS = "ps -eo pid=,user=,pcpu=,pmem=,rss=,comm= --sort=-pcpu | head -n 120"

    const val SERVICES =
        "systemctl list-units --type=service --all --no-pager --plain --no-legend; echo '###'; " +
            "systemctl list-unit-files --type=service --no-pager --no-legend"

    const val DOCKER_PS = "docker ps -a --format '{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}'"

    const val NVP_CONF = "cat /etc/nvpmodel.conf 2>/dev/null"
    const val NVP_QUERY = "nvpmodel -q 2>/dev/null"
    fun nvpSet(id: Int) = "nvpmodel -m $id"

    const val JC_SHOW = "jetson_clocks --show 2>&1 | head -n 40"
    const val JC_ON = "[ -f /tmp/orinpilot_jc ] || jetson_clocks --store /tmp/orinpilot_jc; jetson_clocks"
    const val JC_OFF = "if [ -f /tmp/orinpilot_jc ]; then jetson_clocks --restore /tmp/orinpilot_jc; else echo 'Kein gespeicherter Zustand - Neustart setzt die Takte zurück.'; fi"

    /** liefert das hwmon-Verzeichnis des PWM-Lüfters */
    private const val FAN_DIR =
        "FAN=; for d in /sys/class/hwmon/hwmon*; do [ -f \"${D}d/pwm1\" ] && FAN=${D}d && break; done"

    val FAN_STATUS = listOf(
        FAN_DIR,
        "echo PWM=$(cat ${D}FAN/pwm1 2>/dev/null)",
        "echo RPM=$(cat /sys/class/hwmon/hwmon*/rpm 2>/dev/null | head -n 1)",
        "echo PROFILE=$(grep -E '^\\s*FAN_DEFAULT_PROFILE' /etc/nvfancontrol.conf 2>/dev/null | awk '{print ${D}2}')",
        "echo SERVICE=$(systemctl is-active nvfancontrol 2>/dev/null)",
    ).joinToString("; ")

    fun fanProfile(profile: String) = listOf(
        "systemctl stop nvfancontrol",
        "sed -i -E 's/^(\\s*FAN_DEFAULT_PROFILE\\s+).*/\\1$profile/' /etc/nvfancontrol.conf",
        "rm -f /var/lib/nvfancontrol/status",
        "systemctl start nvfancontrol",
        "echo Profil: $profile",
    ).joinToString(" && ")

    fun fanManual(pwm: Int) = listOf(
        FAN_DIR,
        "systemctl stop nvfancontrol",
        "echo ${pwm.coerceIn(0, 255)} > ${D}FAN/pwm1",
        "echo PWM=$(cat ${D}FAN/pwm1)",
    ).joinToString("; ")

    const val FAN_AUTO = "systemctl start nvfancontrol && echo Automatik aktiv"

    const val GET_TARGET = "systemctl get-default"
    const val HEADLESS_ON = "systemctl set-default multi-user.target && systemctl isolate multi-user.target && echo 'Desktop gestoppt – Jetson läuft headless'"
    const val HEADLESS_OFF = "systemctl set-default graphical.target && systemctl isolate graphical.target && echo 'Desktop gestartet'"

    const val REBOOT = "systemctl reboot"
    const val POWEROFF = "systemctl poweroff"

    const val WIFI_LIST = "nmcli -t -f IN-USE,SSID,SIGNAL,SECURITY dev wifi list --rescan auto 2>&1"
    const val NET_ACTIVE = "nmcli -t -f NAME,TYPE,DEVICE con show --active 2>&1; echo; ip -br addr 2>/dev/null"
    fun wifiConnect(ssid: String, password: String) =
        if (password.isBlank()) "nmcli dev wifi connect ${q(ssid)} 2>&1"
        else "nmcli dev wifi connect ${q(ssid)} password ${q(password)} 2>&1"

    fun kill(pid: Int, force: Boolean) = "kill ${if (force) "-9 " else ""}$pid"

    fun service(action: String, unit: String) = "systemctl $action ${q(unit)} 2>&1"
    fun journal(unit: String) = "journalctl -u ${q(unit)} -n 150 --no-pager 2>&1"

    fun docker(action: String, id: String) = "docker $action ${q(id)} 2>&1"
    fun dockerLogs(id: String) = "docker logs --tail 200 ${q(id)} 2>&1"

    fun rmRecursive(path: String) = "rm -rf -- ${q(path)}"

    fun authorizeKey(pubKey: String) = listOf(
        "mkdir -p ~/.ssh",
        "chmod 700 ~/.ssh",
        "touch ~/.ssh/authorized_keys",
        "chmod 600 ~/.ssh/authorized_keys",
        "grep -qxF ${q(pubKey)} ~/.ssh/authorized_keys || echo ${q(pubKey)} >> ~/.ssh/authorized_keys",
        "echo OK",
    ).joinToString(" && ")

    // ---------------------------------------------------------------- Fernzugriff (Tailscale)

    private const val TS_JSON = "tailscale status --self --peers=false --json 2>/dev/null"
    private fun jsonField(name: String) =
        "grep -m1 '\"$name\"' | sed -E 's/.*\"$name\": *\"([^\"]*)\".*/\\1/'"

    val TS_STATUS = listOf(
        "if command -v tailscale >/dev/null 2>&1; then echo INSTALLED=yes; else echo INSTALLED=no; fi",
        "echo SERVICE=$(systemctl is-active tailscaled 2>/dev/null)",
        "echo IP=$(tailscale ip -4 2>/dev/null | head -n 1)",
        "echo STATE=$($TS_JSON | ${jsonField("BackendState")})",
        "echo AUTHURL=$($TS_JSON | ${jsonField("AuthURL")}; grep -o 'https://login.tailscale.com/[^ ]*' /tmp/orinpilot_ts.log 2>/dev/null | tail -n 1)",
        "echo DNS=$($TS_JSON | ${jsonField("DNSName")} | sed 's/\\.${D}//')",
    ).joinToString("; ")

    /** offizielles Installationsskript (braucht Internet auf dem Jetson) */
    const val TS_INSTALL =
        "curl -fsSL https://tailscale.com/install.sh | sh 2>&1 | tail -n 25 && systemctl enable --now tailscaled 2>&1 && echo INSTALL_OK"

    /** startet die Anmeldung im Hintergrund; der Login-Link landet im Log/Status */
    const val TS_UP_INTERACTIVE =
        "systemctl enable --now tailscaled >/dev/null 2>&1; rm -f /tmp/orinpilot_ts.log; " +
            "setsid nohup tailscale up --reset > /tmp/orinpilot_ts.log 2>&1 < /dev/null & sleep 4; cat /tmp/orinpilot_ts.log"

    fun tsUpWithKey(key: String) =
        "systemctl enable --now tailscaled >/dev/null 2>&1; tailscale up --reset --authkey=${q(key)} 2>&1 && echo UP_OK"

    const val TS_DOWN = "tailscale down 2>&1 && echo DOWN_OK"
}
