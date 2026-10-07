package de.marlon.orinpilot.data

import de.marlon.orinpilot.data.Parsers.q

/** Befehle für Ollama */
object OllamaScripts {
    private const val D = "$"

    val STATUS = listOf(
        "if command -v ollama >/dev/null 2>&1; then echo INSTALLED=yes; else echo INSTALLED=no; fi",
        "echo VERSION=$(ollama -v 2>/dev/null | tail -n 1)",
        "echo ACTIVE=$(systemctl is-active ollama 2>/dev/null)",
        "echo ENABLED=$(systemctl is-enabled ollama 2>/dev/null)",
        "echo ENV=$(systemctl show ollama -p Environment --value 2>/dev/null)",
        "M=$(systemctl show ollama -p Environment --value 2>/dev/null | grep -o 'OLLAMA_MODELS=[^ ]*' | cut -d= -f2); " +
            "[ -z \"${D}M\" ] && M=/usr/share/ollama/.ollama/models; echo MODELS=${D}M",
        "echo FREE=$(df -h \"${D}M\" 2>/dev/null | awk 'NR==2{print ${D}4\" frei von \"${D}2}')",
    ).joinToString("; ")

    /** Welche Rechenhardware hat Ollama beim Start erkannt? (braucht Journal-Zugriff) */
    const val GPU_INFO =
        "journalctl -u ollama -b --no-pager 2>/dev/null | grep -iE 'inference compute|library=|offloaded|no compatible GPUs' | tail -n 6"

    /** offizielles Installationsskript – unterstützt JetPack 5/6 (CUDA-Variante wird automatisch gewählt) */
    const val INSTALL = "curl -fsSL https://ollama.com/install.sh | sh && systemctl enable --now ollama && echo 'Ollama installiert.'"

    fun service(action: String) = "systemctl $action ollama && systemctl is-active ollama"

    const val LOGS = "journalctl -u ollama -n 200 --no-pager"

    fun rm(model: String) = "ollama rm ${q(model)}"

    /** schreibt die Umgebungsvariablen als systemd-Override und startet den Dienst neu */
    fun applyEnv(lines: List<String>): String {
        val file = "/etc/systemd/system/ollama.service.d/orinpilot.conf"
        val content = (listOf("[Service]") + lines.map { "Environment=\"$it\"" }).joinToString("\n")
        return listOf(
            "mkdir -p /etc/systemd/system/ollama.service.d",
            "printf '%s\\n' ${q(content)} > $file",
            "systemctl daemon-reload",
            "systemctl restart ollama",
            "sleep 2",
            "echo 'Gespeichert:'; cat $file",
            "systemctl is-active ollama",
        ).joinToString(" && ")
    }

    /** Modellordner auf eine andere Platte (z. B. NVMe) verlegen */
    fun moveModels(target: String) = listOf(
        "set -e",
        "T=${q(target)}",
        "mkdir -p \"${D}T\"",
        "systemctl stop ollama",
        "SRC=/usr/share/ollama/.ollama/models",
        "if [ -d \"${D}SRC\" ] && [ \"$(ls -A ${D}SRC 2>/dev/null)\" ]; then echo 'Kopiere Modelle …'; cp -a \"${D}SRC/.\" \"${D}T/\"; fi",
        "chown -R ollama:ollama \"${D}T\" 2>/dev/null || true",
        "mkdir -p /etc/systemd/system/ollama.service.d",
        "printf '[Service]\\nEnvironment=\"OLLAMA_MODELS=%s\"\\n' \"${D}T\" > /etc/systemd/system/ollama.service.d/models.conf",
        "systemctl daemon-reload",
        "systemctl start ollama",
        "echo \"Modelle liegen jetzt in ${D}T (alte Kopie bleibt erhalten).\"",
    ).joinToString("\n")
}

/** klassische Jetson-Funktionen */
object JetsonScripts {
    private const val D = "$"

