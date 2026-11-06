import Foundation
import SwiftUI
import UniformTypeIdentifiers

enum MacroKind: String, Codable, CaseIterable {
    case GESTURE, WAIT, WAIT_IMAGE, REPEAT, CLICK_IMAGE, IF_IMAGE, IF_COLOR, HOME, BACK, RECENTS, CLOSE_ACTIVE, NOTIFICATIONS, QUICK_SETTINGS, OPEN_APP
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

    init(id: String = UUID().uuidString.lowercased(), kind: MacroKind = .GESTURE, name: String = "Pulsación", description: String = "", enabled: Bool = true, durationMs: Int64 = 80, points: [MacroPoint] = [MacroPoint(x: 0.5, y: 0.5)], count: Int = 2, children: [MacroAction] = [], otherwise: [MacroAction] = [], packageName: String = "es.macrostudio", templateId: String = "", clickTarget: MacroClickTarget = .CENTER, clickStyle: MacroClickStyle = .SINGLE, clickOffsetX: Float = 0, clickOffsetY: Float = 0, targetColor: Int32 = -65536, colorTolerance: Int = 24, colorMode: MacroSearchMode = .ANYWHERE, colorX: Float = 0.5, colorY: Float = 0.5) {
        self.id = id; self.kind = kind; self.name = name; self.description = description; self.enabled = enabled; self.durationMs = durationMs; self.points = points; self.count = count; self.children = children; self.otherwise = otherwise; self.packageName = packageName; self.templateId = templateId; self.clickTarget = clickTarget; self.clickStyle = clickStyle; self.clickOffsetX = clickOffsetX; self.clickOffsetY = clickOffsetY; self.targetColor = targetColor; self.colorTolerance = colorTolerance; self.colorMode = colorMode; self.colorX = colorX; self.colorY = colorY
    }

    enum CodingKeys: String, CodingKey { case id, kind, name, description, enabled, durationMs, points, count, children, otherwise, packageName, templateId, clickTarget, clickStyle, clickOffsetX, clickOffsetY, targetColor, colorTolerance, colorMode, colorX, colorY }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), kind: try c.decode(MacroKind.self, forKey: .kind), name: try c.decode(String.self, forKey: .name), description: try c.decodeIfPresent(String.self, forKey: .description) ?? "", enabled: try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true, durationMs: try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 80, points: try c.decodeIfPresent([MacroPoint].self, forKey: .points) ?? [MacroPoint(x: 0.5, y: 0.5)], count: try c.decodeIfPresent(Int.self, forKey: .count) ?? 2, children: try c.decodeIfPresent([MacroAction].self, forKey: .children) ?? [], otherwise: try c.decodeIfPresent([MacroAction].self, forKey: .otherwise) ?? [], packageName: try c.decodeIfPresent(String.self, forKey: .packageName) ?? "es.macrostudio", templateId: try c.decodeIfPresent(String.self, forKey: .templateId) ?? "", clickTarget: try c.decodeIfPresent(MacroClickTarget.self, forKey: .clickTarget) ?? .CENTER, clickStyle: try c.decodeIfPresent(MacroClickStyle.self, forKey: .clickStyle) ?? .SINGLE, clickOffsetX: try c.decodeIfPresent(Float.self, forKey: .clickOffsetX) ?? 0, clickOffsetY: try c.decodeIfPresent(Float.self, forKey: .clickOffsetY) ?? 0, targetColor: try c.decodeIfPresent(Int32.self, forKey: .targetColor) ?? -65536, colorTolerance: try c.decodeIfPresent(Int.self, forKey: .colorTolerance) ?? 24, colorMode: try c.decodeIfPresent(MacroSearchMode.self, forKey: .colorMode) ?? .ANYWHERE, colorX: try c.decodeIfPresent(Float.self, forKey: .colorX) ?? 0.5, colorY: try c.decodeIfPresent(Float.self, forKey: .colorY) ?? 0.5)
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
    var templates: [SavedMacroTemplate]
    /// PNG data keyed by the file name used in the Android JSON schema.
    /// Android strips this field after decoding it and writes the images to its
    /// private template directory.
    var imagesBase64: [String: String]
    var actions: [MacroAction]

    init(id: String = UUID().uuidString.lowercased(), name: String = "Mi secuencia", template: MacroTemplate? = nil, mode: MacroSearchMode = .ANYWHERE, threshold: Double = 0.93, delayMs: Int64 = 500, intervalMs: Int64 = 0, timeoutMs: Int64 = 60_000, maxRuns: Int = 10, templates: [SavedMacroTemplate] = [], imagesBase64: [String: String] = [:], actions: [MacroAction] = [], schemaVersion: Int = 4) {
        self.schemaVersion = schemaVersion; self.id = id; self.name = name; self.template = template; self.mode = mode; self.threshold = threshold; self.delayMs = delayMs; self.intervalMs = intervalMs; self.timeoutMs = timeoutMs; self.maxRuns = maxRuns; self.templates = templates; self.imagesBase64 = imagesBase64; self.actions = actions
    }

    enum CodingKeys: String, CodingKey { case schemaVersion, id, name, template, mode, threshold, delayMs, intervalMs, timeoutMs, maxRuns, templates, imagesBase64, actions }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), name: try c.decode(String.self, forKey: .name), template: try c.decodeIfPresent(MacroTemplate.self, forKey: .template), mode: try c.decodeIfPresent(MacroSearchMode.self, forKey: .mode) ?? .ANYWHERE, threshold: try c.decodeIfPresent(Double.self, forKey: .threshold) ?? 0.93, delayMs: try c.decodeIfPresent(Int64.self, forKey: .delayMs) ?? 500, intervalMs: try c.decodeIfPresent(Int64.self, forKey: .intervalMs) ?? 0, timeoutMs: try c.decodeIfPresent(Int64.self, forKey: .timeoutMs) ?? 60_000, maxRuns: try c.decodeIfPresent(Int.self, forKey: .maxRuns) ?? 10, templates: try c.decodeIfPresent([SavedMacroTemplate].self, forKey: .templates) ?? [], imagesBase64: try c.decodeIfPresent([String: String].self, forKey: .imagesBase64) ?? [:], actions: try c.decodeIfPresent([MacroAction].self, forKey: .actions) ?? [], schemaVersion: try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 4)
    }

    func adding(_ action: MacroAction) -> MacroProject { var copy = self; copy.actions.append(action); return copy }
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
        let legacy = (try? Data(contentsOf: legacyURL)).flatMap { try? MacroJSON.decoder.decode(MacroProject.self, from: $0) } ?? MacroProject()
        let state = LocalProjectState(active: legacy, projects: [legacy])
        save(active: legacy, projects: state.projects)
        return state
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
