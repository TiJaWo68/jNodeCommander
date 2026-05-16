# Epic 5: Two-Browser Architecture (JavaFX WebView + JCEF)

## Überblick

Browser-Tabs unterstützen zwei Backends:
- **JavaFX WebView** (Standard, aktuell implementiert)
- **JCEF** (Java Chromium Embedded Framework) — optional, performanter, vollständige SSL-Kontrolle

Umschaltbar auf drei Ebenen:
1. **Global Settings**: Default-Backend für alle neuen Tabs
2. **ConnectionProfile**: Backend-Override pro Verbindung
3. **Per-Tab**: Sofortiges Umschalten im laufenden Tab via Kontextmenü

---

## 1. Architektur

### 1.1 BrowserBackend Interface

```java
// src/main/java/de/in/jnc/connection/browser/backend/BrowserBackend.java
package de.in.jnc.connection.browser.backend;

public interface BrowserBackend {

    /** Typ des Backends */
    BrowserBackendType getType();

    /** Seite laden */
    void loadUrl(String url);

    /** Navigation */
    void reload();
    void goBack();
    void goForward();
    boolean canGoBack();
    boolean canGoForward();
    void stopLoading();

    /** Lifecycle */
    void dispose();
    boolean isInitialized();

    /** Swing-Komponente, die in BrowserPanel eingebettet wird */
    JComponent getViewComponent();

    /** Location-Änderungen (URL-Feld-Update) */
    void setLocationListener(Consumer<String> listener);

    /** Titel-Änderungen (Tab-Label-Update) */
    void setTitleListener(Consumer<String> listener);

    /** SSL-Zertifikatsfehler (Backend-spezifisch) */
    void setCertificateErrorHandler(CertificateErrorHandler handler);

    /** JavaScript / Credential Injection */
    void executeScript(String script);
    void setPopupHandler(java.util.function.Consumer<String> newTabCallback);

    /** Context-Menü-Einträge, die das Backend beisteuert */
    List<Action> getContextMenuActions();
}
```

### 1.2 Hilfstypen

```java
public enum BrowserBackendType {
    JAVAFX_WEBVIEW,
    JCEF
}

/** Zertifikatsfehler-Handler — jedes Backend implementiert ihn anders */
@FunctionalInterface
public interface CertificateErrorHandler {
    /**
     * @param certError   Fehlertyp (z.B. CERT_DATE_INVALID, CERT_AUTHORITY_INVALID)
     * @param requestUrl  Die aufgerufene URL
     * @param sslInfo     Zertifikatsdetails (Backend-spezifisch)
     * @return true wenn das Zertifikat akzeptiert wird, false wenn abgelehnt
     */
    boolean onCertificateError(String certError, String requestUrl, SslCertInfo sslInfo);
}

/** Einheitliche Zertifikatsinfo (Backend-agnostisch) */
public class SslCertInfo {
    private final X509Certificate[] chain;
    private final String hostname;
    // Konstruktor + Getter
}
```

### 1.3 Implementierungen

```
BrowserBackend (interface)
├── JavaFXWebViewBackend
│   └── Kapselt bestehenden JavaFX WebView Code
│   └── SSL: Windows-Import via CertificateStoreManager (wie implementiert)
│   └── View: JFXPanel
│
└── JCEFBackend
    ├── Nutzt CefBrowser + CefClient + CefLoadHandler
    ├── SSL: onCertificateError() → CertificateWarningDialog
    ├── View: CefBrowserOsr (OSR-Modus für Swing-Embedding)
    └── CefApp-Initialisierung beim ersten Start
```

### 1.4 Überarbeiteter BrowserPanel

