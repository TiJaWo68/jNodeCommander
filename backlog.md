# jNodeCommander (jnc) - Development Roadmap & Backlog

This document outlines the development phases structured as Epics and User Stories. It serves as the primary backlog for GitHub Issues.

## Epic 1: Foundation & CI/CD Workflow
**Goal:** Establish a solid, building project skeleton with automated pipelines.

* [x] **Story 1.1: Project Initialization**
  * Initialize Git repository.
  * Create `pom.xml` configured for Java 25.
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
* [ ] **Story 3.3.1: Browser Tab (JCEF)**
  * Embed a JCEF browser instance as a closable dynamic tab within ConnectionFrame.
  * Add URL navigation bar (back, forward, refresh, address field).
  * Implement tab management: existing URL → select tab, new URL → create tab.
  * Provide "New Browser Tab" entry for manual URL entry.
  * Prepare for JS-injection capability (required for Epic 4).
* [ ] **Story 3.3.2: URL + Service Discovery (Web Apps Menu)**
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

* [ ] **Story 4.1: Background Credential Extraction**
  * Implement an automated SSH command execution (`show_credentials`) upon connection.
  * Build a parser to extract URLs, usernames, and passwords from the console output.
* [ ] **Story 4.2: Dynamic Tabs & JS-Injection**
  * Dynamically open a JCEF tab for each extracted URL.
  * Implement the DOM-Watcher and JavaScript injection logic to automatically fill in the login credentials.