    val INVENTORY = listOf(
        "echo MODEL=$(tr -d '\\0' < /proc/device-tree/model 2>/dev/null)",
        "echo SERIAL=$(tr -d '\\0' < /proc/device-tree/serial-number 2>/dev/null)",
        "echo MODULE=$(tr '\\0' ' ' < /proc/device-tree/compatible 2>/dev/null | awk '{print ${D}1}')",
        "echo L4T=$(head -n 1 /etc/nv_tegra_release 2>/dev/null)",
        "echo JETPACK=$(dpkg-query -W -f='${D}{Version}' nvidia-jetpack 2>/dev/null)",
        "echo CUDA=$( (/usr/local/cuda/bin/nvcc --version 2>/dev/null | grep -o 'release [0-9.]*' | cut -d' ' -f2) || true)",
        "echo CUDNN=$(dpkg-query -W -f='${D}{Version}' libcudnn9-cuda-12 libcudnn8 2>/dev/null | head -c 40)",
        "echo TENSORRT=$(dpkg-query -W -f='${D}{Version}' tensorrt libnvinfer10 libnvinfer8 2>/dev/null | head -c 40)",
        "echo VPI=$(dpkg-query -W -f='${D}{Package} ' 'libnvvpi*' 2>/dev/null | grep -o 'libnvvpi[0-9]*' | head -n 1)",
        "echo OPENCV=$(dpkg-query -W -f='${D}{Version}' libopencv libopencv-dev 2>/dev/null | head -c 30)",
        "echo PYTHON=$(python3 --version 2>/dev/null | cut -d' ' -f2)",
        "echo DOCKER=$(docker --version 2>/dev/null | cut -d' ' -f3 | tr -d ,)",
        "echo NVRUNTIME=$(grep -q nvidia /etc/docker/daemon.json 2>/dev/null && echo ja || echo nein)",
        "echo JTOP=$(command -v jtop >/dev/null 2>&1 && echo installiert || echo fehlt)",
        "echo BOOTDEV=$(findmnt -n -o SOURCE /)",
        "echo NVME=$(lsblk -dn -o NAME,SIZE,MODEL 2>/dev/null | grep -i nvme | head -n 1)",
    ).joinToString("; ")

    const val STORAGE = "lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT,MODEL 2>/dev/null; echo; df -h -x tmpfs -x devtmpfs -x squashfs 2>/dev/null"

    const val TORCH_CHECK =
        "python3 -c \"import torch;print('PyTorch', torch.__version__);print('CUDA verfügbar:', torch.cuda.is_available());" +
            "print('Gerät:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else '-')\" 2>&1 | tail -n 5"

    const val CUDA_SAMPLE =
        "if [ -x /usr/local/cuda/extras/demo_suite/deviceQuery ]; then /usr/local/cuda/extras/demo_suite/deviceQuery; " +
            "else echo 'deviceQuery nicht vorhanden – nvidia-jetpack bzw. cuda-toolkit installieren.'; fi"

    // ---------------------------------------------------------------- Swap / zram

    const val SWAP_STATUS =
        "echo '== Swap =='; swapon --show 2>/dev/null; echo; echo '== Speicher =='; free -h; echo; " +
            "echo ZRAM=$(systemctl is-enabled nvzramconfig 2>/dev/null); echo SWAPFILE=$( [ -f /swapfile ] && du -h /swapfile | cut -f1 || echo keine)"

    fun createSwapfile(gb: Int) = listOf(
        "set -e",
        "if swapon --show=NAME --noheadings | grep -qx /swapfile; then swapoff /swapfile; fi",
        "rm -f /swapfile",
        "echo 'Lege /swapfile mit ${gb} GB an …'",
        "fallocate -l ${gb}G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=${gb * 1024} status=progress",
        "chmod 600 /swapfile",
        "mkswap /swapfile",
        "swapon /swapfile",
        "grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab",
        "swapon --show",
    ).joinToString("\n")

    const val REMOVE_SWAPFILE =
        "swapoff /swapfile 2>/dev/null; sed -i '\\#^/swapfile#d' /etc/fstab; rm -f /swapfile; echo 'Swapfile entfernt.'; swapon --show"

    const val ZRAM_OFF =
        "systemctl disable nvzramconfig 2>&1; for z in $(swapon --show=NAME --noheadings | grep zram); do swapoff ${D}z; done; " +
            "echo 'zram deaktiviert (dauerhaft ab nächstem Start).'; swapon --show"

    const val ZRAM_ON = "systemctl enable nvzramconfig 2>&1 && systemctl start nvzramconfig 2>&1; swapon --show"

    const val DROP_CACHES = "sync && echo 3 > /proc/sys/vm/drop_caches && free -h"

    // ---------------------------------------------------------------- Kamera

    /** Zeilen: /dev/videoN|Name */
    const val CAMERAS =
        "for d in /dev/video*; do [ -e \"${D}d\" ] || continue; n=$(basename ${D}d); " +
            "echo \"${D}d|$(cat /sys/class/video4linux/${D}n/name 2>/dev/null)\"; done"