```java
public class BrowserPanel extends JPanel {
    private BrowserBackend backend;  // aktuell aktives Backend
    private BrowserBackendType backendType;

    // Gemeinsame Toolbar (URL-Feld, Nav-Buttons) bleibt
    private final JToolBar toolbar;
    private final JTextField urlField;
    private final JButton backBtn;
    private final JButton forwardBtn;

    // Callbacks (backend-agnostisch)
    private NewTabCallback newTabCallback;
    private TitleChangeCallback titleCallback;
    private Consumer<Consumer<String>> credentialsCallback;

    public BrowserPanel(String url) {
        this(url, resolveDefaultBackendType());
    }

    public BrowserPanel(String url, BrowserBackendType type) {
        super(new BorderLayout());
        this.backendType = type;
        // Toolbar erstellen
        // Backend initialisieren
        // URL laden
    }

    /** Backend zur Laufzeit wechseln (per Kontextmenü) */
    public void switchBackend(BrowserBackendType newType) {
        String currentUrl = urlField.getText();
        backend.dispose();
        remove(backend.getViewComponent());
        this.backendType = newType;
        this.backend = createBackend(newType);
        add(backend.getViewComponent(), BorderLayout.CENTER);
        revalidate();
        repaint();
        backend.loadUrl(currentUrl);
    }

    private BrowserBackend createBackend(BrowserBackendType type) {
        return switch (type) {
            case JAVAFX_WEBVIEW -> new JavaFXWebViewBackend(this, certificateErrorHandler);
            case JCEF -> new JCEFBackend(this, certificateErrorHandler);
        };
    }
}
```

---

## 2. SSL-Handling pro Backend

### 2.1 JavaFX WebView

```
WebView lädt URL
    │
    ├── HTTP/1.1 → JDK SSL (useJVMSSLSocket=true)
    │   └── CertificateTrustManager.checkServerTrusted()
    │       ├── vertraut (JDK Trust Store / öffentliches CA) → OK
    │       └── unbekannt → CertificateWarningDialog
    │           ├── akzeptiert → CertificateStoreManager.importAcceptedCertificate()
    │           │   ├── ~/.jnc/certs/<host>.pem
    │           │   ├── JDK Trust Store (~/.jnc/truststore.p12)
    │           │   └── Windows Cert Store (certutil -user -addstore Root)
    │           └── abgelehnt → Fehler
    │
    └── HTTP/2 → Nativer Schannel (Windows SSL)
        └── Schannel prüft Windows Cert Store
            ├── vertraut (nach Windows-Import) → OK ✓
            └── unbekannt → SSL-Fehler → sslRetryAttempted=true
                └── BrowserPanel.attemptSslRetry()
                    └── HttpsURLConnection → CertificateTrustManager → Dialog
                        ├── akzeptiert → Windows-Import → WebView Reload ✓
                        └── abgelehnt → Ende
```

### 2.2 JCEF

```
CefBrowser lädt URL
    │
    └── Chromium SSL-Stack prüft Zertifikat
        ├── vertraut → OK ✓
        └── FEHLER → onCertificateError() wird gecalled
            │
            └── JCEFBackend.certificateErrorHandler()
                │
                ├── 1. Prüfe ~/.jnc/certs/ (SHA-256 Fingerprint)
                │   ├── bekannt → callback.Continue(true) ✓ (kein Dialog)
                │   └── unbekannt → weiter zu 2.
                │
                ├── 2. CertificateWarningDialog anzeigen
                │   ├── akzeptiert → callback.Continue(true)
                │   │   └── CertificateStoreManager.saveAcceptedCert()
                │   │       ├── PEM in ~/.jnc/certs/
                │   │       └── JDK Trust Store (optional)
                │   └── abgelehnt → callback.Continue(false)
                │
                └── ALLES IN DER JVM (kein Windows-Import nötig!) ✓
```

---

## 3. Settings-Integration

### 3.1 Global Settings

```java
// src/main/java/de/in/jnc/GlobalSettings.java
public class GlobalSettings {
    private BrowserBackendType defaultBrowser = BrowserBackendType.JAVAFX_WEBVIEW;
    // Getter/Setter
}
```

Settings-Dialog: Neue ComboBox "Standard-Browser" → JavaFX / JCEF

### 3.2 Connection Profile

```java
// src/main/java/de/in/jnc/ConnectionProfile.java
public class ConnectionProfile {
    private BrowserBackendType browserBackend; // null = Global Setting
    // Getter/Setter
}
```

### 3.3 Auflösungsreihenfolge

```java
// Resolve-Logik in ConnectionFrame oder BrowserTabManager
BrowserBackendType resolveBackendType(ConnectionProfile profile) {
    if (profile != null && profile.getBrowserBackend() != null) {
        return profile.getBrowserBackend();           // 1. Profil-Override
    }
    return GlobalSettings.getInstance().getDefaultBrowser(); // 2. Global Setting
}
```

---

## 4. JCEF-Backend Details

### 4.1 Abhängigkeiten

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.jcef</groupId>
    <artifactId>jcef</artifactId>
    <version>...</version>
