# jNodeCommander (jnc) - Development Roadmap & Backlog

This document outlines the development phases structured as Epics and User Stories. It serves as the primary backlog for GitHub Issues.

## Epic 1: Foundation & CI/CD Workflow
**Goal:** Establish a solid, building project skeleton with automated pipelines.

* [x] **Story 1.1: Project Initialization**
  * Initialize Git repository.
  * Create `pom.xml` configured for Java 21.
  * Add core dependencies: Swing, FlatLaf, SSHJ, and JCEF wrapper.
* [x] **Story 1.2: CI/CD Pipeline Setup**
  * Create a GitHub Actions workflow (`.github/workflows/build.yml`) to compile and test the project on `push` and `pull_request`.
* [x] **Story 1.3: Basic Application Skeleton**
  * Implement the `main` method.
  * Initialize FlatLaf for the modern Swing look and feel.

## Epic 2: The Command Center (Tray & Profiles)
**Goal:** Build the central entry point and connection management.

* [x] **Story 2.1: System Tray Integration**
  * Implement a native Windows System Tray icon using AWT/Swing.
  * Add a basic context menu (Exit, Connection->new,<alle gespeicherten connections/profiles>).
  * Ensure the application stays alive in the background when no windows are open.
* [x] **Story 2.2: "Connect to Host" Dialog**
  * Build a Swing UI dialog to input Host, Username, Port, and Password/Key.
* [x] **Story 2.3: Profile Persistence**
  * Implement logic to save and load connection profiles to/from the local disk.
  * Implement basic AES-256 encryption for saved passwords (Test Scope).

## Epic 3: The Three Pillars (Tools Integration)
**Goal:** Provide the core administrative interfaces within a node's window.

* [x] **Story 3.1: The Native Terminal ("PuTTY" alternative)**
  * Create a Swing panel that connects to a shell channel via SSHJ.
  * Bind standard input/output to the UI to execute manual commands.
  * Integrate JediTerm as terminal emulator (Git submodule + local Maven build).
  * Implement TtyConnector bridge for SSHJ shell channels.
  * Provide Solarized Dark as default color scheme.
* [x] **Story 3.1a: Configurable Terminal Options**
  * Make terminal color scheme configurable (Solarized Dark, Default, custom).
  * Make font family and font size configurable.
  * Make cursor style and blinking configurable.
  * Provide a settings UI or extend the existing connection dialog.
* [x] **Story 3.2: File Transfer Interface ("WinSCP" alternative)**
  * Build a ConnectionFrame with JTabbedPane (Terminal + File Transfer pinned tabs).
  * Implement dual-pane file transfer (left: local, right: remote) via SFTP (SSHJ).
  * Support Copy (both directions), Delete, Rename, MkDir file operations.
* [x] **Story 3.3.1: Browser Tab (JCEF)**
  * Embed a JCEF browser instance as a closable dynamic tab within ConnectionFrame.
  * Add URL navigation bar (back, forward, refresh, address field).
  * Implement tab management: existing URL → select tab, new URL → create tab.
  * Provide "New Browser Tab" entry for manual URL entry.
  * Prepare for JS-injection capability (required for Epic 4).
* [x] **Story 3.3.2: URL + Service Discovery (Web Apps Menu)**
  * Add `executeCommand()` to SshConnection for running shell commands in a separate channel.
  * Implement kubectl-based service discovery (`kubectl get svc --all-namespaces`) via SSH.
  * Classify endpoints by access type: NodePort (direct), ClusterIP (tunnel required), Ingress.
  * Build a "🔗 Web Apps" button in the ConnectionFrame tab bar with a grouped popup menu.
  * Implement automatic view switching: 1-2 namespaces → flat, 3+ → grouped by namespace.
  * Add toggle switch to manually switch between flat and grouped views.
  * Implement `kubectl port-forward` tunnel management for ClusterIP services.
  * Clean up all tunnels on connection close.

## Epic 4: Automation Core (The JLock Magic)
**Goal:** Automate credential retrieval and application login.

* [x] **Story 4.1: JediTerm Context Menu Extensions (Credentials & Settings)**
  * Add "Credentials..." entry (top) to JediTerm's right-click context menu.
  * Execute `show_credentials` on the remote host via SSH when clicked.
  * Build a parser to extract credentials (`name`, `username`, `password`) from the output.
  * Show a popup with all entries; select username or password to insert at cursor position.
  * Add "Settings..." entry (bottom) to open the per-profile terminal settings dialog.
  * Wire the custom `JncActionProvider` into the existing `TerminalActionProvider` chain.
* [x] **Story 4.2: Browser Context Menu — Credentials & JS-Injection**
  * Extract credential logic into reusable, caching `CredentialsService` (one-time fetch at connection setup).
  * Replace default JavaFX WebView right-click menu with custom context menu (Back, Forward, Reload, Credentials...).
  * On "Credentials..." click, show the credentials dialog; selected value is injected via JavaScript into the focused input field on the web page.
  * Dispatch `input` and `change` events after injection for compatibility with React, Angular, Vue.

## Epic 5: Two-Browser Architecture (JavaFX WebView + JCEF)
**Goal:** Support both JavaFX WebView (default) and JCEF (optional) as browser backends, switchable at global, profile, and per-tab level.