    /**
     * Einzelbild aufnehmen. CSI-Kameras laufen über nvarguscamerasrc (Belichtung braucht einige Frames),
     * USB-Kameras über v4l2src. Ergebnis: /tmp/orinpilot_snap.jpg
     */
    fun snapshot(device: String, csiSensorId: Int?, width: Int, height: Int): String {
        val pipe = if (csiSensorId != null) {
            "nvarguscamerasrc sensor-id=$csiSensorId num-buffers=20 ! " +
                "'video/x-raw(memory:NVMM),width=$width,height=$height' ! nvvidconv ! 'video/x-raw,format=I420' ! " +
                "jpegenc ! multifilesink location=/tmp/orinpilot_snap_%02d.jpg"
        } else {
            "v4l2src device=${q(device)} num-buffers=15 ! videoconvert ! videoscale ! 'video/x-raw,width=$width,height=$height' ! " +
                "jpegenc ! multifilesink location=/tmp/orinpilot_snap_%02d.jpg"
        }
        return listOf(
            "rm -f /tmp/orinpilot_snap_*.jpg /tmp/orinpilot_snap.jpg",
            "timeout 25 gst-launch-1.0 -q $pipe >/tmp/orinpilot_snap.log 2>&1 || " +
                (if (csiSensorId == null) "timeout 15 ffmpeg -loglevel error -y -f v4l2 -i ${q(device)} -frames:v 1 /tmp/orinpilot_snap_99.jpg >>/tmp/orinpilot_snap.log 2>&1 || " else "") + "true",
            "L=$(ls /tmp/orinpilot_snap_*.jpg 2>/dev/null | sort | tail -n 1)",
            "if [ -n \"${D}L\" ]; then mv \"${D}L\" /tmp/orinpilot_snap.jpg; rm -f /tmp/orinpilot_snap_*.jpg; echo SNAP_OK; else tail -n 15 /tmp/orinpilot_snap.log; fi",
        ).joinToString("; ")
    }

    const val SNAP_PATH = "/tmp/orinpilot_snap.jpg"

    // ---------------------------------------------------------------- Software

    const val APT_UPDATE = "DEBIAN_FRONTEND=noninteractive apt-get update && echo && apt list --upgradable 2>/dev/null | tail -n +2"
    const val APT_UPGRADE = "DEBIAN_FRONTEND=noninteractive apt-get -y -o Dpkg::Options::=--force-confold upgrade"
    const val APT_CLEAN = "apt-get -y autoremove && apt-get clean && df -h /"
    const val JETPACK_INSTALL = "DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y install nvidia-jetpack"
    const val JTOP_INSTALL =
        "DEBIAN_FRONTEND=noninteractive apt-get -y install python3-pip && " +
            "(pip3 install -U jetson-stats || pip3 install -U --break-system-packages jetson-stats) && " +
            "systemctl restart jtop.service 2>/dev/null; echo 'jtop installiert – im Terminal mit \"jtop\" starten (evtl. Neuanmeldung nötig).'"
    const val DOCKER_NV_CHECK =
        "docker info 2>/dev/null | grep -iE 'runtimes|default runtime'; echo; cat /etc/docker/daemon.json 2>/dev/null || echo 'keine /etc/docker/daemon.json'"
    const val DOCKER_NV_DEFAULT =
        "set -e; command -v nvidia-ctk >/dev/null || { echo 'nvidia-container-toolkit fehlt (Teil von nvidia-jetpack).'; exit 1; }; " +
            "nvidia-ctk runtime configure --runtime=docker --set-as-default; systemctl restart docker; docker info | grep -i 'default runtime'"
    const val DESKTOP_SLIM =
        "systemctl disable --now cups cups-browsed 2>/dev/null; systemctl disable --now whoopsie apport 2>/dev/null; " +
            "systemctl disable --now snapd 2>/dev/null; echo 'Nicht benötigte Dienste (Drucker, Absturzberichte, snap) deaktiviert.'; free -h"

    // ---------------------------------------------------------------- Diagnose

    const val THROTTLE =
        "for z in /sys/class/thermal/thermal_zone*; do printf '%-14s %6s m°C\\n' \"$(cat ${D}z/type)\" \"$(cat ${D}z/temp)\"; done; echo; " +
            "echo 'GPU-Takt:'; cat /sys/devices/platform/bus@0/17000000.gpu/devfreq/17000000.gpu/cur_freq 2>/dev/null || " +
            "cat /sys/class/devfreq/17000000.gpu/cur_freq 2>/dev/null; echo; echo 'Throttle-Ereignisse (dmesg):'; dmesg 2>/dev/null | grep -iE 'throttl|soctherm|oc_event' | tail -n 10"
    const val DMESG_ERR = "dmesg --level=err,warn 2>/dev/null | tail -n 80"
    const val BOOT_TIME = "systemd-analyze 2>&1; echo; systemd-analyze blame 2>/dev/null | head -n 15"
}