</dependency>
```

JCEF erfordert native Bibliotheken (~80 MB pro Plattform):
- Windows: `jcef.dll`, `chromium_elf.dll`, `libcef.dll` u.a.
- macOS: `.dylib`, `.framework`
- Linux: `.so`

**Build-Strategie**: natives JCEF-Zip in `resources/jcef/natives/` entpacken und via `java.library.path` laden.

### 4.2 CefApp-Initialisierung

```java
public class JCEFInitializer {
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /** Wird einmalig beim ersten JCEF-Tab aufgerufen (lazy) */
    public static synchronized void initialize() {
        if (initialized.get()) return;

        String libPath = extractNatives();
        System.setProperty("java.library.path", libPath);

        CefApp.startup();
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = true;
        settings.cache_path = getCachePath(); // ~/.jnc/jcef-cache/
        settings.user_agent = "...";

        CefApp.getInstance().start(settings);
        initialized.set(true);
    }
}
```

### 4.3 OSR (Off-Screen-Rendering) in Swing

```java
public class JCEFBackend implements BrowserBackend {
    private CefBrowser browser;
    private CefClient client;
    private CefOSRComponent osrComponent; // JCEF's Swing-OSR-Komponente

    @Override
    public void loadUrl(String url) {
        browser.loadURL(url);
    }

    @Override
    public JComponent getViewComponent() {
        return osrComponent;
    }

    // LoadHandler with certificate callback
    private class JcefLoadHandler extends CefLoadHandlerAdapter {
        @Override
        public void onCertificateError(
                CertificateErrorCode certError,
                String requestUrl,
                CefSSLInfo sslInfo,
                CertificateErrorCallback callback) {

            SslCertInfo info = JcefSslConverter.toSslCertInfo(sslInfo);
            boolean accepted = certificateErrorHandler.onCertificateError(
                    certError.name(), requestUrl, info);

            callback.Continue(accepted);
        }

        @Override
        public void onLoadingStateChange(
                CefBrowser browser,
                boolean isLoading,
                boolean canGoBack,
                boolean canGoForward) {
            // Navigation-Buttons-Status aktualisieren
        }

        @Override
        public void onAddressChange(CefBrowser browser, String url) {
            locationListener.accept(url);
        }

        @Override
        public void onTitleChange(CefBrowser browser, String title) {
            titleListener.accept(title);
        }
    }
}
```

---

## 5. Per-Tab Backend-Wechsel

### 5.1 Kontextmenü-Erweiterung

Im Browser-Kontextmenü (rechtsklick):

```
─────────────────
Zurück
Vorwärts
Neu laden
─────────────────
Credentials...
─────────────────
▶ Browser wechseln → JavaFX WebView (current)
                   → Chromium (JCEF)
