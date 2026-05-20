# Epic 6: Browser-Finalisierung & UX-Politur

## Story 6.1: JavaFX-Backend entfernen & GUI bereinigen

**Ziel:** Keine Umschaltmöglichkeit zum JavaFX-WebView-Backend mehr. Optionen aus GUI entfernen, beim Profil-Laden ignorieren.

**Betroffene Dateien:**
- [`BrowserPanel.java`](src/main/java/de/in/jnc/connection/browser/BrowserPanel.java) — `switchBackend()` entfernen oder auf JCEF hartkodieren
- [`BrowserBackendType.java`](src/main/java/de/in/jnc/connection/browser/backend/BrowserBackendType.java) — `JAVAFX_WEBVIEW`-Enum-Wert entfernen oder deprecated markieren
- [`GlobalSettings.java`](src/main/java/de/in/jnc/GlobalSettings.java) — `defaultBrowser`-Feld (falls vorhanden) nur JCEF erlauben
- [`ConnectionProfile.java`](src/main/java/de/in/jnc/ConnectionProfile.java) — `browserBackend`-Feld beim Laden ignorieren
- [`SettingsFrame.java`](src/main/java/de/in/jnc/SettingsFrame.java) — Browser-Backend-ComboBox (falls vorhanden) entfernen

**Implementierung:**
1. `BrowserBackendType`: `JAVAFX_WEBVIEW` entfernen, Enum auf `JCEF` reduzieren (single-value enum, Vorbereitung für spätere Alternative Engine)
2. `BrowserPanel`: `switchBackend()` und `BrowserBackendType`-Parameter entfernen; `createBackend()` vereinfachen
3. `GlobalSettings`/`ConnectionProfile`: Felder für `browserBackend` entfernen oder beim Deserialisieren ignorieren (`@JsonIgnore`)
4. GUI: Alle Backend-Auswahl-UI-Elemente entfernen

---

## Story 6.2: JSeparator-UI-Fehler im CredentialsService beheben

**Problem:** `java.lang.Error: no ComponentUI class for: javax.swing.JSeparator`

**Ursache:** Der `JSeparator` wird außerhalb des EDT oder in einem Kontext erstellt, wo der FlatLaf LookAndFeel nicht verfügbar ist. Der Credentials-Dialog wird von einem CEF-Callback-Thread ausgelöst, der nicht der EDT ist.

**Betroffene Dateien:**
- [`CredentialsService.java`](src/main/java/de/in/jnc/terminal/CredentialsService.java:223)
- [`JCEFBackend.java`](src/main/java/de/in/jnc/connection/browser/backend/jcef/JCEFBackend.java) — `onCredentialsRequested()` muss auf EDT dispatcht werden

**Implementierung:**
1. `JCEFBackend.onCredentialsRequested()`: `credentialsCallback.accept()` in `SwingUtilities.invokeLater()` wrappen
2. `CredentialsService.showCredentialsDialog()`: Sicherstellen, dass `JSeparator` auf dem EDT erstellt wird (bereits via invokeLater)
3. Optional: `CredentialsService.addSeparator()` defensiv mit `SwingUtilities.invokeLater()` wrappen

---

## Story 6.3: BrowserMenu (ersetzt EndpointPopupMenu)

**Ziel:** Der "Web Apps"-Knopf in der Tab-Leiste wird durch ein vollständiges Browser-Menü ersetzt.

**Menü-Struktur:**
```
[🌐 Browser]
├── 🆕 New Browser Tab
├── ──────────────
├── 📑 Bookmarks
│   ├── host1/path1
│   ├── host2/path2
│   └── ...
├── 🕐 History          → öffnet Browser-Tab mit History-UI
├── 📋 Open Tabs
│   ├── Tab 1: Title    → aktiviert diesen Tab
│   ├── Tab 2: Title
│   └── ...
└── 🔗 Discover Endpoints  → bisheriges EndpointPopupMenu
```

**Betroffene Dateien:**
- [`ConnectionFrame.java`](src/main/java/de/in/jnc/connection/ConnectionFrame.java) — `webAppsBtn` ersetzen durch `browserMenuBtn` mit `JPopupMenu`
- Neu: `BrowserMenu.java` — Klasse für das Popup-Menü
- [`BrowserTabManager.java`](src/main/java/de/in/jnc/connection/browser/BrowserTabManager.java) — Methoden: `getOpenTabs()`, `activateTab(index)`
- [`EndpointPopupMenu.java`](src/main/java/de/in/jnc/connection/browser/EndpointPopupMenu.java) — bleibt bestehen, wird als Sub-Menü eingebunden

