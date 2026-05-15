# jNodeCommander (jnc) ⚓️

**jNodeCommander** is a Java-based desktop application serving as the central command bridge for administering web products on Kubernetes nodes. 

The tool automates the entire administrative workflow: from the initial SSH login and extraction of credentials via console commands to the automated login into the respective web applications—all bundled in a clean, native multi-window interface.

---

## 1. Systemarchitektur & Technologie

* **Basis:** Java-Desktop-Anwendung, entwickelt mit **Java 25 LTS** für modernstes Memory-Management und aktuelle Sprachfeatures.
* **UI-Framework (Swing & FlatLaf):** Die Benutzeroberfläche wird robust und nativ in **Swing** entwickelt. Für ein modernes, zeitgemäßes Design (inklusive Dark-Mode und HiDPI-Support) kommt **FlatLaf** (Flat Look and Feel) zum Einsatz.
* **Plattformen:** Cross-Platform-Unterstützung für **Windows, Linux und macOS** (inkl. nativer Unterstützung für Apple Silicon / ARM64).
* **Browser-Engine:** Integration von **JCEF (Java Chromium Embedded Framework)**. Da JCEF historisch auf AWT/Swing optimiert ist, integriert sich die Browser-View ohne Workarounds (wie Heavyweight/Lightweight-Mixing) direkt, hochperformant und fehlerfrei in die Benutzeroberfläche.
* **Konnektivität & File-Transfer:** **SSHJ** für robuste, asynchrone SSH-Sitzungen, modernes Key-Handling sowie integrierte SCP/SFTP-Funktionalität für rudimentäre Dateitransfers.
* **Update-System:** Integration und Ausbau von **SimpleUpDraft4J** für plattformspezifische Updates inkl. Integritätsprüfungen großer Binärdaten.

---

## 2. UI/UX Concept

### 2.1 The Base Station (Tray / Menu Bar)
* The application operates primarily in a resource-efficient manner from the **System Tray** (Win/Linux) or the **Menu Bar** (macOS).
* **Unified Profile List:** A persistent overview of all configured K8s nodes (profiles).
* **Status Indication:** Active sessions are visually highlighted.
* **Focus-on-Click:** Clicking on an already active profile brings the corresponding node window to the foreground instead of forcing a new instance.

### 2.2 The "BridgeDeck" (Multi-Window Architecture)
* Each node profile opens an **independent main window**.
* **Multi-Tab System:** A separate, isolated JCEF browser tab is generated for each product found on the node.
* **Integrated Console:** An optionally displayable terminal panel (via JLine/JCTerm) for direct inputs into the active SSH session.

---

## 3. Functional Requirements

### 3.1 SSH Automation & Parsing ("The Picker")
* Automatic background connection to the K8s node.
* Execution of the `show_credentials` command.
* A regex/structure parsing engine extracts app names, URLs, usernames, and passwords from the console output.
* **Fallback Logic:** In case of unreadable output, a "Raw-View" opens where credentials can be manually assigned to tabs via drag & drop.

### 3.2 Auto-Login & Injection ("The Door")
* Control of the JCEF context for secure injection of JavaScript snippets.
* **Intelligent DOM Watcher:** The injection script waits asynchronously until the corresponding login fields are actually rendered in the web application's DOM (e.g., React/Angular) before inserting login data.

### 3.3 Rudimentärer Dateitransfer (SFTP / SCP)
Da Administrationsaufgaben oft den Austausch von Konfigurations- oder Logdateien erfordern, bietet jnc ein integriertes File-Interface.
* **Technologie:** Nahtlose Nutzung des bestehenden SSH-Tunnels über das in `SSHJ` integrierte SFTP/SCP-Protokoll.
* **UI-Integration:** 
  * Ein rudimentärer Dateimanager (als separater Tab oder Side-Panel), der das Up- und Herunterladen von Dateien in das Home-Verzeichnis (oder temporäre Verzeichnisse) des K8s-Nodes ermöglicht.
  * **Drag & Drop:** Perspektivisch Unterstützung für Drag & Drop direkt in das jnc-Fenster, um Dateien unkompliziert auf den Node zu transportieren.
---

## 4. Security & Profile Scopes

### 4.1 Environment Scopes
To ensure flexibility in testing and maximum security in production, jnc implements a strict scope concept:

* **Scope: TEST / DEV**
  * Allows the optional storage of node admin passwords in a local vault secured by a master password (AES-256 encryption).
* **Scope: PRODUCTION (Strict Mode)**
  * Storage of credentials on the local drive is strictly prohibited.
  * All extracted credentials exist **exclusively in RAM** and are completely wiped from memory when the profile window is closed.

### 4.2 Session Isolation
* Each JCEF process operates in a completely isolated context. Session cookies, caches, and histories are not shared across profiles.

---

## 5. Resource & Lifecycle Management

* **Process Control:** jnc actively monitors the native Chromium subprocesses, ensuring no "zombie processes" remain in the operating system when a window or the application is closed.
* **SSH Keep-Alive:** SSH tunnels are kept stable throughout the entire lifecycle of a window to allow console commands to be issued at any time.

---

## 6. CLI-Startparameter

jnc unterstützt beim Starten über die Kommandozeile zwei Flags, um Verbindungen automatisch herzustellen:

### 6.1 `--open` – Gespeicherte Profile öffnen

Öffnet ein oder mehrere gespeicherte Connection-Profile anhand ihres Namens.

```bash
# Einzelnes Profil öffnen
java -jar jnc.jar --open="mein-server"

# Mehrere Profile öffnen (kommagetrennt)
java -jar jnc.jar --open="produktion,staging,dev"

# Kurzform -o
java -jar jnc.jar -o="produktion"
```

### 6.2 `--connect` – Ad-hoc Verbindung

Stellt eine direkte SSH-Verbindung mit übergebenen Login-Daten her, ohne dass ein Profil angelegt werden muss.

```bash
# Format: --connect="benutzer:passwort@hostname:port"
java -jar jnc.jar --connect="root:meinPasswort@192.168.1.100:2222"

# Port ist optional (Standard: 22)
java -jar jnc.jar --connect="admin:secret@example.com"

# Kurzform -c
java -jar jnc.jar -c="user:pass@host:2222"

# Mehrere Ad-hoc Verbindungen (kommagetrennt)
java -jar jnc.jar --connect="root:pass@host1:2222,admin:secret@host2"
```

### 6.3 Kombinierter Einsatz

Beide Flags können gleichzeitig verwendet werden:

```bash
java -jar jnc.jar --open="produktion" --connect="user:pass@staging.local:22"
```