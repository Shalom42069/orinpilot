# OrinPilot

Android-App zur headless Steuerung eines **NVIDIA Jetson Orin Nano (8 GB)**. Die Oberfläche läuft komplett auf dem Handy, der Jetson braucht weder Monitor noch Desktop. Die gesamte Kommunikation läuft über SSH.

## Funktionen

| Bereich | Inhalt |
|---|---|
| **Übersicht** | Live-Werte aus `tegrastats` (1 s): CPU pro Kern, GPU, RAM/Swap, EMC, alle Temperaturen, Stromschienen in mW, Verlaufsgraphen. Dazu Systeminfos (L4T, JetPack, Kernel, IPs, Speicher) |
| **Steuerung** | Fernzugriff (Tailscale), Energiemodus (`nvpmodel`, z. B. 15W / MAXN SUPER), `jetson_clocks`, Lüfter (Profil quiet/cool oder manuell per PWM), Headless/Desktop umschalten, WLAN suchen und verbinden, SSH-Schlüssel erzeugen und installieren, Neustart/Herunterfahren |
| **Terminal** | Eigener xterm-256color-Emulator mit mehreren Sitzungen (Tabs), Zusatztasten (ESC, TAB, CTRL, ALT, Pfeile, PgUp/Dn …), Hardware-Tastatur, Scrollback per Wischen, Kopieren/Einfügen, Schriftgröße. Getestet mit bash; ausgelegt für htop, nano und vim |
| **Dateien** | SFTP-Browser: hochladen, aufs Handy laden, Textdateien bearbeiten, umbenennen, löschen, Ordner anlegen |
| **System** | Prozesse (live, beenden/killen), systemd-Dienste (start/stop/restart/enable/disable, Status, Journal), Docker-Container (start/stop/logs/rm), eigene Schnellbefehle (mit/ohne sudo) |

`sudo` funktioniert automatisch: Das gespeicherte Passwort wird per `sudo -S` über stdin übergeben, also nie in der Befehlszeile.

## Zugriff von unterwegs (anderes Netzwerk)

Nach der ersten Verbindung im Heimnetz: **Steuerung → Fernzugriff**

1. **Tailscale auf dem Jetson installieren:** ein Tippen. Dafür braucht der Jetson Internet.
2. **Jetson anmelden:** Die App zeigt den Login-Link, du öffnest ihn auf dem Handy und gibst den Jetson frei. Alternativ geht ein Auth-Key (`tskey-auth-…`).
3. **Tailscale-App auf dem Handy:** installieren (kostenlos), mit demselben Konto anmelden und den VPN-Schalter einschalten.

Danach speichert die App die Tailscale-Adresse (100.x.y.z) im Profil. Beim Verbinden probiert sie automatisch zuerst die zuletzt funktionierende Adresse, dann das LAN (4 s), dann Tailscale. Zu Hause läuft die Verbindung also direkt, unterwegs über Tailscale, ohne dass du etwas umstellen musst. Der Host-Schlüssel ist auf beiden Wegen derselbe; ein Abweichen wird gemeldet (Schutz gegen Man-in-the-Middle).

Ohne Tailscale geht es auch: Im Profil lässt sich eine eigene Fernzugriff-Adresse eintragen (DynDNS mit Portfreigabe, ZeroTier …). Bei einer Portfreigabe dann unbedingt den Schlüssel-Login nutzen.

## APK bauen

Diese Umgebung hatte keinen Zugriff auf das Android-SDK. Das Projekt ist deshalb fertiger Quellcode, die APK baust du auf einem der beiden Wege:

**A) GitHub Actions (ohne Android Studio)**
1. Neues (privates) Repo anlegen und den Ordnerinhalt pushen.
2. Der Workflow `.github/workflows/build.yml` baut automatisch.
3. Unter *Actions → letzter Lauf → Artifacts* liegt `OrinPilot-debug-apk`. Herunterladen und auf dem Handy installieren.

**B) Lokal**
```bash
# Android SDK nötig (z. B. über Android Studio), dann:
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Jetson vorbereiten

- SSH ist bei JetPack standardmäßig aktiv (`sudo systemctl enable --now ssh`, falls nicht).
- **Erste Einrichtung ganz ohne Monitor:** Jetson per USB-C mit dem PC verbinden. Die Ersteinrichtung (oem-config) läuft dann über die serielle Konsole (`/dev/ttyACM0`, 115200 Baud). Danach ist der Jetson im USB-Gerätemodus unter `192.168.55.1` erreichbar.
- Die App findet Jetsons im WLAN/LAN über **„Jetson im Netzwerk suchen“** (Port 22 im /24-Netz plus 192.168.55.1).

## Technik

- Kotlin, Jetpack Compose (Material 3), minSdk 26 / targetSdk 35
- SSH/SFTP: [JSch (mwiede-Fork)](https://github.com/mwiede/jsch) + BouncyCastle (Ed25519/Curve25519 auf allen Android-Versionen)
- Eigener Terminal-Emulator (`term/TerminalEmulator.kt`): VT100/VT220/xterm-Teilmenge, 256 Farben + Truecolor, Alt-Screen, Scroll-Regionen, DEC-Linienzeichen, UTF-8, Bracketed Paste
- Alle Shell-Befehle stehen zentral in `data/Scripts.kt`

### Getestet

- Terminal-Emulator: 24 Unit-Tests plus Fuzzing (zufällige Bytes, Resize)
- Parser (`tegrastats` von JetPack 5 und 6, ps, systemctl, docker, nvpmodel, nmcli, Tailscale-Status)
- SSH-Schicht gegen einen echten OpenSSH-Server (9.6): Exec, stdin/EOF, sudo mit Passwort, Streaming, SFTP, PTY-Terminal, LAN→Fernzugriff-Fallback, Host-Key-Prüfung, Schlüsselerzeugung und -Login

Die Compose-Oberfläche konnte hier nicht kompiliert werden (kein Android-SDK). Sie wurde statisch gegen die API-Dateien der verwendeten Bibliotheksversionen geprüft. Falls der erste Build trotzdem einen Fehler meldet, schick mir einfach die Meldung.

## Sicherheit

- Profile (inkl. Passwort/Schlüssel) liegen app-privat; Android-Backup ist deaktiviert.
- Host-Schlüssel per Trust-on-first-use; eine Änderung wird angezeigt und muss bestätigt werden.
- Empfehlung: Unter *Steuerung → Sicherheit* einen SSH-Schlüssel erzeugen lassen.
