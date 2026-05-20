package de.in.jnc.connection.browser.backend.jcef;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefFocusHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandlerAdapter;

import de.in.jnc.connection.browser.backend.BrowserBackend;
import de.in.jnc.connection.browser.backend.BrowserBackendType;
import de.in.jnc.connection.browser.backend.CertificateErrorHandler;
import de.in.jnc.connection.browser.backend.SslCertInfo;

/**
 * A {@link BrowserBackend} implementation backed by JCEF (Java Chromium Embedded Framework).
 * <p>
 * Each instance creates its own {@link CefClient} and {@link CefBrowser}, providing full session isolation between tabs.
 * <p>
 * <b>Windowed mode:</b> The browser runs in native windowed mode (not OSR), which uses CEF's internal rendering pipeline and avoids
 * JOGL/OpenGL compatibility issues on systems with certain GPU configurations. The native AWT {@code Canvas} is wrapped in a
 * {@link javax.swing.JPanel} for Swing embedding.
 * <p>
 * <b>Focus handling:</b> A {@link org.cef.handler.CefFocusHandler} ensures that keyboard focus can freely move between the CEF browser and
 * Swing components such as the URL address bar. When a non-browser component receives focus, {@link CefBrowser#setFocus(boolean)
 * setFocus(false)} is called to release the CEF keyboard hook.
 * <p>
 * <b>SSL certificate errors:</b> The
 * {@link org.cef.handler.CefRequestHandler#onCertificateError(CefBrowser, CefLoadHandler.ErrorCode, String, CefCallback)} callback
 * delegates to the registered {@link CertificateErrorHandler} which may prompt the user via
 * {@link de.in.jnc.connection.browser.CertificateWarningDialog}.
 * <p>
 * <b>Note:</b> JCEF's {@code onCertificateError} does <i>not</i> expose the server certificate chain, so {@link SslCertInfo} will be passed
 * as {@code null} to the handler.
 */
public class JCEFBackend implements BrowserBackend {

	private static final Logger LOGGER = LogManager.getLogger(JCEFBackend.class);

	// ── JavaScript templates (shared with JavaFXWebViewBackend) ────────

	/** Saves a reference to the currently focused DOM element. */
	private static final String JS_SAVE_ACTIVE_ELEMENT = "window.__jnc_activeElement = document.activeElement;";

	/** Injects a value into the previously saved active element. */
	private static final String JS_INSERT_VALUE_TEMPLATE = "(function(val) {" + "  var el = window.__jnc_activeElement;"
			+ "  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {" + "    el.focus();" + "    el.value = val;"
			+ "    el.dispatchEvent(new Event('input', { bubbles: true }));" + "    el.dispatchEvent(new Event('change', { bubbles: true }));" + "  }"
			+ "})('%s');";

	// ── Custom context menu command IDs ───────────────────────────────

	/** Must be between {@code MENU_ID_USER_FIRST} (26500) and {@code MENU_ID_USER_LAST} (28500). */
	private static final int CMD_CREDENTIALS = org.cef.callback.CefMenuModel.MenuId.MENU_ID_USER_FIRST + 1;

	private static final int CMD_DEV_TOOLS = org.cef.callback.CefMenuModel.MenuId.MENU_ID_USER_FIRST + 2;

	// ── Fields ─────────────────────────────────────────────────────────

	private final CefClient client;
	private final CefBrowser browser;
	private final CefRequestContext requestContext;
	private final JComponent viewComponent;

	/** Tracks whether this backend has been disposed. */
	private volatile boolean disposed;

	/**
	 * Tracks whether the CEF browser currently owns keyboard focus. Used to prevent infinite loops between {@code onGotFocus()} and
	 * {@code onTakeFocus()} callbacks. This is the official pattern from {@code tests.detailed.MainFrame.java}.
	 */
	private boolean browserFocus;

