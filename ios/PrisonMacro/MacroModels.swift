import Foundation
import SwiftUI
import UniformTypeIdentifiers

enum MacroKind: String, Codable, CaseIterable {
    case GESTURE, WAIT, WAIT_IMAGE, REPEAT, CLICK_IMAGE, IF_IMAGE, IF_COLOR, HOME, BACK, RECENTS, CLOSE_ACTIVE, NOTIFICATIONS, QUICK_SETTINGS, OPEN_APP, STOP, WHEN_IMAGE
    /// «⏱ Reloj» (Android 0.9.8): marcar la hora y esperar hasta X desde la marca.
    case MARK_TIME, WAIT_SINCE_MARK
}

/// «⚡ Siempre que se vea»: qué hace la macro después de los pasos de la regla (igual que Android).
enum MacroAfterRule: String, Codable, CaseIterable {
    case CONTINUE, SKIP_STEP, EXIT_LOOP, RESTART, STOP
}

enum MacroSearchMode: String, Codable {
    case EXACT, ANYWHERE
}

enum MacroClickTarget: String, Codable, CaseIterable {
    case CENTER, OFFSET, FIXED
}

enum MacroClickStyle: String, Codable, CaseIterable {
    case SINGLE, DOUBLE, LONG
}

struct MacroPoint: Codable, Hashable {
    var x: Float
    var y: Float

    init(x: Float, y: Float) { self.x = x; self.y = y }

    private enum CodingKeys: String, CodingKey { case x, y }

    /// Android guarda cada punto como [x, y]; versiones antiguas del iPhone, como {"x":…,"y":…}. Se aceptan las dos.
    init(from decoder: Decoder) throws {
        if var pair = try? decoder.unkeyedContainer() {
            x = try pair.decode(Float.self); y = try pair.decode(Float.self)
        } else {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            x = try c.decode(Float.self, forKey: .x); y = try c.decode(Float.self, forKey: .y)
        }
    }

    /// Se guarda como Android, para que cualquier versión de Android lo abra.
    func encode(to encoder: Encoder) throws {
        var pair = encoder.unkeyedContainer(); try pair.encode(x); try pair.encode(y)
    }
}

struct MacroTemplate: Codable, Hashable {
    var file: String
    var x: Int
    var y: Int
    var width: Int
    var height: Int
    var screenWidth: Int
    var screenHeight: Int
}

struct SavedMacroTemplate: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var template: MacroTemplate
    /// Carpeta para ordenarla (igual que en Android). nil o "" = sin carpeta.
    var folder: String? = nil
}

struct MacroAction: Codable, Identifiable, Hashable {
    var id: String
    var kind: MacroKind
    var name: String
    var description: String
    var enabled: Bool
    var durationMs: Int64
    var points: [MacroPoint]
    var count: Int
    var children: [MacroAction]
    var otherwise: [MacroAction]
    var packageName: String
    var templateId: String
    var clickTarget: MacroClickTarget
    var clickStyle: MacroClickStyle
    var clickOffsetX: Float
    var clickOffsetY: Float
    var targetColor: Int32
    var colorTolerance: Int
    var colorMode: MacroSearchMode
    var colorX: Float
    var colorY: Float
    /// Android: si la imagen no aparece a tiempo, sigue en vez de parar.
    var skipIfMissing: Bool = false
    /// Android: tiempo máximo buscando la imagen (0 = el de la macro).
    var waitMs: Int64 = 0
    /// Espera antes de hacer este paso y después de hacerlo (para no necesitar pasos «Esperar»).
    var delayBeforeMs: Int64 = 0
    var delayAfterMs: Int64 = 0
    /// «⚡ Siempre que se vea»: qué hacer al terminar (waitMs = descanso antes de volver a actuar).
    var afterRule: MacroAfterRule = .CONTINUE
    /// «Esperar a que salga una imagen» al revés: espera a que YA NO se vea (waitMs seguidos sin verla).
    var untilGone: Bool = false

