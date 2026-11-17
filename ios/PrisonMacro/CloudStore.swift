import Foundation

@MainActor
final class CloudStore: ObservableObject {
    @Published var email = UserDefaults.standard.string(forKey: "prisonmacro.email") ?? ""
    @Published var status = "Sin cuenta conectada"
    @Published var projects: [CloudMacroProject] = []
    @Published var activeSessions = 0

    private let apiKey = "AIzaSyDIPdbK4zrpdhNlRCuv7GVpIQFcI2agiBQ"
    private let databaseRoot = "https://prison-ed653.firebaseio.com"
    private var idToken = UserDefaults.standard.string(forKey: "prisonmacro.idToken")
    private var uid = UserDefaults.standard.string(forKey: "prisonmacro.uid")
    private var refreshToken = UserDefaults.standard.string(forKey: "prisonmacro.refreshToken")
    private var tokenExpiry = UserDefaults.standard.double(forKey: "prisonmacro.tokenExpiry")
    private let deviceId: String = {
        if let value = UserDefaults.standard.string(forKey: "prisonmacro.deviceId") { return value }
        let value = UUID().uuidString.lowercased(); UserDefaults.standard.set(value, forKey: "prisonmacro.deviceId"); return value
    }()

    init() {
        if idToken != nil { status = "Cuenta conectada"; Task { await refresh() } }
    }

    func sendLink() async {
        let value = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard value.contains("@"), value.contains(".") else { status = "Escribe un correo válido"; return }
        do {
            var request = URLRequest(url: URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=\(apiKey)")!)
            request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["requestType": "EMAIL_SIGNIN", "email": value, "continueUrl": "https://prison-ed653.firebaseapp.com/__/auth/links", "canHandleCodeInApp": true])
            _ = try await requestData(request)
            email = value; UserDefaults.standard.set(value, forKey: "prisonmacro.pendingEmail"); status = "Enlace enviado. Copia el enlace del correo y pégalo aquí."
        } catch { status = "No se pudo enviar el enlace: \(error.localizedDescription)" }
    }

    func consumeLink(_ value: String) async {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let code = URLComponents(string: text)?.queryItems?.first(where: { $0.name == "oobCode" })?.value ?? text
        let mail = email.isEmpty ? (UserDefaults.standard.string(forKey: "prisonmacro.pendingEmail") ?? "") : email
        guard !mail.isEmpty else { status = "Escribe primero el correo que recibió el enlace"; return }
        do {
            var request = URLRequest(url: URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:signInWithEmailLink?key=\(apiKey)")!)
            request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["email": mail, "oobCode": code])
            let data = try await requestData(request)
            let response = try JSONDecoder().decode(AuthResponse.self, from: data)
            idToken = response.idToken; uid = response.localId
            UserDefaults.standard.set(response.idToken, forKey: "prisonmacro.idToken"); UserDefaults.standard.set(response.localId, forKey: "prisonmacro.uid"); UserDefaults.standard.removeObject(forKey: "prisonmacro.pendingEmail")
            status = "Cuenta conectada"; await refresh()
        } catch { status = "Enlace no válido o caducado: \(error.localizedDescription)" }
    }

