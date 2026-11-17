import Foundation

/// Pasa macros por la wifi de casa a través del ordenador que tiene abierto
/// `bash scripts/serve-updates.sh` (el «buzón»). El iPhone lo encuentra solo:
/// el ordenador se anuncia en la red como «_prisonmacro._tcp».
///
/// - Enviar: deja la macro en el ordenador; Android la recoge con ⚙ › Recibir.
/// - Recibir: trae lo último que se envió al ordenador (desde Android o desde aquí).
@MainActor
final class ComputerLink: NSObject, ObservableObject {
    static let port = 8765
    private static let key = "prisonmacro.computerAddress"

    /// «10.0.0.25:8765» o «MacBook-Air.local:8765». Vacío = buscarlo solo.
    @Published var address: String = UserDefaults.standard.string(forKey: ComputerLink.key) ?? "" {
        didSet { UserDefaults.standard.set(address, forKey: ComputerLink.key) }
    }
    @Published var foundName: String?

    private var browser: NetServiceBrowser?
    private var resolving: [NetService] = []
    private var waiter: CheckedContinuation<String?, Never>?

    // MARK: Enviar y recibir

    func send(_ data: Data) async throws -> String {
        try await withAddress { address in
            var request = URLRequest(url: URL(string: "http://\(address)/macros.json")!, timeoutInterval: 30)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            let (body, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if code == 404 || code == 501 { throw LinkError.oldServer }
            guard code == 200 else {
                let message = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["error"] as? String
                throw LinkError.server(message ?? "El ordenador respondió \(code)")
            }
            return address
        }
    }

    func receive() async throws -> Data {
        try await withAddress { address in
            let (data, response) = try await URLSession.shared.data(for: URLRequest(url: URL(string: "http://\(address)/macros.json")!, timeoutInterval: 30))
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if code == 404 { throw LinkError.nothingToReceive }
            guard code == 200 else { throw LinkError.server("El ordenador respondió \(code)") }
            return data
        }
    }

    /// Usa la dirección guardada; si no responde (o no hay), busca el ordenador en la wifi y reintenta.
    private func withAddress<T>(_ work: (String) async throws -> T) async throws -> T {
        if let saved = Self.normalize(address), await isAlive(saved) { return try await work(saved) }
        guard let found = await discover() else { throw LinkError.notFound }
        address = found
        return try await work(found)
    }

    private func isAlive(_ address: String) async -> Bool {
        guard let url = URL(string: "http://\(address)/hello") else { return false }
        do {
            let (_, response) = try await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 3))
            return (response as? HTTPURLResponse).map { $0.statusCode == 200 || $0.statusCode == 404 } ?? false
        } catch { return false }
    }

    /// «10.0.0.25» → «10.0.0.25:8765». Nil si está vacío o no es válido.
    static func normalize(_ text: String) -> String? {
        var clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.hasPrefix("http://") { clean.removeFirst(7) }
        while clean.hasSuffix("/") { clean.removeLast() }
        guard !clean.isEmpty, !clean.contains(where: { $0.isWhitespace || $0 == "/" || $0 == "@" }) else { return nil }
        let parts = clean.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count <= 2, !parts[0].isEmpty else { return nil }
        if parts.count == 2 { guard let p = Int(parts[1]), (1...65535).contains(p) else { return nil }; return clean }
        return "\(clean):\(port)"
    }

    // MARK: Buscar el ordenador (Bonjour)

    /// Busca durante unos segundos un ordenador con el buzón abierto. Devuelve «host:puerto».
    func discover() async -> String? {
        stopBrowsing()
        return await withCheckedContinuation { continuation in
            waiter = continuation
            let browser = NetServiceBrowser()
            browser.delegate = self
            self.browser = browser
            browser.searchForServices(ofType: "_prisonmacro._tcp.", inDomain: "local.")
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in self?.finish(nil) }
        }
    }

    private func finish(_ result: String?) {
        guard let waiter else { return }
        self.waiter = nil
        stopBrowsing()
        waiter.resume(returning: result)
    }

    private func stopBrowsing() {
        browser?.stop(); browser = nil
        resolving.forEach { $0.stop() }; resolving = []
    }
}

extension ComputerLink: NetServiceBrowserDelegate, NetServiceDelegate {
    nonisolated func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        Task { @MainActor in
            service.delegate = self
            resolving.append(service)
            service.resolve(withTimeout: 5)
        }
    }

    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        Task { @MainActor in
            // Preferimos la dirección IPv4 (10.x / 192.168.x); si no, el nombre «.local».
            let ipv4 = (sender.addresses ?? []).compactMap { data -> String? in
                data.withUnsafeBytes { raw -> String? in
                    guard let sa = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self), sa.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
                    var sin = raw.load(as: sockaddr_in.self)
                    var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                    inet_ntop(AF_INET, &sin.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
                    return String(cString: buffer)
                }
            }.first
            let host = ipv4 ?? sender.hostName.map { $0.hasSuffix(".") ? String($0.dropLast()) : $0 }
            guard let host else { return }
            foundName = sender.name
            finish("\(host):\(sender.port > 0 ? sender.port : Self.port)")
        }
    }
}

enum LinkError: LocalizedError {
    case notFound, nothingToReceive, oldServer, server(String)
    var errorDescription: String? {
        switch self {
        case .notFound:
            return "No encuentro el ordenador. Comprueba que el iPhone y el ordenador están en la misma wifi y que en el Mac está abierto el buzón (icono «Abrir buzón PrisonMacro» del Escritorio). También puedes escribir su dirección en ⚙ Ajustes."
        case .nothingToReceive:
            return "El ordenador todavía no tiene ninguna macro. Envíala primero desde Android (⚙ Ajustes › Enviar al ordenador) o desde otro iPhone."
        case .oldServer:
            return "El ordenador tiene una versión antigua del buzón. Ciérralo y vuelve a abrirlo con el icono «Abrir buzón PrisonMacro» del Escritorio del Mac."
        case .server(let text): return text
        }
    }
}