    init(id: String = UUID().uuidString.lowercased(), kind: MacroKind = .GESTURE, name: String = "Pulsación", description: String = "", enabled: Bool = true, durationMs: Int64 = 80, points: [MacroPoint] = [MacroPoint(x: 0.5, y: 0.5)], count: Int = 2, children: [MacroAction] = [], otherwise: [MacroAction] = [], packageName: String = "es.macrostudio", templateId: String = "", clickTarget: MacroClickTarget = .CENTER, clickStyle: MacroClickStyle = .SINGLE, clickOffsetX: Float = 0, clickOffsetY: Float = 0, targetColor: Int32 = -65536, colorTolerance: Int = 24, colorMode: MacroSearchMode = .ANYWHERE, colorX: Float = 0.5, colorY: Float = 0.5) {
        self.id = id; self.kind = kind; self.name = name; self.description = description; self.enabled = enabled; self.durationMs = durationMs; self.points = points; self.count = count; self.children = children; self.otherwise = otherwise; self.packageName = packageName; self.templateId = templateId; self.clickTarget = clickTarget; self.clickStyle = clickStyle; self.clickOffsetX = clickOffsetX; self.clickOffsetY = clickOffsetY; self.targetColor = targetColor; self.colorTolerance = colorTolerance; self.colorMode = colorMode; self.colorX = colorX; self.colorY = colorY
    }

    enum CodingKeys: String, CodingKey { case id, kind, name, description, enabled, durationMs, points, count, children, otherwise, packageName, templateId, clickTarget, clickStyle, clickOffsetX, clickOffsetY, targetColor, colorTolerance, colorMode, colorX, colorY, skipIfMissing, waitMs, delayBeforeMs, delayAfterMs, afterRule, untilGone }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), kind: try c.decode(MacroKind.self, forKey: .kind), name: try c.decode(String.self, forKey: .name), description: try c.decodeIfPresent(String.self, forKey: .description) ?? "", enabled: try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true, durationMs: try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 80, points: try c.decodeIfPresent([MacroPoint].self, forKey: .points) ?? [MacroPoint(x: 0.5, y: 0.5)], count: try c.decodeIfPresent(Int.self, forKey: .count) ?? 2, children: try c.decodeIfPresent([MacroAction].self, forKey: .children) ?? [], otherwise: try c.decodeIfPresent([MacroAction].self, forKey: .otherwise) ?? [], packageName: try c.decodeIfPresent(String.self, forKey: .packageName) ?? "es.macrostudio", templateId: try c.decodeIfPresent(String.self, forKey: .templateId) ?? "", clickTarget: try c.decodeIfPresent(MacroClickTarget.self, forKey: .clickTarget) ?? .CENTER, clickStyle: try c.decodeIfPresent(MacroClickStyle.self, forKey: .clickStyle) ?? .SINGLE, clickOffsetX: try c.decodeIfPresent(Float.self, forKey: .clickOffsetX) ?? 0, clickOffsetY: try c.decodeIfPresent(Float.self, forKey: .clickOffsetY) ?? 0, targetColor: try c.decodeIfPresent(Int32.self, forKey: .targetColor) ?? -65536, colorTolerance: try c.decodeIfPresent(Int.self, forKey: .colorTolerance) ?? 24, colorMode: try c.decodeIfPresent(MacroSearchMode.self, forKey: .colorMode) ?? .ANYWHERE, colorX: try c.decodeIfPresent(Float.self, forKey: .colorX) ?? 0.5, colorY: try c.decodeIfPresent(Float.self, forKey: .colorY) ?? 0.5)
        skipIfMissing = (try? c.decodeIfPresent(Bool.self, forKey: .skipIfMissing)) ?? false
        waitMs = (try? c.decodeIfPresent(Int64.self, forKey: .waitMs)) ?? 0
        delayBeforeMs = (try? c.decodeIfPresent(Int64.self, forKey: .delayBeforeMs)) ?? 0
        delayAfterMs = (try? c.decodeIfPresent(Int64.self, forKey: .delayAfterMs)) ?? 0
        afterRule = (try? c.decodeIfPresent(MacroAfterRule.self, forKey: .afterRule)) ?? .CONTINUE
        untilGone = (try? c.decodeIfPresent(Bool.self, forKey: .untilGone)) ?? false
    }
}