* [x] **Story 5.1: BrowserBackend Interface + JavaFXWebViewBackend Refactoring**
  * Define `BrowserBackend` interface with methods: `loadUrl`, `reload`, `goBack`, `goForward`, `canGoBack`, `canGoForward`, `stopLoading`, `dispose`, `getViewComponent`, `setLocationListener`, `setTitleListener`, `setCertificateErrorHandler`, `executeScript`, `setPopupHandler`, `getContextMenuActions`.
  * Define `BrowserBackendType` enum (`JAVAFX_WEBVIEW`, `JCEF`).
  * Define `SslCertInfo` class for backend-agnostic certificate info.
  * Define `CertificateErrorHandler` functional interface.
  * Extract `JavaFXWebViewBackend` from existing `BrowserPanel`.
  * Refactor `BrowserPanel` to delegate to `BrowserBackend`.
  * Connect navigation buttons (back/forward/reload) through backend.
* [x] **Story 5.2: JCEF Integration**
  * Add JCEF dependency to `pom.xml` (natives JAR or ZIP extraction).
  * Implement `JCEFInitializer` with lazy `CefApp.startup()`.
  * Implement `JCEFBackend` with `CefLoadHandler.onCertificateError()`.
  * Use OSR (Off-Screen Rendering) for Swing embedding (`CefOSRComponent`).
  * SSL: `CertificateErrorHandler` → `CertificateStoreManager` (no Windows import).
  * Handle `onBeforePopup` for new browser tabs.
* [-] **Story 5.3: Backend Switching** (teilweise: JCEF ist jetzt Default via BrowserPanel, GlobalSettings.defaultBrowser + Settings-UI fehlen noch)
  * Add `defaultBrowser` field to `GlobalSettings` (JavaFX as default).
  * Add optional `browserBackend` override to `ConnectionProfile`.
  * Implement `BrowserPanel.switchBackend(BrowserBackendType)`.
  * Add "Browser wechseln" context menu entry in browser tab.
  * Resolve backend: Profile → Global → Default.
  * Add ComboBox to Settings dialog for default browser selection.
* [ ] **Story 5.4: CertificateStoreManager for JCEF**
  * Load PEM certificates from `~/.jnc/certs/` into memory cache.
  * Add `isKnown(cert)` method for fast fingerprint lookup.
  * Save newly accepted certificates as PEM files.
  * Integrate with JCEF's `onCertificateError()` callback.
* [ ] **Story 5.5: Native Build & Distribution**
  * Bundle JCEF natives ZIP in `resources/`.
  * Implement `extractNatives()` for first-run DLL extraction.
  * Platform detection (Windows 64-bit, macOS, Linux).
  * Extend launch scripts for JCEF natives library path.

## Epic 6: Browser-Finalisierung & UX-Politur
**Goal:** JCEF as sole browser engine, context menu polishing, bookmarks & history, session restore, keyboard shortcuts, auto-focus.

* [ ] **Story 6.1: JavaFX-Backend entfernen & GUI bereinigen**
 * `BrowserBackendType`: `JAVAFX_WEBVIEW`-Enum-Wert entfernen, auf `JCEF` reduzieren.
 * `BrowserPanel`: `switchBackend()` und Backend-Parameter entfernen.
 * `GlobalSettings`/`ConnectionProfile`: Backend-Felder beim Laden ignorieren.
 * GUI: Alle Backend-Auswahl-UI-Elemente entfernen.
* [ ] **Story 6.2: JSeparator-UI-Fehler im CredentialsService beheben**
 * Credentials-Dialog wird von CEF-Callback-Thread ausgelöst → EDT-Dispatch fehlt.
 * Fix: `onCredentialsRequested()` in `SwingUtilities.invokeLater()` wrappen.
* [ ] **Story 6.3: BrowserMenu (ersetzt EndpointPopupMenu)**
  * "Web Apps"-Knopf → vollständiges Browser-Menü: New Tab, Bookmarks, History, Open Tabs, Discover Endpoints.
  * Bookmarks: `Ctrl+D` in Adresszeile → gespeichert im `ConnectionProfile`, sortiert nach host/url.
  * History: HTML-Seite im Browser-Tab (wie Chrome), Einträge im `ConnectionProfile` gespeichert.
  * Open Tabs: dynamische Liste aus `BrowserTabManager`, Klick aktiviert Tab.
* [ ] **Story 6.4: Globale Tastenkombinationen**
 * `Alt+C` → Terminal-Tab, `Alt+N` → File-Transfer-Tab, `Alt+1`…`Alt+9` → Browser-Tabs.
* [ ] **Story 6.5: Tab-Persistenz (Session Restore, optional)**
  * `ConnectionProfile.restoreTabs` (boolean, default `true`, im Profil konfigurierbar) + `savedTabUrls` (List).
  * Beim Schließen: offene URLs speichern. Beim Öffnen: Tabs wiederherstellen (nur wenn `restoreTabs=true`).
* [ ] **Story 6.6: Tab-Close-Button-Fix**
 * `setTitleAt()` in `onTitleChanged` entfernt (überschrieb custom tab component).
 * Tab-Wechsel-Listener für automatischen Fokus.
* [ ] **Story 6.7: Automatische Fokussierung**
 * Neuer Browser-Tab → URL-Leiste fokussiert.
 * File-Transfer-Tab → linke Seite, erstes File.
 * SSH-Tab → JediTerm.
 * Tab-Wechsel → jeweiliger Content fokussiert.