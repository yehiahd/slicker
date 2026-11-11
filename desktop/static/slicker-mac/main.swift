// Slicker native shell: starts the bundled server and shows the UI in its own window.
// Build: swiftc -O -target arm64-apple-macos12 main.swift -o Slicker -framework Cocoa -framework WebKit

import Cocoa
import WebKit

let serverURL = URL(string: "http://localhost:52342/")!

final class AppDelegate: NSObject, NSApplicationDelegate, WKUIDelegate, WKNavigationDelegate {
    private var window: NSWindow!
    private var webView: WKWebView!
    private var loadingLabel: NSTextField!
    private var server: Process?
    private var quitting = false
    private var restarts = 0

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildMenu()
        buildWindow()
        startServerIfNeeded()
        waitForServer(attempt: 0)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationWillTerminate(_ notification: Notification) {
        quitting = true
        // Only stop the server this app started.
        guard let server, server.isRunning else { return }
        server.terminate()
        server.waitUntilExit()
    }

    // MARK: Server

    private func startServerIfNeeded() {
        if isServerUp() { return }
        let macOS = Bundle.main.bundleURL.appendingPathComponent("Contents/MacOS")
        let serverDir = Bundle.main.bundleURL.appendingPathComponent("Contents/Resources/server")
        let logDir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Logs/Slicker")
        try? FileManager.default.createDirectory(at: logDir, withIntermediateDirectories: true)
        let logURL = logDir.appendingPathComponent("server.log")
        if !FileManager.default.fileExists(atPath: logURL.path) || restarts == 0 {
            FileManager.default.createFile(atPath: logURL.path, contents: nil)
        }
        let log = try? FileHandle(forWritingTo: logURL)
        _ = try? log?.seekToEnd()

        let process = Process()
        process.executableURL = macOS.appendingPathComponent("flipper-runtime")
        process.arguments = [".", "--no-open"]
        process.currentDirectoryURL = serverDir
        process.standardOutput = log
        process.standardError = log
        process.terminationHandler = { [weak self] _ in
            DispatchQueue.main.async { self?.serverExited() }
        }
        do {
            try process.run()
            server = process
        } catch {
            fail("Could not start the Slicker server: \(error.localizedDescription)")
        }
    }

    // The server stopped while the window is open: start it again (a few times at most).
    private func serverExited() {
        guard !quitting else { return }
        server = nil
        restarts += 1
        guard restarts <= 3 else {
            fail("The Slicker server keeps stopping. See ~/Library/Logs/Slicker/server.log for details.")
            return
        }
        webView.isHidden = true
        loadingLabel.stringValue = "Restarting Slicker…"
        loadingLabel.isHidden = false
        startServerIfNeeded()
        waitForServer(attempt: 0)
    }

    private func isServerUp() -> Bool {
        var request = URLRequest(url: serverURL)
        request.timeoutInterval = 1
        let done = DispatchSemaphore(value: 0)
        var ok = false
        URLSession.shared.dataTask(with: request) { _, response, _ in
            ok = (response as? HTTPURLResponse)?.statusCode == 200
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 2)
        return ok
    }

    private func waitForServer(attempt: Int) {
        DispatchQueue.global().async {
            let up = self.isServerUp()
            DispatchQueue.main.async {
                if up {
                    self.loadingLabel.isHidden = true
                    self.webView.isHidden = false
                    self.webView.load(URLRequest(url: serverURL))
                } else if attempt >= 90 || (self.server != nil && self.server?.isRunning == false) {
                    self.fail("Slicker did not start. See ~/Library/Logs/Slicker/server.log for details.")
                } else {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.waitForServer(attempt: attempt + 1) }
                }
            }
        }
    }

    private func fail(_ message: String) {
        let alert = NSAlert()
        alert.messageText = "Slicker"
        alert.informativeText = message
        alert.runModal()
        NSApp.terminate(nil)
    }

    // MARK: Window

    private func buildWindow() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default() // keeps plugin settings between launches
        webView = WKWebView(frame: .zero, configuration: config)
        webView.uiDelegate = self
        webView.navigationDelegate = self
        if #available(macOS 13.3, *) { webView.isInspectable = true }
        webView.isHidden = true

        loadingLabel = NSTextField(labelWithString: "Starting Slicker…")
        loadingLabel.font = .systemFont(ofSize: 15)
        loadingLabel.textColor = .secondaryLabelColor

        let content = NSView()
        content.wantsLayer = true
        content.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        for view in [webView!, loadingLabel!] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(view)
        }
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            webView.topAnchor.constraint(equalTo: content.topAnchor),
            webView.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            loadingLabel.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            loadingLabel.centerYAnchor.constraint(equalTo: content.centerYAnchor),
        ])

        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1400, height: 900),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false)
        window.title = "Slicker"
        window.minSize = NSSize(width: 900, height: 600)
        window.contentView = content
        window.setFrameAutosaveName("SlickerMainWindow")
        if window.frame.origin == .zero { window.center() }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    // Links that open a new window (docs, GitHub) go to the default browser.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { NSWorkspace.shared.open(url) }
        return nil
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url, url.host != "localhost", url.scheme?.hasPrefix("http") == true {
            NSWorkspace.shared.open(url)
            decisionHandler(.cancel)
        } else {
            decisionHandler(.allow)
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = NSAlert()
        alert.messageText = message
        alert.runModal()
        completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert()
        alert.messageText = message
        alert.addButton(withTitle: "OK")
        alert.addButton(withTitle: "Cancel")
        completionHandler(alert.runModal() == .alertFirstButtonReturn)
    }

    // File pickers (e.g. "Import Slicker file").
    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.canChooseDirectories = parameters.allowsDirectories
        completionHandler(panel.runModal() == .OK ? panel.urls : nil)
    }

    @objc func reload(_ sender: Any?) { webView.reload() }

    @objc func openInBrowser(_ sender: Any?) {
        NSWorkspace.shared.open(serverURL)
    }

    // MARK: Menu

    private func buildMenu() {
        let main = NSMenu()

        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "About Slicker", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Hide Slicker", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(withTitle: "Quit Slicker", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)

        let editItem = NSMenuItem()
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        main.addItem(editItem)

        let viewItem = NSMenuItem()
        let view = NSMenu(title: "View")
        let reloadItem = NSMenuItem(title: "Reload", action: #selector(reload(_:)), keyEquivalent: "r")
        reloadItem.target = self
        view.addItem(reloadItem)
        let browserItem = NSMenuItem(title: "Open in Browser", action: #selector(openInBrowser(_:)), keyEquivalent: "")
        browserItem.target = self
        view.addItem(browserItem)
        view.addItem(.separator())
        view.addItem(withTitle: "Enter Full Screen", action: #selector(NSWindow.toggleFullScreen(_:)), keyEquivalent: "f")
        viewItem.submenu = view
        main.addItem(viewItem)

        let windowItem = NSMenuItem()
        let windowMenu = NSMenu(title: "Window")
        windowMenu.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        windowMenu.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        windowItem.submenu = windowMenu
        main.addItem(windowItem)
        NSApp.windowsMenu = windowMenu

        NSApp.mainMenu = main
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