struct MacroProject: Codable, Identifiable, Hashable {
    var schemaVersion: Int
    var id: String
    var name: String
    var template: MacroTemplate?
    var mode: MacroSearchMode
    var threshold: Double
    var delayMs: Int64
    var intervalMs: Int64
    var timeoutMs: Int64
    var maxRuns: Int
    /// Campos de Android 0.4+: se conservan aunque el iPhone no los edite.
    var repeatCount: Int = 1
    var startOnImage: Bool = false
    var maxMinutes: Int = 30
    /// Sin fin: si un paso falla, la vuelta empieza otra vez en vez de parar (Android 0.8.9+).
    var keepGoing: Bool = true
    var templates: [SavedMacroTemplate]
    /// PNG data keyed by the file name used in the Android JSON schema.
    /// Android strips this field after decoding it and writes the images to its
    /// private template directory.
    var imagesBase64: [String: String]
    /// Con «⏱ reloj» la macro se guarda como versión 5 (como Android 0.9.8); sin él, como antes.
    var actions: [MacroAction] { didSet { schemaVersion = usesClock(actions) ? max(schemaVersion, 5) : min(schemaVersion, 4) } }

    init(id: String = UUID().uuidString.lowercased(), name: String = "Mi macro", template: MacroTemplate? = nil, mode: MacroSearchMode = .ANYWHERE, threshold: Double = 0.93, delayMs: Int64 = 500, intervalMs: Int64 = 0, timeoutMs: Int64 = 60_000, maxRuns: Int = 10, templates: [SavedMacroTemplate] = [], imagesBase64: [String: String] = [:], actions: [MacroAction] = [], schemaVersion: Int = 4) {
        self.schemaVersion = schemaVersion; self.id = id; self.name = name; self.template = template; self.mode = mode; self.threshold = threshold; self.delayMs = delayMs; self.intervalMs = intervalMs; self.timeoutMs = timeoutMs; self.maxRuns = maxRuns; self.templates = templates; self.imagesBase64 = imagesBase64; self.actions = actions
        // didSet no salta en el init: una macro creada ya con «⏱ reloj» también se guarda como versión 5.
        if usesClock(actions) { self.schemaVersion = max(schemaVersion, 5) }
    }

    enum CodingKeys: String, CodingKey { case schemaVersion, id, name, template, mode, threshold, delayMs, intervalMs, timeoutMs, maxRuns, repeatCount, startOnImage, maxMinutes, keepGoing, templates, imagesBase64, actions }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), name: try c.decode(String.self, forKey: .name), template: try c.decodeIfPresent(MacroTemplate.self, forKey: .template), mode: try c.decodeIfPresent(MacroSearchMode.self, forKey: .mode) ?? .ANYWHERE, threshold: try c.decodeIfPresent(Double.self, forKey: .threshold) ?? 0.93, delayMs: try c.decodeIfPresent(Int64.self, forKey: .delayMs) ?? 500, intervalMs: try c.decodeIfPresent(Int64.self, forKey: .intervalMs) ?? 0, timeoutMs: try c.decodeIfPresent(Int64.self, forKey: .timeoutMs) ?? 60_000, maxRuns: try c.decodeIfPresent(Int.self, forKey: .maxRuns) ?? 10, templates: try c.decodeIfPresent([SavedMacroTemplate].self, forKey: .templates) ?? [], imagesBase64: try c.decodeIfPresent([String: String].self, forKey: .imagesBase64) ?? [:], actions: try c.decodeIfPresent([MacroAction].self, forKey: .actions) ?? [], schemaVersion: try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 4)
        startOnImage = (try? c.decodeIfPresent(Bool.self, forKey: .startOnImage)) ?? false
        maxMinutes = (try? c.decodeIfPresent(Int.self, forKey: .maxMinutes)) ?? 30
        keepGoing = (try? c.decodeIfPresent(Bool.self, forKey: .keepGoing)) ?? true
        // Igual que Android: proyectos antiguos con pausa entre vueltas usaban maxRuns.
        if let value = try? c.decodeIfPresent(Int.self, forKey: .repeatCount) { repeatCount = value }
        else { repeatCount = intervalMs > 0 ? min(max(maxRuns, 1), 1000) : 1 }
    }

    func adding(_ action: MacroAction) -> MacroProject { var copy = self; copy.actions.append(action); return copy }
}