**Bookmarks:**
- Speicherort: im `ConnectionProfile` (`profile.bookmarks`), nicht global
- Datenstruktur: `List<Bookmark>` mit `url`, `title`, `host`
- Hinzufügen: `Ctrl+D` in der Adresszeile (KeyBinding auf `BrowserPanel.urlField`)
- Anzeige: sortiert nach `host`, dann `url`
- Entfernen: Rechtsklick → "Remove" im Bookmark-Menü
- Jeder ConnectionFrame hat seine eigenen Bookmarks (pro Profil)

**History:**
- Wie in Chrome: öffnet eine HTML-Seite im Browser-Tab (`chrome://history`-ähnlich)
- Die HTML-Seite wird dynamisch aus den History-Daten generiert
- Speicherort: im `ConnectionProfile` (`profile.history`), nicht global
- Einträge automatisch beim Laden einer URL hinzufügen
- UI: HTML-Tabelle mit URL, Title, Timestamp; Remove/Remove-All Buttons per JS
- Klick auf Eintrag → `openBrowserUrl(url, title)`

**Open Tabs:**
- Dynamisch aus `BrowserTabManager` ausgelesen
- Klick auf Eintrag → `tabbedPane.setSelectedIndex(index)`

---

## Story 6.4: Globale Tastenkombinationen

**Ziel:** Tastatur-Shortcuts für schnelle Tab-Navigation.

| Tastenkombination | Aktion |
|---|---|
| `Alt+C` | Terminal-Tab (Index 0) aktivieren |
| `Alt+N` | File-Transfer-Tab (Index 1) aktivieren |
| `Alt+1` … `Alt+9` | Browser-Tab 1–9 aktivieren |

**Betroffene Dateien:**
- [`ConnectionFrame.java`](src/main/java/de/in/jnc/connection/ConnectionFrame.java) — `JTabbedPane`-Mnemonic setzen oder `KeyBinding`-InputMap

**Implementierung:**
1. `JTabbedPane` unterstützt Mnemonics via `setMnemonicAt(index, keyCode)`. Prüfen, ob das mit `Alt+Ziffer` funktioniert.
2. Fallback: `KeyboardFocusManager.addKeyEventDispatcher()` für globale Shortcuts im `ConnectionFrame`
3. Tab-Indizes: 0=Terminal, 1=FileTransfer, 2+=Browser

---

## Story 6.5: Tab-Persistenz (Session Restore)

**Ziel:** Offene Browser-Tabs beim Schließen speichern, beim nächsten Öffnen wiederherstellen — optional, im Profil konfigurierbar.

**Konfiguration (alle im ConnectionProfile):**
- `restoreTabs` (boolean, default `true`) — ein/ausschaltbar
- `savedTabUrls` (List<String>) — gespeicherte URLs
- Konfigurierbar im `SettingsFrame` (pro Profil) oder beim Verbindungsaufbau

**Betroffene Dateien:**
- [`ConnectionProfile.java`](src/main/java/de/in/jnc/ConnectionProfile.java) — neue Felder
- [`ConnectionFrame.java`](src/main/java/de/in/jnc/connection/ConnectionFrame.java) — `closeConnection()`: URLs speichern; nach `startTerminal()`: Tabs wiederherstellen
- [`BrowserTabManager.java`](src/main/java/de/in/jnc/connection/browser/BrowserTabManager.java) — `getOpenUrls()`-Methode
- [`SettingsFrame.java`](src/main/java/de/in/jnc/SettingsFrame.java) — Checkbox "Restore open tabs on reconnect"

**Implementierung:**
1. `ConnectionProfile`: `restoreTabs` (boolean, default true), `savedTabUrls` (List<String>)
2. `BrowserTabManager.getOpenUrls()`: iteriert über Browser-Tabs und sammelt URLs
3. `ConnectionFrame.closeConnection()`: wenn `profile.restoreTabs`: `profile.setSavedTabUrls(tabManager.getOpenUrls())`
4. `ConnectionFrame` nach `startTerminal()`: wenn `profile.restoreTabs && !savedTabUrls.isEmpty()`: für jede URL `openBrowserUrl(url)`

