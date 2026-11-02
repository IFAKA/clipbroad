import AppKit
import CryptoKit
import Foundation
import Network

private struct Payload: Codable {
    let kind: String
    let mime: String?
    let data: String
    let origin: String
}

private final class SecureChannel {
    private let connection: NWConnection
    private let key: SymmetricKey
    private let queue = DispatchQueue(label: "clipbroad.channel")
    private let onPayload: (Payload) -> Void
    private let onStateChange: (Bool) -> Void
    private var buffer = Data()
    private var didReportReady = false

    init(connection: NWConnection, secret: String, onPayload: @escaping (Payload) -> Void, onStateChange: @escaping (Bool) -> Void) {
        self.connection = connection
        let digest = SHA256.hash(data: Data(secret.utf8))
        self.key = SymmetricKey(data: Data(digest))
        self.onPayload = onPayload
        self.onStateChange = onStateChange
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                if !self.didReportReady {
                    self.didReportReady = true
                    self.onStateChange(true)
                }
                self.receive()
            case .failed, .cancelled:
                self.onStateChange(false)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func send(_ payload: Payload) {
        queue.async { [self] in
            guard let plain = try? JSONEncoder().encode(payload) else { return }
            do {
                let sealed = try AES.GCM.seal(plain, using: key)
                guard let combined = sealed.combined else { return }
                var frame = Data()
                var length = UInt32(combined.count).bigEndian
                frame.append(Data(bytes: &length, count: 4))
                frame.append(combined)
                connection.send(content: frame, completion: .contentProcessed { _ in })
            } catch { }
        }
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1_048_576) { [weak self] data, _, isComplete, _ in
            guard let self else { return }
            if let data { buffer.append(data) }
            while buffer.count >= 4 {
                let header = Array(buffer.prefix(4))
                let length = (UInt32(header[0]) << 24) | (UInt32(header[1]) << 16) | (UInt32(header[2]) << 8) | UInt32(header[3])
                guard length <= 1_048_576, buffer.count >= Int(length) + 4 else { break }
                let sealed = buffer.subdata(in: 4..<(Int(length) + 4))
                buffer.removeSubrange(0..<(Int(length) + 4))
                if let box = try? AES.GCM.SealedBox(combined: sealed),
                   let plain = try? AES.GCM.open(box, using: key),
                   let payload = try? JSONDecoder().decode(Payload.self, from: plain) {
                    onPayload(payload)
                }
            }
            if !isComplete { self.receive() }
        }
    }
}

final class ClipboardBridge {
    private let board = NSPasteboard.general
    private let origin = UUID().uuidString
    private var lastChange = NSPasteboard.general.changeCount
    private var secret = ""
    private var listener: NWListener?
    private var channels: [SecureChannel] = []
    private var locked = false
    private var onConnectionChange: ((Bool) -> Void)?
    private var onListenerStatusChange: ((String) -> Void)?

    func configure(secret: String) {
        self.secret = secret
        listener?.cancel()
        guard !secret.isEmpty else {
            onListenerStatusChange?("Set a pairing code to start the Mac service.")
            return
        }
        guard let listener = try? NWListener(using: .tcp, on: 8765) else {
            onListenerStatusChange?("Could not create the Mac network service.")
            return
        }
        self.listener = listener
        listener.service = NWListener.Service(name: "ClipBroad", type: "_clipbroad._tcp")
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            if case .failed(let error) = state {
                self.onListenerStatusChange?("Mac network service failed: \(error.localizedDescription)")
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            let channel = SecureChannel(
                connection: connection,
                secret: secret,
                onPayload: { [weak self] payload in self?.apply(payload) },
                onStateChange: { [weak self] connected in self?.onConnectionChange?(connected) }
            )
            channels.append(channel)
            channel.start()
        }
        listener.start(queue: DispatchQueue(label: "clipbroad.listener"))
    }

    func setConnectionChangeHandler(_ handler: @escaping (Bool) -> Void) {
        onConnectionChange = handler
    }