/// Copia de seguridad completa creada en Android (⚙ Ajustes › Copias de seguridad).
struct MacroBackup: Decodable {
    var prisonMacroBackup: Int
    var projects: [MacroProject]
}

struct CloudMacroProject: Identifiable, Hashable {
    var id: String
    var name: String
    var updatedAtMs: Int64
    var deviceId: String
}

struct MacroFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    static var writableContentTypes: [UTType] { [.json] }
    var data: Data

    init(data: Data = Data()) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

enum MacroJSON {
    static let encoder: JSONEncoder = {
        let value = JSONEncoder(); value.outputFormatting = [.prettyPrinted, .sortedKeys]; return value
    }()
    static let decoder = JSONDecoder()
}

struct LocalProjectState {
    var active: MacroProject
    var projects: [MacroProject]
}

private struct LocalProjectLibrary: Codable {
    var activeID: String
    var projects: [MacroProject]
}

/// Guarda varios proyectos editables en Application Support para que cerrar la
/// app o reiniciar el iPhone no borre las macros ni sus capturas embebidas.
/// Migra automáticamente el antiguo project.json de una sola macro.
enum LocalProjectStore {
    private static let legacyFileName = "project.json"
    private static let libraryFileName = "projects.json"

    private static var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = base.appendingPathComponent("PrisonMacro", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func loadState() -> LocalProjectState {
        let libraryURL = folder.appendingPathComponent(libraryFileName)
        if let data = try? Data(contentsOf: libraryURL),
           let library = try? MacroJSON.decoder.decode(LocalProjectLibrary.self, from: data),
           !library.projects.isEmpty {
            let active = library.projects.first(where: { $0.id == library.activeID }) ?? library.projects[0]
            return LocalProjectState(active: active, projects: library.projects)
        }
        let legacyURL = folder.appendingPathComponent(legacyFileName)
        guard let legacy = (try? Data(contentsOf: legacyURL)).flatMap({ try? MacroJSON.decoder.decode(MacroProject.self, from: $0) }) else {
            return LocalProjectState(active: MacroProject(), projects: [])
        }
        let state = LocalProjectState(active: legacy, projects: [legacy])
        save(active: legacy, projects: state.projects)
        return state
    }

    /// Guarda todas las macros (la primera queda como la última abierta).
    static func saveAll(_ projects: [MacroProject]) {
        guard let first = projects.first else {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(libraryFileName)); return
        }
        save(active: first, projects: projects)
    }

    static func save(active project: MacroProject, projects: [MacroProject]) {
        var values = projects
        if let index = values.firstIndex(where: { $0.id == project.id }) { values[index] = project }
        else { values.append(project) }
        let library = LocalProjectLibrary(activeID: project.id, projects: values)
        guard let data = try? MacroJSON.encoder.encode(library) else { return }
        try? data.write(to: folder.appendingPathComponent(libraryFileName), options: .atomic)
    }
}

// MARK: - Cuánto tarda la macro (igual que en Android, para enseñarlo mientras se crea)

/// Tiempo de un paso o de un trozo de macro: lo normal (`min`) y lo máximo (`max`).
/// Buscar una imagen no tiene un tiempo fijo: tarda lo que tarde en salir.
struct TimeSpan: Equatable {
    var min: Int64
    var max: Int64
    static let zero = TimeSpan(min: 0, max: 0)
    static func of(_ ms: Int64) -> TimeSpan { TimeSpan(min: ms, max: ms) }
    static func + (a: TimeSpan, b: TimeSpan) -> TimeSpan { TimeSpan(min: a.min + b.min, max: a.max + b.max) }
    static func * (a: TimeSpan, n: Int) -> TimeSpan { TimeSpan(min: a.min * Int64(n), max: a.max * Int64(n)) }
    var exact: Bool { min == max }
}

