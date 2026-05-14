---
title: "[Epic 3] Story 3.1a: Configurable Terminal Options"
labels: enhancement, Epic 3
assignees: ""
---

## Beschreibung

Terminal-Farben, Schriftart, Schriftgröße, Cursor-Stil und Cursor-Blinken konfigurierbar machen. Es gibt zwei Ebenen:

1. **Globale Settings** über Tray-Menü "Settings..." → Tabbed Dialog mit Terminal-Tab
2. **Per-Profile-Override** über Zahnrad-Button im [`ConnectionDialog`](src/main/java/de/in/jnc/ConnectionDialog.java)

Aktuell sind alle Terminal-Werte in [`SolarizedDarkSettingsProvider`](src/main/java/de/in/jnc/terminal/SolarizedDarkSettingsProvider.java) hartcodiert.

---

## Ziel

Der Benutzer kann globale Terminal-Standards festlegen und optional für einzelne Profile überschreiben.

---

## Tasks

### 1. [`TerminalSettings.java`](src/main/java/de/in/jnc/terminal/TerminalSettings.java) – Datenmodell
- [ ] POJO mit Feldern: `colorScheme`, `fontFamily`, `fontSize`, `cursorShape`, `cursorBlinkRateMs`
- [ ] Hilfsmethoden: `getEffectiveCursorShape()`, `isCursorBlinking()`
- [ ] Factory-Methoden: `createDefault()`, `createSolarizedDark()`
- [ ] Jackson-kompatibel für JSON-Serialisierung
- [ ] Unit-Tests

### 2. [`SolarizedPalette.java`](src/main/java/de/in/jnc/terminal/SolarizedPalette.java) – Konstanten
- [ ] Solarized Dark-Farbkonstanten aus [`SolarizedDarkSettingsProvider`](src/main/java/de/in/jnc/terminal/SolarizedDarkSettingsProvider.java) extrahieren

### 3. [`DynamicSettingsProvider.java`](src/main/java/de/in/jnc/terminal/DynamicSettingsProvider.java)
- [ ] Ersetzt [`SolarizedDarkSettingsProvider`](src/main/java/de/in/jnc/terminal/SolarizedDarkSettingsProvider.java)
- [ ] Liest alle Werte aus `TerminalSettings`
- [ ] Berücksichtigt Farbschema (Solarized Dark, Default, Custom)
- [ ] Fallback auf OS-Default-Schriftart bei leerem `fontFamily`
- [ ] Unit-Tests

### 4. [`GlobalSettings.java`](src/main/java/de/in/jnc/GlobalSettings.java) – Globale Konfiguration
- [ ] Singleton mit JSON-Persistenz in `%LOCALAPPDATA%/jNodeCommander/settings.json`
- [ ] Enthält `TerminalSettings` als globalen Standard
- [ ] `load()` / `save()` Methoden

### 5. [`ConnectionProfile`](src/main/java/de/in/jnc/ConnectionProfile.java) erweitern
- [ ] Feld `terminalSettingsOverride` (optional, nullable)
- [ ] Methode `resolveTerminalSettings()` → globale Settings falls null, sonst Override

### 6. [`TerminalSettingsPanel.java`](src/main/java/de/in/jnc/terminal/TerminalSettingsPanel.java) – UI
- [ ] Wiederverwendbares `JPanel` mit allen Steuerelementen:
  - Farbschema (JComboBox: Solarized Dark, Default, Custom)
  - Schriftart (JComboBox: Systemschriften + "System Default")
  - Schriftgröße (JSpinner: 8–36)
  - Cursor-Stil (JComboBox: 6 Varianten)
  - Blinkrate (JSpinner: 0–2000ms)
- [ ] `getSettings()` / `setSettings(TerminalSettings)` Methoden

### 7. [`SettingsFrame.java`](src/main/java/de/in/jnc/SettingsFrame.java) – Globaler Dialog
- [ ] `JFrame` mit `JTabbedPane`
- [ ] Tab "Terminal" mit `TerminalSettingsPanel`
- [ ] Save/Cancel-Buttons
- [ ] Speichert in `GlobalSettings`

### 8. [`TrayManager`](src/main/java/de/in/jnc/TrayManager.java) anpassen
- [ ] "Settings..." MenuItem im Popup-Menü (vor "Exit")
- [ ] Öffnet `SettingsFrame`

### 9. [`TerminalFrame`](src/main/java/de/in/jnc/terminal/TerminalFrame.java) anpassen
- [ ] Konstruktor erhält `TerminalSettings settings`
- [ ] Nutzt `DynamicSettingsProvider` statt `SolarizedDarkSettingsProvider`
- [ ] Setzt Cursor-Shape via `terminalPanel.setDefaultCursorShape()`

### 10. [`ConnectionDialog`](src/main/java/de/in/jnc/ConnectionDialog.java) anpassen
- [ ] Zahnrad-Button (⚙) für per-Profile Terminal-Settings
- [ ] Öffnet `TerminalSettingsPanel` im Per-Profile-Mode
- [ ] Speichert Override in `ConnectionProfile.terminalSettingsOverride`
- [ ] Übergibt `resolveTerminalSettings()` an `TerminalFrame`

### 11. Integration & Smoke-Test
- [ ] Global: Tray → Settings → Terminal-Tab → Werte ändern → Save → Connect → prüfen
- [ ] Per-Profile: ConnectionDialog → Zahnrad → Werte ändern → OK → Connect → prüfen
- [ ] Per-Profile auf "use global" zurücksetzen → Terminal nutzt globale Settings

---

## Architektur

Siehe [`plans/epic3-story3.1a-terminal-options.md`](plans/epic3-story3.1a-terminal-options.md) für detaillierte Architektur, Datenfluss-Diagramme und Klassenstruktur.

### Neue Klassen

| Klasse | Package | Zweck |
|--------|---------|-------|
| `TerminalSettings` | `de.in.jnc.terminal` | Terminal-Konfigurations-POJO |
| `DynamicSettingsProvider` | `de.in.jnc.terminal` | Dynamischer JediTerm SettingsProvider |
| `SolarizedPalette` | `de.in.jnc.terminal` | Solarized Dark-Farbkonstanten |
| `TerminalSettingsPanel` | `de.in.jnc.terminal` | Wiederverwendbares Settings-UI-Panel |
| `GlobalSettings` | `de.in.jnc` | Singleton für globale App-Konfiguration |
| `SettingsFrame` | `de.in.jnc` | Tabbed Settings-Dialog (via Tray) |

### Geänderte Klassen

| Klasse | Änderung |
|--------|----------|
| `ConnectionProfile` | Feld `terminalSettingsOverride` + `resolveTerminalSettings()` |
| `TerminalFrame` | Konstruktor-Parameter `TerminalSettings`, nutzt `DynamicSettingsProvider` |
| `ConnectionDialog` | Zahnrad-Button für per-Profile Override |
| `TrayManager` | "Settings..." MenuItem |