    /// Cuenta con correo y contraseña (como en otras apps): crear o entrar, sin enlaces.
    /// Devuelve nil si va bien, o el motivo en castellano.
    func passwordAuth(email rawEmail: String, password: String, create: Bool) async -> String? {
        let value = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard value.contains("@"), value.contains(".") else { return "Escribe un correo válido" }
        guard password.count >= 6 else { return "La contraseña tiene que tener al menos 6 letras o números" }
        status = create ? "Creando la cuenta…" : "Entrando…"
        do {
            var request = URLRequest(url: URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:\(create ? "signUp" : "signInWithPassword")?key=\(apiKey)")!)
            request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["email": value, "password": password, "returnSecureToken": true])
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                let code = ((try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? [String: Any])?["message"] as? String ?? ""
                let text: String
                switch true {
                case code.hasPrefix("EMAIL_EXISTS"): text = "Ese correo ya tiene cuenta. Pulsa «Entrar» (si no recuerdas la contraseña, «¿Olvidaste la contraseña?»)."
                case code.hasPrefix("EMAIL_NOT_FOUND"): text = "No hay ninguna cuenta con ese correo. Pulsa «Crear cuenta»."
                case code.hasPrefix("INVALID_PASSWORD"), code.hasPrefix("INVALID_LOGIN_CREDENTIALS"): text = "Correo o contraseña incorrectos. Si es tu primera vez, pulsa «Crear cuenta»."
                case code.hasPrefix("WEAK_PASSWORD"): text = "Contraseña demasiado débil: usa al menos 6 letras o números."
                case code.hasPrefix("TOO_MANY_ATTEMPTS"): text = "Demasiados intentos. Espera unos minutos."
                default: text = "No se pudo entrar (\(code.isEmpty ? "sin respuesta" : code))."
                }
                status = text; return text
            }
            let auth = try JSONDecoder().decode(PasswordResponse.self, from: data)
            store(idToken: auth.idToken, uid: auth.localId, refresh: auth.refreshToken, expiresIn: auth.expiresIn)
            email = value; UserDefaults.standard.set(value, forKey: "prisonmacro.email")
            status = "Cuenta conectada"; await refresh()
            return nil
        } catch { status = "Sin conexión: \(error.localizedDescription)"; return status }
    }

    /// Correo para poner una contraseña nueva (se abre en el navegador).
    func resetPassword(email rawEmail: String) async -> String? {
        let value = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard value.contains("@") else { return "Escribe tu correo" }
        do {
            var request = URLRequest(url: URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=\(apiKey)")!)
            request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["requestType": "PASSWORD_RESET", "email": value])
            _ = try await requestData(request); return nil
        } catch { return "No se pudo enviar: \(error.localizedDescription)" }
    }

    private func store(idToken: String, uid: String, refresh: String?, expiresIn: String?) {
        self.idToken = idToken; self.uid = uid
        UserDefaults.standard.set(idToken, forKey: "prisonmacro.idToken"); UserDefaults.standard.set(uid, forKey: "prisonmacro.uid")
        if let refresh { refreshToken = refresh; UserDefaults.standard.set(refresh, forKey: "prisonmacro.refreshToken") }
        tokenExpiry = Date().timeIntervalSince1970 + (Double(expiresIn ?? "3600") ?? 3600) - 120
        UserDefaults.standard.set(tokenExpiry, forKey: "prisonmacro.tokenExpiry")
    }

    /// El acceso dura 1 hora: se renueva solo con el «refresh token».
    private func freshToken() async -> String? {
        if let idToken, Date().timeIntervalSince1970 < tokenExpiry || refreshToken == nil { return idToken }
        guard let refreshToken else { return idToken }
        do {
            var request = URLRequest(url: URL(string: "https://securetoken.googleapis.com/v1/token?key=\(apiKey)")!)
            request.httpMethod = "POST"; request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = "grant_type=refresh_token&refresh_token=\(refreshToken)".data(using: .utf8)
            let data = try await requestData(request)
            let r = try JSONDecoder().decode(RefreshResponse.self, from: data)
            store(idToken: r.id_token, uid: r.user_id, refresh: r.refresh_token, expiresIn: r.expires_in)
            return r.id_token
        } catch { return idToken }
    }

    /// Token válido y usuario, o nil si no hay cuenta.
    func session() async -> (String, String)? {
        _ = await freshToken()
        guard let token = idToken, let user = uid else { return nil }
        return (token, user)
    }

    func getRaw(_ path: String, token: String, shallow: Bool) async throws -> Data {
        try await requestData(URLRequest(url: URL(string: "\(databaseRoot)/\(path).json?auth=\(token)\(shallow ? "&shallow=true" : "")")!, timeoutInterval: 60))
    }

    func putRaw(_ path: String, token: String, json: Data) async throws {
        var request = URLRequest(url: URL(string: "\(databaseRoot)/\(path).json?auth=\(token)")!, timeoutInterval: 120)
        request.httpMethod = "PUT"; request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = json
        _ = try await requestData(request)
    }

    func deleteRaw(_ path: String, token: String) async throws {
        var request = URLRequest(url: URL(string: "\(databaseRoot)/\(path).json?auth=\(token)")!)
        request.httpMethod = "DELETE"
        _ = try await requestData(request)
    }