/// Lo que suele tardar una búsqueda de imagen y lo que espera Android tras Atrás, Inicio…
let searchMs: Int64 = 400
let systemMs: Int64 = 500

func stepTime(_ a: MacroAction, _ p: MacroProject) -> TimeSpan {
    guard a.enabled else { return .zero }
    let own: TimeSpan
    switch a.kind {
    case .WAIT, .GESTURE: own = .of(a.durationMs)
    case .CLICK_IMAGE:
        let tap: Int64 = a.clickStyle == .DOUBLE ? a.durationMs * 2 + 120 : a.clickStyle == .LONG ? Swift.max(500, a.durationMs) : a.durationMs
        let limit = a.waitMs > 0 ? a.waitMs : p.timeoutMs
        own = TimeSpan(min: searchMs + tap, max: limit + searchMs + tap)
    case .WAIT_IMAGE: own = a.untilGone ? TimeSpan(min: min(a.waitMs > 0 ? a.waitMs : 10_000, a.durationMs) + searchMs, max: a.durationMs + searchMs) : TimeSpan(min: searchMs, max: a.durationMs + searchMs)
    case .IF_IMAGE, .IF_COLOR:
        let yes = blockTime(a.children, p), no = blockTime(a.otherwise, p)
        own = TimeSpan(min: searchMs + Swift.min(yes.min, no.min), max: searchMs + Swift.max(yes.max, no.max))
    case .REPEAT:
        let body = blockTime(a.children, p)
        own = a.templateId.isEmpty ? body * a.count : TimeSpan(min: searchMs, max: (body.max + searchMs) * Int64(a.count))
    case .WHEN_IMAGE, .STOP, .MARK_TIME: own = .zero
    // Espera solo lo que falte desde la marca: de nada a todo el tiempo.
    case .WAIT_SINCE_MARK: own = TimeSpan(min: 0, max: a.durationMs)
    case .CLOSE_ACTIVE: own = .of(a.durationMs + 700)
    default: own = .of(systemMs)
    }
    return own + .of(a.delayBeforeMs + a.delayAfterMs)
}

func blockTime(_ items: [MacroAction], _ p: MacroProject) -> TimeSpan { items.reduce(.zero) { $0 + stepTime($1, p) } }

func roundTime(_ p: MacroProject) -> TimeSpan { .of(p.delayMs) + blockTime(p.actions, p) }

/// «0,5 s», «12 s», «5 min 32 s», «1 h 05 min».
func durationText(_ ms: Int64) -> String {
    if ms < 60_000 {
        if ms % 1000 == 0 || ms >= 10_000 { return "\((ms + 500) / 1000) s" }
        return String(format: "%.1f s", Double(ms) / 1000).replacingOccurrences(of: ".", with: ",")
    }
    if ms < 3_600_000 { let s = (ms + 500) / 1000; return s % 60 == 0 ? "\(s / 60) min" : "\(s / 60) min \(s % 60) s" }
    let m = (ms + 30_000) / 60_000
    return "\(m / 60) h " + String(format: "%02d", m % 60) + " min"
}

/// «12 s» o «≈0,5 s (hasta 1 min)».
/// Versión corta para la lista de pasos: solo lo normal («≈20 s»), sin el máximo.
func shortSpan(_ s: TimeSpan) -> String { s.exact ? durationText(s.min) : "≈\(durationText(s.min))" }

func spanText(_ s: TimeSpan) -> String { s.exact ? durationText(s.min) : "≈\(durationText(s.min)) (hasta \(durationText(s.max)))" }

/// Cada vuelta y el total, para la cabecera del editor.
func totalText(_ p: MacroProject) -> String {
    let round = roundTime(p)
    var parts = ["⏱ Cada vuelta: \(shortSpan(round))"]
    switch p.repeatCount {
    case 1: break
    case 0: parts.append(p.maxMinutes > 0 ? "sin fin (se para sola a los \(p.maxMinutes) min)" : "sin fin")
    default:
        let n = p.repeatCount
        parts.append("Total \(n) vueltas: \(shortSpan(round * n + .of(p.intervalMs * Int64(n - 1))))")
    }
    return parts.joined(separator: " · ")
}