    func setListenerStatusHandler(_ handler: @escaping (String) -> Void) {
        onListenerStatusChange = handler
    }

    func watch() {
        DistributedNotificationCenter.default().addObserver(forName: NSNotification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in self?.locked = true }
        DistributedNotificationCenter.default().addObserver(forName: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in self?.locked = false }
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in self?.check() }
        RunLoop.main.add(timer, forMode: .common)
    }

    private func check() {
        guard !locked, board.changeCount != lastChange else { return }
        lastChange = board.changeCount
        let value: Payload?
        if let text = board.string(forType: .string), !text.isEmpty, !isSensitive(text) {
            value = Payload(kind: "text", mime: "text/plain", data: Data(text.utf8).base64EncodedString(), origin: origin)
        } else if let image = normalizedImageData() {
            value = Payload(kind: "image", mime: "image/png", data: image.base64EncodedString(), origin: origin)
        } else { value = nil }
        if let value { channels.forEach { $0.send(value) } }
    }

    private func apply(_ payload: Payload) {
        guard !locked, payload.origin != origin, let data = Data(base64Encoded: payload.data) else { return }
        board.clearContents()
        if payload.kind == "text", let text = String(data: data, encoding: .utf8) { board.setString(text, forType: .string) }
        else if payload.kind == "image" { board.setData(data, forType: .png) }
        lastChange = board.changeCount
    }

    private func isSensitive(_ text: String) -> Bool {
        let patterns = ["^sk-[A-Za-z0-9_-]{20,}$", "password\\s*[:=]", "token\\s*[:=]", "secret\\s*[:=]"]
        return patterns.contains { text.range(of: $0, options: .regularExpression) != nil }
    }

    private func normalizedImageData() -> Data? {
        if let png = board.data(forType: .png) { return png }
        if let jpeg = board.data(forType: NSPasteboard.PasteboardType("public.jpeg")), let image = NSImage(data: jpeg), let tiff = image.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff) { return rep.representation(using: .png, properties: [:]) }
        if let tiff = board.data(forType: .tiff), let image = NSImage(data: tiff), let normalized = image.tiffRepresentation,
           let rep = NSBitmapImageRep(data: normalized) { return rep.representation(using: .png, properties: [:]) }
        return nil
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    let bridge = ClipboardBridge()
    var status: NSStatusItem!
    var connectionStatusItem: NSMenuItem!
    func applicationDidFinishLaunching(_ notification: Notification) {
        status = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        status.button?.title = "CB"
        let menu = NSMenu()
        connectionStatusItem = menu.addItem(withTitle: "Waiting for Redmi…", action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Set pairing code…", action: #selector(setSecret), keyEquivalent: "")
        menu.addItem(withTitle: "Quit", action: #selector(NSApp.terminate), keyEquivalent: "q")
        status.menu = menu
        bridge.setConnectionChangeHandler { [weak self] connected in
            DispatchQueue.main.async {
                self?.connectionStatusItem.title = connected ? "Paired and connected" : "Waiting for Redmi…"
            }
        }
        bridge.setListenerStatusHandler { [weak self] message in
            DispatchQueue.main.async { self?.connectionStatusItem.title = message }
        }
        bridge.watch()
        if let saved = UserDefaults.standard.string(forKey: "pairingSecret") { bridge.configure(secret: saved) }
    }
    @objc func setSecret() {
        let alert = NSAlert(); alert.messageText = "Pair ClipBroad"; alert.informativeText = "Enter the same code on the Redmi."; let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 280, height: 24)); alert.accessoryView = field
        guard alert.runModal() == .alertFirstButtonReturn, !field.stringValue.isEmpty else { return }
        UserDefaults.standard.set(field.stringValue, forKey: "pairingSecret"); bridge.configure(secret: field.stringValue)
    }
}

let app = NSApplication.shared
let delegate = AppDelegate(); app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