─────────────────
```

### 5.2 Wechsel-Logik

```java
// In BrowserPanel
private void onSwitchBackend() {
    BrowserBackendType newType = (backendType == BrowserBackendType.JAVAFX_WEBVIEW)
            ? BrowserBackendType.JCEF
            : BrowserBackendType.JAVAFX_WEBVIEW;

    String currentUrl = urlField.getText();
    boolean couldGoBack = backend.canGoBack();
    boolean couldGoForward = backend.canGoForward();

    // Altes Backend entsorgen
    backend.setLocationListener(null);
    backend.setTitleListener(null);
    backend.dispose();
    remove(backend.getViewComponent());

    // Neues Backend erstellen
    this.backendType = newType;
    this.backend = createBackend(newType);
    add(backend.getViewComponent(), BorderLayout.CENTER);

    // Callbacks neu setzen
    backend.setLocationListener(this::onLocationChanged);
    backend.setTitleListener(this::onTitleChanged);

    revalidate();
    repaint();

    // Gleiche URL laden
    if (currentUrl != null && !currentUrl.isEmpty()) {
        backend.loadUrl(currentUrl);
    }
}
```

---

## 6. Shared Code (für beide Backends nutzbar)

| Komponente | Nutzung |
|-----------|---------|
| [`CertificateWarningDialog`](src/main/java/de/in/jnc/connection/browser/CertificateWarningDialog.java) | Beide (JCEF direkter, WebView via HttpsURLConnection) |
| [`CertificateTrustManager`](src/main/java/de/in/jnc/connection/browser/CertificateTrustManager.java) | WebView (JDK SSL) + JDK-interne Verbindungen |
| [`CertificateStoreManager`](src/main/java/de/in/jnc/connection/browser/CertificateStoreManager.java) | Beide: PEM-Speicherung + JDK Trust Store + (nur WebView) Windows-Import |
| [`BrowserPanel`](src/main/java/de/in/jnc/connection/browser/BrowserPanel.java) | Überarbeitet: hält Backend-Referenz + gemeinsame Toolbar |
| [`BrowserTabManager`](src/main/java/de/in/jnc/connection/ConnectionFrame.java) | Unverändert: managed Tabs, delegiert an BrowserPanel |
| Navigation-Toolbar (URL-Feld, Buttons) | Beide: in BrowserPanel, steuert aktuelles Backend an |
| Context-Menü "Credentials..." | Beide: via `backend.executeScript()` |

---

## 7. Implementierungsreihenfolge

### Story 5.1: BrowserBackend-Interface + JavaFXWebViewBackend (Refactoring)
- [ ] `BrowserBackend` Interface definieren
- [ ] `BrowserBackendType` Enum
- [ ] `SslCertInfo` Klasse
- [ ] `JavaFXWebViewBackend` als Extraktion aus bestehendem BrowserPanel
- [ ] `BrowserPanel` auf Backend-Delegate umstellen
- [ ] Navigation-Buttons (back/forward/reload) über Backend steuern
- [ ] Kompilieren + Test: alle 130 Tests + RobotTest + BrowserSSLDebugTest

### Story 5.2: JCEF-Integration
- [ ] JCEF-Abhängigkeit in pom.xml (oder natives JAR)
- [ ] `JCEFInitializer` — einmalige CefApp-Startup-Logik (lazy)
- [ ] `JCEFBackend` — implementiert BrowserBackend
- [ ] NATIVE BITTE KOMMENTIERT: CefLoadHandler + onCertificateError
- [ ] OSR-Rendering in Swing (CefOSRComponent)
- [ ] SSL: CertificateErrorHandler → CertificateStoreManager (kein Windows-Import!)
- [ ] Test: JCEFBackend manuell testen (BrowserSSLDebugTest-ähnlich)

### Story 5.3: Backend-Umschaltung
- [ ] GlobalSettings.defaultBrowser (JavaFX / JCEF)
- [ ] ConnectionProfile.browserBackend (optionales Override)
- [ ] `BrowserPanel.switchBackend(BrowserBackendType)` — Laufzeitwechsel
- [ ] Kontextmenü-Eintrag "Browser wechseln" im Browser-Tab
- [ ] Resolve-Logik: Profil → Global → Default (JavaFX)
- [ ] SettingsDialog: ComboBox für Standard-Browser

### Story 5.4: CertificateStoreManager für JCEF
- [ ] `CertificateStoreManager.loadCertificatesFromDirectory()` — lädt PEMs in Memory-Cache
- [ ] `CertificateStoreManager.isKnown(cert)` — prüft ob Zertifikat bekannt ist
- [ ] `CertificateStoreManager.saveAcceptedCert(cert, hostname)` — speichert PEM in ~/.jnc/certs/
- [ ] (optional) JCEF: `CefRequestContext` mit AddTrustedCertificate?

### Story 5.5: Native Build + Distribution
- [ ] JCEF natives ZIP in resources/ ablegen
- [ ] `extractNatives()` — DLLs bei erstem Start entpacken
- [ ] Platform-Detection (Windows 64bit, macOS, Linux)
- [ ] Launch-Script-Erweiterung für JCEF natives Path
---

## 8. Offene Punkte

1. **JCEF-Version**: Welche JCEF-Release für JDK 25? (`jcef‑...‑windows-amd64‑...‑jni‑...`)
2. **OSR-Qualität**: JCEF OSR (Off-Screen-Rendering) hat manchmal Input-Fokus-Probleme
3. **CefApp-Lebenszyklus**: Einmal pro JVM gestartet, sauberes Shutdown via `CefApp.getInstance().dispose()`
4. **Ressourcen**: JCEF braucht ~150-300 MB RAM zusätzlich — akzeptabel?
5. **JCEF-Cache**: `~/.jnc/jcef-cache/` für Session-State, Cookies, etc.
6. **Popup-Handling**: JCEF hat eigenen Popup-Handler (`CefLifeSpanHandler.onBeforePopup`)