---

## Story 6.6: Tab-Close-Button-Fix + Tab-Wechsel-Fokus

**Problem 1:** Close-Button (✕) verschwindet nach URL-Load.  
**Ursache:** `onTitleChanged()` ruft `tabbedPane.setTitleAt(index, title)` auf — in manchen L&F (ChromeTabbedPaneUI) überschreibt das die custom tab component.

**Fix:** `setTitleAt()`-Aufruf entfernen, nur das JLabel im custom tab component aktualisieren (wird bereits gemacht). Der `setTitleAt`-Aufruf ist redundant, da das custom component das Label bereits setzt.

**Problem 2:** Tab-Wechsel fokussiert nicht automatisch den Content.

**Betroffene Dateien:**
- [`BrowserTabManager.java`](src/main/java/de/in/jnc/connection/browser/BrowserTabManager.java) — `onTitleChanged`: `setTitleAt` entfernen; Tab-Wechsel-Listener hinzufügen
- [`BrowserPanel.java`](src/main/java/de/in/jnc/connection/browser/BrowserPanel.java) — `requestUrlBarFocus()`-Methode
- [`ConnectionFrame.java`](src/main/java/de/in/jnc/connection/ConnectionFrame.java) — `ChangeListener` auf `tabbedPane`

---

## Story 6.7: Automatische Fokussierung

**Ziel:** Beim Öffnen/Wechseln eines Tabs wird der Content automatisch fokussiert.

| Aktion | Fokus-Ziel |
|---|---|
| Neuer Browser-Tab | URL-Adressleiste |
| File-Transfer-Tab geöffnet | Linke Seite, erstes File |
| SSH-Tab geöffnet | JediTerm-Widget |
| Tab-Wechsel (beliebig) | Jeweiliger Content |

**Betroffene Dateien:**
- [`ConnectionFrame.java`](src/main/java/de/in/jnc/connection/ConnectionFrame.java) — `ChangeListener` auf `tabbedPane`
- [`BrowserPanel.java`](src/main/java/de/in/jnc/connection/browser/BrowserPanel.java) — `requestUrlBarFocus()`
- [`BrowserTabManager.java`](src/main/java/de/in/jnc/connection/browser/BrowserTabManager.java) — `openUrl()`, `openNewTab()`: Fokus nach Erstellung
- [`FileTransferPanel.java`](src/main/java/de/in/jnc/connection/filetransfer/FileTransferPanel.java) — `requestInitialFocus()`
- [`JediTermWidget`](libs/jediterm/ui/src/com/jediterm/terminal/ui/JediTermWidget.java) — `requestFocusInWindow()` auf TerminalPanel

**Implementierung:**
1. `BrowserPanel.requestUrlBarFocus()`: `urlField.requestFocusInWindow()` + bei JCEF `backend.releaseFocus()` (damit URL-Leiste Tastatureingaben bekommt)
2. `BrowserPanel` nach Erstellung: `SwingUtilities.invokeLater(this::requestUrlBarFocus)`
3. `ConnectionFrame`: `tabbedPane.addChangeListener` → je nach selektiertem Tab:
   - Index 0: `terminalWidget.getTerminalPanel().requestFocusInWindow()`
   - Index 1: `fileTransferPanel.requestInitialFocus()`
   - Index ≥2: `((BrowserPanel) tabbedPane.getComponentAt(idx)).requestUrlBarFocus()`

---

## Implementierungsreihenfolge

1. **Story 6.2** (JSeparator-Fix) — schnellster Fix, behebt aktiven Bug
2. **Story 6.6** (Close-Button + Tab-Wechsel) — UX-Bug
3. **Story 6.7** (Auto-Fokus) — UX-Verbesserung
4. **Story 6.1** (JavaFX entfernen) — Code-Bereinigung
5. **Story 6.4** (Tastenkombinationen) — einfache Implementierung
6. **Story 6.3** (BrowserMenu) — komplex, baut auf 6.5 auf
7. **Story 6.5** (Tab-Persistenz) — benötigt ConnectionProfile-Änderungen
