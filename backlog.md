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

* [ ] **Story 2.1: System Tray Integration**
  * Implement a native Windows System Tray icon using AWT/Swing.
  * Add a basic context menu (Exit, Add Connection).
  * Ensure the application stays alive in the background when no windows are open.
* [ ] **Story 2.2: "Connect to Host" Dialog**
  * Build a Swing UI dialog to input Host, Username, Port, and Password/Key.
* [ ] **Story 2.3: Profile Persistence**
  * Implement logic to save and load connection profiles to/from the local disk.
  * Implement basic AES-256 encryption for saved passwords (Test Scope).

## Epic 3: The Three Pillars (Tools Integration)
**Goal:** Provide the core administrative interfaces within a node's window.

* [ ] **Story 3.1: The Native Terminal ("PuTTY" alternative)**
  * Create a Swing panel that connects to a shell channel via SSHJ.
  * Bind standard input/output to the UI to execute manual commands.
* [ ] **Story 3.2: File Transfer Interface ("WinSCP" alternative)**
  * Build a rudimentary UI panel showing remote files.
  * Implement upload/download logic using SSHJ's SCP/SFTP capabilities.
* [ ] **Story 3.3: Basic Browser Integration**
  * Embed a JCEF browser instance within a Swing container.
  * Ensure the browser correctly renders a standard test webpage.

## Epic 4: Automation Core (The JLock Magic)
**Goal:** Automate credential retrieval and application login.

* [ ] **Story 4.1: Background Credential Extraction**
  * Implement an automated SSH command execution (`show_credentials`) upon connection.
  * Build a parser to extract URLs, usernames, and passwords from the console output.
* [ ] **Story 4.2: Dynamic Tabs & JS-Injection**
  * Dynamically open a JCEF tab for each extracted URL.
  * Implement the DOM-Watcher and JavaScript injection logic to automatically fill in the login credentials.