/// Hasta el final de este paso de la lista principal (nil si va dentro de un bloque).
func elapsedUntil(_ id: String, _ p: MacroProject) -> TimeSpan? {
    guard let index = p.actions.firstIndex(where: { $0.id == id }) else { return nil }
    return p.actions[...index].reduce(TimeSpan.of(p.delayMs)) { $0 + stepTime($1, p) }
}

// MARK: - ⚡ Macro rápida (igual que en Android: core/QuickMacro.kt)

let zoobaPackage = "com.wildlife.games.battle.royale.free.zooba"
/// Paquete de relleno de «Abrir un juego» mientras no se elige (Android lo acepta, pero no es un juego).
let noAppPackage = "es.macrostudio"

/// La macro de siempre con 4 respuestas: abrir el juego, esperar a que cargue, pulsar jugar,
/// jugar un rato, cerrarlo y esperar. Sin fin; si algo falla, vuelve a empezar. Las imágenes, después.
func quickMacro(_ base: MacroProject, game: String, byIcon: Bool, playMs: Int64, everyMs: Int64, popups: Bool) -> MacroProject {
    let name = String((game.trimmingCharacters(in: .whitespaces).isEmpty ? "el juego" : game.trimmingCharacters(in: .whitespaces)).prefix(40))
    func step(_ kind: MacroKind, _ title: String) -> MacroAction { var a = defaultAction(kind); a.name = String(title.prefix(100)); return a }
    var steps: [MacroAction] = []
    if popups { var r = step(.WHEN_IMAGE, "⚡ Cerrar ventanas y anuncios"); r.waitMs = 3_000; steps.append(r) }
    if byIcon {
        var a = step(.CLICK_IMAGE, "1. Abrir \(name) (tocar su icono)"); a.waitMs = 30_000; a.skipIfMissing = true; steps.append(a)
    } else {
        var a = step(.OPEN_APP, "1. Abrir \(name)"); a.packageName = name.localizedCaseInsensitiveContains("zooba") ? zoobaPackage : noAppPackage; a.durationMs = 80; steps.append(a)
    }
    var load = step(.WAIT_IMAGE, "2. Esperar a que cargue"); load.durationMs = 90_000; load.skipIfMissing = true; steps.append(load)
    var play = step(.CLICK_IMAGE, "3. Pulsar el botón para jugar"); play.waitMs = 20_000; steps.append(play)
    var playing = step(.WAIT, "4. Jugando"); playing.durationMs = min(max(playMs, 1_000), 600_000); steps.append(playing)
    var close = step(.CLOSE_ACTIVE, "5. Cerrar \(name)"); close.durationMs = 1_000; steps.append(close)
    // Un paso «Esperar» llega a 10 min; lo que pase de ahí va como pausa entre vueltas (hasta 1 h).
    let wait = min(max(everyMs, 0), 600_000)
    if wait > 0 { var w = step(.WAIT, "6. Esperar hasta la siguiente vuelta"); w.durationMs = wait; steps.append(w) }
    var p = base
    p.name = String("\(name) en bucle".prefix(100))
    p.actions = steps; p.repeatCount = 0; p.keepGoing = true; p.maxMinutes = 0
    p.intervalMs = min(max(everyMs - wait, 0), 3_600_000)
    return p
}

// MARK: Tiempos escritos en nombres y notas (igual que TimeInText.kt de Android)

/// «Jugando (15 s)», «Esperar 4 minutos», «máximo 90 s»: al cambiar el tiempo de un paso,
/// también se cambia donde estaba escrito.
enum TimeInText {
    private static let regex = try! NSRegularExpression(
        pattern: "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)(\\s?)(milisegundos?|minutos?|mins?|ms|segundos?|segs?|horas?|s|h|m)(?![\\p{L}\\d])",
        options: [.caseInsensitive])

    private static func factor(_ unit: String) -> Int64 {
        switch unit.lowercased() {
        case "ms", "milisegundo", "milisegundos": return 1
        case "minuto", "minutos", "min", "mins", "m": return 60_000
        case "hora", "horas", "h": return 3_600_000
        default: return 1_000
        }
    }

