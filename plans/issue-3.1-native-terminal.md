---
title: "[Epic 3] Story 3.1: The Native Terminal ('PuTTY' alternative)"
labels: enhancement, Epic 3
assignees: ""
---

## Beschreibung

Ein funktionales SSH-Terminal innerhalb einer Swing-Oberfläche, das eine Shell-Channel-Verbindung via SSHJ herstellt und **JediTerm** als Terminal-Emulator nutzt.

Aktuell sammelt der [`ConnectionDialog`](src/main/java/de/in/jnc/ConnectionDialog.java) zwar SSH-Zugangsdaten, aber der "Connect"-Button führt noch **keine** SSH-Verbindung durch – er loggt nur und schließt den Dialog.

---

## Ziel

Wenn der Benutzer im ConnectionDialog auf "Connect" klickt, soll eine echte SSH-Verbindung aufgebaut und ein Terminal-Fenster mit der Remote-Shell geöffnet werden.

---

## Tasks

### 1. Dependency hinzufügen
- [ ] `com.jediterm:jediterm-terminal:3.1.0` in [`pom.xml`](pom.xml) eintragen (MIT-Lizenz)
- [ ] `mvn dependency:resolve` ausführen, um Verfügbarkeit zu prüfen

### 2. Sub-Package anlegen
- [ ] Ordner `src/main/java/de/in/jnc/terminal/` erstellen
- [ ] Ordner `src/test/java/de/in/jnc/terminal/` erstellen

### 3. [`SshConnection.java`](src/main/java/de/in/jnc/terminal/SshConnection.java) erstellen
- [ ] SSHJ-`SSHClient` mit Verbindungsaufbau (Host, Port)
- [ ] Authentifizierung: **Passwort** (`ssh.authPassword()`) **oder** Private-Key (`ssh.authPublickey()`)
- [ ] `ShellChannel` mit PTY öffnen: `session.allocateDefaultPTY("xterm-256color", 80, 24, 0, 0)`
- [ ] Host-Key-Verifikation im MVP: alle Keys akzeptieren
- [ ] Methoden: `connect()`, `disconnect()`, `isConnected()`, `resizePty(int columns, int rows)`
- [ ] Getter: `getInputStream()`, `getOutputStream()`
- [ ] Sauberes Resource-Cleanup in `disconnect()`

### 4. [`SshTtyConnector.java`](src/main/java/de/in/jnc/terminal/SshTtyConnector.java) erstellen
- [ ] Implementiert `com.jediterm.pty.TtyConnector`
- [ ] `read(byte[], int, int)` → delegiert an `SshConnection.getInputStream().read()`
- [ ] `write(byte[])` → delegiert an `SshConnection.getOutputStream().write()`
- [ ] `resize(Dimension, Dimension)` → ruft `SshConnection.resizePty()` auf
- [ ] `close()` → ruft `SshConnection.disconnect()` auf
- [ ] `getName()` → liefert `"ssh: user@host"`
- [ ] `isConnected()` → delegiert an `SshConnection.isConnected()`

### 5. [`TerminalFrame.java`](src/main/java/de/in/jnc/terminal/TerminalFrame.java) erstellen
- [ ] `JFrame` mit `JediTermWidget` (80×24), `SshTtyConnector` und `TerminalStarter`
- [ ] Fenster-Titel: `user@host:port – jNodeCommander`
- [ ] `WindowAdapter.windowClosing()` → `SshTtyConnector.close()` aufrufen
- [ ] `TerminalStarter.start()` nach der Initialisierung aufrufen

### 6. [`ConnectionDialog.onConnect()`](src/main/java/de/in/jnc/ConnectionDialog.java) erweitern
- [ ] `SwingWorker<SshConnection, Void>` für blockierenden SSH-Verbindungsaufbau
- [ ] Während Verbindung: Buttons deaktivieren, "Connecting..." anzeigen
- [ ] Bei Erfolg: `new TerminalFrame(sshConnection).setVisible(true)` + Dialog schließen
- [ ] Bei Fehler: `JOptionPane.showMessageDialog()` mit Fehlermeldung + Dialog reaktivieren

### 7. Unit-Tests schreiben
- [ ] `SshConnectionTest` – Verbindungsaufbau/Fehler mit gemocktem SSHJ
- [ ] `SshTtyConnectorTest` – read/write/resize/close Delegation
- [ ] `TerminalFrameTest` – Fenster-Titel, WindowClose-Verhalten

### 8. Integration & Smoke-Test
- [ ] Gegen echten SSH-Server testen (localhost oder remote)
- [ ] Prüfen: Login, `ls`, `top` (startet), `vim` (startet), Fenster schließen, Resize

---

## Architektur

Siehe [`plans/epic3-story3.1-native-terminal.md`](plans/epic3-story3.1-native-terminal.md) für detaillierte Architektur, Datenfluss-Diagramme und Edge Cases.

### Kernklassen

| Klasse | Package | Zweck |
|--------|---------|-------|
| `SshConnection` | `de.in.jnc.terminal` | SSHJ-Verbindung + ShellChannel |
| `SshTtyConnector` | `de.in.jnc.terminal` | JediTerm `TtyConnector`-Implementierung |
| `TerminalFrame` | `de.in.jnc.terminal` | JFrame mit eingebettetem JediTermWidget |
| `ConnectionDialog` (erweitert) | `de.in.jnc` | SwingWorker für Verbindungsaufbau |

### Keine neuen natives Abhängigkeiten

JediTerm ist reines Java. SSHJ (0.38.0) ist bereits vorhanden.