    private struct PasswordResponse: Decodable { let idToken: String; let localId: String; let refreshToken: String?; let expiresIn: String? }
    private struct RefreshResponse: Decodable { let id_token: String; let user_id: String; let refresh_token: String?; let expires_in: String? }

    func signOut() {
        let previousToken = idToken
        let previousUid = uid
        if let previousToken, let previousUid {
            let path = "users/\(previousUid)/devices/\(deviceId)"
            Task { try? await put(["name": "iPhone", "platform": "iOS", "lastSeenAtMs": Int64(Date().timeIntervalSince1970 * 1000), "revoked": true], path: path, token: previousToken) }
        }
        idToken = nil; uid = nil; refreshToken = nil; projects = []; activeSessions = 0
        UserDefaults.standard.removeObject(forKey: "prisonmacro.refreshToken")
        UserDefaults.standard.removeObject(forKey: "prisonmacro.idToken")
        UserDefaults.standard.removeObject(forKey: "prisonmacro.uid")
        status = "Sesión cerrada"
    }

    func upload(_ project: MacroProject) async {
        _ = await freshToken()
        guard let token = idToken, let user = uid else { status = "Conecta la cuenta antes de subir"; return }
        do {
            let payload = String(data: try MacroJSON.encoder.encode(project), encoding: .utf8) ?? ""
            let body: [String: Any] = ["name": project.name, "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000), "deviceId": deviceId, "payload": payload]
            try await put(body, path: "users/\(user)/projects/\(project.id)", token: token)
            status = "Proyecto subido"; await refresh()
        } catch { status = "No se pudo subir: \(error.localizedDescription)" }
    }

    func refresh() async {
        _ = await freshToken()
        guard let token = idToken, let user = uid else { return }
        do {
            try? await put(["name": "iPhone", "platform": "iOS", "lastSeenAtMs": Int64(Date().timeIntervalSince1970 * 1000), "revoked": false], path: "users/\(user)/devices/\(deviceId)", token: token)
            let data = try await get(path: "users/\(user)/projects", token: token)
            if data == Data("null".utf8) { projects = [] } else {
                let raw = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] ?? [:]
                projects = raw.compactMap { key, value in
                    guard let name = value["name"] as? String else { return nil }
                    return CloudMacroProject(id: key, name: name, updatedAtMs: (value["updatedAtMs"] as? NSNumber)?.int64Value ?? 0, deviceId: value["deviceId"] as? String ?? "")
                }.sorted { $0.updatedAtMs > $1.updatedAtMs }
            }
            let devices = try await get(path: "users/\(user)/devices", token: token)
            let rawDevices = (try? JSONSerialization.jsonObject(with: devices)) as? [String: [String: Any]] ?? [:]
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            activeSessions = rawDevices.values.filter { !($0["revoked"] as? Bool ?? false) && now - ((($0["lastSeenAtMs"] as? NSNumber)?.int64Value ?? 0)) <= 180_000 }.count
        } catch { status = "No se pudo actualizar: \(error.localizedDescription)" }
    }

    func download(_ cloud: CloudMacroProject) async -> MacroProject? {
        _ = await freshToken()
        guard let token = idToken, let user = uid else { status = "Conecta la cuenta antes de descargar"; return nil }
        do {
            let data = try await get(path: "users/\(user)/projects/\(cloud.id)/payload", token: token)
            let string = try JSONDecoder().decode(String.self, from: data)
            let project = try MacroJSON.decoder.decode(MacroProject.self, from: Data(string.utf8)); status = "Macro descargada"; return project   // mismo id: si ya la tienes, se actualiza
        } catch { status = "No se pudo descargar: \(error.localizedDescription)"; return nil }
    }

    private struct AuthResponse: Decodable { let idToken: String; let localId: String }
    private func requestData(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }
    private func get(path: String, token: String) async throws -> Data { try await requestData(URLRequest(url: URL(string: "\(databaseRoot)/\(path).json?auth=\(token)")!)) }
    private func put(_ value: [String: Any], path: String, token: String) async throws {
        var request = URLRequest(url: URL(string: "\(databaseRoot)/\(path).json?auth=\(token)")!); request.httpMethod = "PUT"; request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject: value); _ = try await requestData(request)
    }
}