	/**
	 * Callback invoked when the user selects "Credentials..." from the context menu. Receives a value consumer that injects the selected
	 * credential into the focused input field.
	 */
	private Consumer<Consumer<String>> credentialsCallback;

	private Consumer<String> locationListener;
	private Consumer<String> titleListener;
	private CertificateErrorHandler certificateErrorHandler;
	private Consumer<String> popupHandler;

	/**
	 * Creates a new JCEF-backed browser instance that loads {@code url}.
	 * <p>
	 * {@link JCEFInitializer#initialize()} is called automatically if this is the first JCEF backend instance.
	 *
	 * @param url the initial URL to load (may be {@code "about:blank"})
	 */
	public JCEFBackend(String url) {
		JCEFInitializer.initialize();

		CefApp cefApp = CefApp.getInstance();
		this.client = cefApp.createClient();

		// Each tab gets its own CefRequestContext so that cookies,
		// localStorage, and session state are fully isolated between
		// tabs (like private browsing sessions per tab).
		this.requestContext = CefRequestContext.createContext(null);

		// Create browser in windowed mode (non-OSR) with isolated context.
		this.browser = client.createBrowser(url, false, false, requestContext);
		Component rawComponent = browser.getUIComponent();
		if (rawComponent instanceof JComponent) {
			this.viewComponent = (JComponent) rawComponent;
		} else {
			// Wrap in a JPanel if the component is AWT-only (defensive)
			javax.swing.JPanel wrapper = new javax.swing.JPanel(new java.awt.BorderLayout());
			wrapper.add(rawComponent, java.awt.BorderLayout.CENTER);
			this.viewComponent = wrapper;
		}

		installHandlers();

		LOGGER.debug("JCEFBackend created for URL: {}", url);
	}

	// ── Handler installation ──────────────────────────────────────────

	private void installHandlers() {
		installFocusHandler();
		installDisplayHandler();
		installRequestHandler(); // handles SSL certificate errors
		installLifeSpanHandler();
		installContextMenuHandler();
	}

	/**
	 * Focus handler that implements the official JCEF pattern from {@code tests.detailed.MainFrame.java} for windowed mode.
	 * <p>
	 * Three critical elements:
	 * <ol>
	 * <li>{@link KeyboardFocusManager#clearGlobalFocusOwner()} — forces AWT to re-evaluate the focus hierarchy so the native CEF HWND actually
	 * releases keyboard control</li>
	 * <li>{@code onGotFocus()} / {@code onTakeFocus()} on {@link CefFocusHandlerAdapter} — bidirectional focus transitions between CEF and
	 * Swing</li>
	 * <li>{@link #browserFocus} flag — prevents infinite callback loops between the two focus handlers</li>
	 * </ol>
	 */
	private void installFocusHandler() {
		client.addFocusHandler(new CefFocusHandlerAdapter() {
			@Override
			public void onGotFocus(CefBrowser browser) {
				if (browserFocus) {
					return; // prevent loop
				}
				browserFocus = true;
				LOGGER.trace("CEF browser gained focus");
			}

			@Override
			public void onTakeFocus(CefBrowser browser, boolean next) {
				LOGGER.trace("CEF browser takeFocus (next={})", next);
				browserFocus = false;
				// Force AWT to re-evaluate the global focus owner so the
				// native HWND actually releases keyboard control.
				KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
				if (viewComponent != null) {
					SwingUtilities.invokeLater(() -> {
						if (next) {
							viewComponent.transferFocus();
						} else {
							viewComponent.transferFocusBackward();
						}
					});
				}
			}
		});
	}