    private static func parts(_ text: String) -> [(range: NSRange, number: String, space: String, unit: String, ms: Int64)] {
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            let number = ns.substring(with: m.range(at: 1)), space = ns.substring(with: m.range(at: 2)), unit = ns.substring(with: m.range(at: 3))
            guard let v = Double(number.replacingOccurrences(of: ",", with: ".")) else { return nil }
            return (m.range, number, space, unit, Int64((v * Double(factor(unit))).rounded()))
        }
    }

    /// Milisegundos de cada tiempo escrito en el texto.
    static func find(_ text: String) -> [Int64] { parts(text).map(\.ms) }

    /// Cambia cada tiempo que valga lo de antes por lo de ahora, de una sola pasada.
    static func retime(_ text: String, _ changes: [(Int64, Int64)]) -> String {
        let real = changes.filter { $0.0 != $0.1 && $0.0 > 0 && $0.1 > 0 }
        if real.isEmpty || text.isEmpty { return text }
        let ns = NSMutableString(string: text)
        for p in parts(text).reversed() {
            guard let now = real.first(where: { $0.0 == p.ms })?.1 else { continue }
            ns.replaceCharacters(in: p.range, with: write(now, p.number, p.space, p.unit))
        }
        return ns as String
    }

    private static func write(_ ms: Int64, _ oldNumber: String, _ space: String, _ oldUnit: String) -> String {
        let u = oldUnit.lowercased()
        let long = u.count > 4
        let comma = !oldNumber.contains(".")
        var n: String, unit: String
        if u == "ms" || u.hasPrefix("milisegundo") {
            n = "\(ms)"; unit = long ? (ms == 1 ? "milisegundo" : "milisegundos") : "ms"
        } else if ms >= 3_600_000 && ms % 3_600_000 == 0 {
            let h = ms / 3_600_000; n = "\(h)"; unit = long ? (h == 1 ? "hora" : "horas") : "h"
        } else if ms >= 60_000 && ms % 60_000 == 0 {
            let m = ms / 60_000; n = "\(m)"; unit = long ? (m == 1 ? "minuto" : "minutos") : "min"
        } else {
            var t = String(format: "%.3f", Double(ms) / 1000)
            while t.hasSuffix("0") { t.removeLast() }
            if t.hasSuffix(".") { t.removeLast() }
            n = comma ? t.replacingOccurrences(of: ".", with: ",") : t
            unit = long ? (ms == 1000 ? "segundo" : "segundos") : (u == "seg" || u == "segs" ? u : "s")
        }
        let upper = oldUnit == oldUnit.uppercased() && oldUnit != oldUnit.lowercased()
        return n + space + (upper ? unit.uppercased() : unit)
    }
}

/// Qué tiempos han cambiado al editar un paso: (antes, ahora). El primero manda si dos valían lo mismo.
func timeChanges(_ old: MacroAction, _ new: MacroAction, timeoutMs: Int64) -> [(Int64, Int64)] {
    func search(_ a: MacroAction) -> Int64 {
        switch a.kind {
        case .CLICK_IMAGE: return a.waitMs > 0 ? a.waitMs : timeoutMs
        case .WHEN_IMAGE: return a.waitMs > 0 ? a.waitMs : 3_000
        default: return a.waitMs
        }
    }
    return [(old.durationMs, new.durationMs), (old.delayAfterMs, new.delayAfterMs), (old.delayBeforeMs, new.delayBeforeMs), (search(old), search(new))]
        .filter { $0.0 != $0.1 }
}

// MARK: - ⏱ Reloj

let clockKinds: Set<MacroKind> = [.MARK_TIME, .WAIT_SINCE_MARK]
let defaultClockName = "reloj"

/// El nombre de la marca va en la nota del paso (vacía = «reloj»), igual que en Android.
func clockName(_ a: MacroAction) -> String {
    let n = String(a.description.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
    return n.isEmpty ? defaultClockName : n
}

/// ¿Usa la macro el «⏱ reloj»?
func usesClock(_ items: [MacroAction]) -> Bool {
    items.contains { clockKinds.contains($0.kind) || usesClock($0.children) || usesClock($0.otherwise) }
}