	/**
	 * Display handler for address (URL) and title change events.
	 */
	private void installDisplayHandler() {
		client.addDisplayHandler(new CefDisplayHandlerAdapter() {
			@Override
			public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
				if (locationListener != null) {
					locationListener.accept(url);
				}
			}

			@Override
			public void onTitleChange(CefBrowser browser, String title) {
				if (titleListener != null) {
					titleListener.accept(title);
				}
			}
		});
	}

	/**
	 * Request handler for SSL certificate errors.
	 * <p>
	 * JCEF's {@code onCertificateError} is on {@link org.cef.handler.CefRequestHandler}, not on {@link org.cef.handler.CefLoadHandler}. Unlike
	 * JavaFX WebView, the JCEF API does <b>not</b> expose the server certificate chain, so we pass {@code null} for the {@link SslCertInfo}.
	 */
	private void installRequestHandler() {
		client.addRequestHandler(new CefRequestHandlerAdapter() {
			@Override
			public boolean onCertificateError(CefBrowser browser, CefLoadHandler.ErrorCode certError, String requestUrl, CefCallback callback) {
				if (certificateErrorHandler == null) {
					LOGGER.warn("No CertificateErrorHandler registered – rejecting " + "certificate for: {}", requestUrl);
					callback.cancel();
					return true;
				}

				// JCEF does not expose the cert chain in this callback.
				// Pass null for SslCertInfo; the handler will work with the URL only.
				SslCertInfo sslInfo = new SslCertInfo(null, extractHostname(requestUrl));
				boolean accepted = certificateErrorHandler.onCertificateError(certError.name(), requestUrl, sslInfo);
				if (accepted) {
					callback.Continue();
				} else {
					callback.cancel();
				}
				return true;
			}
		});
	}

	/**
	 * Life-span handler for popup window requests (open URL in new tab).
	 */
	private void installLifeSpanHandler() {
		client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
			@Override
			public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
				LOGGER.debug("Popup requested: {}", targetUrl);
				if (popupHandler != null && targetUrl != null && !targetUrl.isEmpty()) {
					popupHandler.accept(targetUrl);
				}
				// Cancel the popup (we handle it by opening a new tab)
				return true;
			}
		});
	}

	/**
	 * Context menu handler that <b>extends</b> CEF's native context menu with custom entries (Credentials, Developer Tools) instead of
	 * replacing it. The native menu remains context-sensitive (different entries for links, images, text selections, etc.).
	 * <p>
	 * Custom command IDs are defined in {@link #CMD_CREDENTIALS} and {@link #CMD_DEV_TOOLS}. They are handled in {@code onContextMenuCommand}.
	 */
	private void installContextMenuHandler() {
		client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
			@Override
			public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
				// Save the focused DOM element for credential injection
				browser.executeJavaScript(JS_SAVE_ACTIVE_ELEMENT, "", 0);

				// Insert "Credentials..." at the very top
				model.insertItemAt(0, CMD_CREDENTIALS, "Credentials...");
				model.insertSeparatorAt(1);

				// Append "Developer Tools" at the bottom
				model.addSeparator();
				model.addItem(CMD_DEV_TOOLS, "JCEF – Developer Tools");
			}

			@Override
			public boolean onContextMenuCommand(CefBrowser browser, CefFrame frame, CefContextMenuParams params, int commandId, int eventFlags) {
				if (commandId == CMD_CREDENTIALS) {
					onCredentialsRequested();
					return true;
				}
				if (commandId == CMD_DEV_TOOLS) {
					browser.openDevTools();
					return true;
				}
				// Let CEF handle all other (native) commands
				return false;
			}
		});
	}

	// ── Helpers ───────────────────────────────────────────────────────

	/**
	 * Extracts the hostname from a URL string.
	 */
	static String extractHostname(String url) {
		if (url == null || url.isEmpty()) {
			return "unknown";
		}
		int schemeEnd = url.indexOf("://");
		int start = (schemeEnd >= 0) ? schemeEnd + 3 : 0;
		int pathStart = url.indexOf('/', start);
		int portStart = url.indexOf(':', start);
		int end = url.length();
		if (pathStart >= 0) {
			end = Math.min(end, pathStart);
		}
		if (portStart >= 0 && portStart < end) {
			end = portStart;
		}
		return (start < end) ? url.substring(start, end) : "unknown";
	}

	// ── BrowserBackend interface ──────────────────────────────────────

	@Override
	public BrowserBackendType getType() {
		return BrowserBackendType.JCEF;
	}

	@Override
	public void loadUrl(String url) {
		LOGGER.debug("JCEF loadUrl: {}", url);
		browser.loadURL(url);
	}

	@Override
	public void reload() {
		browser.reload();
	}

	@Override
	public void goBack() {
		browser.goBack();
	}

	@Override
	public void goForward() {
		browser.goForward();
	}

	@Override
	public boolean canGoBack() {
		return browser.canGoBack();
	}

	@Override
	public boolean canGoForward() {
		return browser.canGoForward();
	}

	@Override
	public void stopLoading() {
		browser.stopLoad();
	}

	@Override
	public void dispose() {
	    LOGGER.debug("Disposing JCEFBackend");
	    disposed = true;
	    browser.close(true);
	    client.dispose();
	    requestContext.dispose();
	}

	@Override
	public void releaseFocus() {
		if (disposed) {
			return;
		}
		browserFocus = false;
		// clearGlobalFocusOwner() is the critical call from MainFrame.java:
		// it forces AWT to re-evaluate the focus hierarchy so the native
		// CEF HWND actually releases keyboard control. Without this,
		// browser.setFocus(false) alone is ignored at the OS level.
		KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
		browser.setFocus(false);
	}

	@Override
	public void requestFocus() {
		if (disposed) {
			return;
		}
		// setFocus(true) internally calls canvas.setFocusable(true) and
		// canvas.requestFocus(), which triggers onGotFocus() → sets
		// browserFocus = true.
		browser.setFocus(true);
	}

	@Override
	public boolean isInitialized() {
		return !disposed;
	}

	@Override
	public JComponent getViewComponent() {
		return viewComponent;
	}

	@Override
	public void setLocationListener(Consumer<String> listener) {
		this.locationListener = listener;
	}

	@Override
	public void setTitleListener(Consumer<String> listener) {
		this.titleListener = listener;
	}

	@Override
	public void setCertificateErrorHandler(CertificateErrorHandler handler) {
		this.certificateErrorHandler = handler;
	}

	@Override
	public void executeScript(String script) {
		browser.executeJavaScript(script, "", 0);
	}

	@Override
	public void setPopupHandler(Consumer<String> handler) {
		this.popupHandler = handler;
		// Note: CefLifeSpanHandler.onBeforePopup is already installed,
		// so setting the handler after construction will be picked up
		// on the next popup request.
	}

	@Override
	public void setCredentialsCallback(Consumer<Consumer<String>> callback) {
		this.credentialsCallback = callback;
	}

	@Override
	public List<Action> getContextMenuActions() {
		List<Action> actions = new ArrayList<>();
		actions.add(new AbstractAction("\uD83D\uDD11  Credentials...") {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				onCredentialsRequested();
			}
		});
		actions.add(new AbstractAction("JCEF – Developer Tools") {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				browser.openDevTools();
			}
		});
		return actions;
	}

	// ── Credentials injection ──────────────────────────────────────────

	private void onCredentialsRequested() {
		if (credentialsCallback == null) {
			LOGGER.warn("Credentials requested but no callback is registered");
			return;
		}
		// Save the currently focused element before showing the dialog
		browser.executeJavaScript(JS_SAVE_ACTIVE_ELEMENT, "", 0);
		credentialsCallback.accept(this::insertValueIntoActiveElement);
	}

	/**
	 * Injects the given value into the previously focused input element on the web page via JavaScript execution.
	 */
	private void insertValueIntoActiveElement(String value) {
		String escaped = escapeJavaScriptString(value);
		String script = String.format(JS_INSERT_VALUE_TEMPLATE, escaped);
		browser.executeJavaScript(script, "", 0);
		LOGGER.debug("Injected credential value into active input element");
	}

	private static String escapeJavaScriptString(String input) {
		if (input == null) {
			return "";
		}
		return input.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
	}
}
