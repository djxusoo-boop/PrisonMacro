import Foundation
import SwiftUI

/// Todas las macros del iPhone. Se guardan solas (con un pequeño retraso para
/// no escribir el disco en cada letra) y se importan/exportan en el mismo
/// formato que Android, con las capturas dentro.
@MainActor
final class Library: ObservableObject {
    @Published var projects: [MacroProject] { didSet { recordUndo(oldValue); scheduleSave() } }
    /// Aviso corto que muestra la pantalla principal.
    @Published var notice: String?
    /// Paso copiado o cortado (se pega como copia nueva).
    @Published var clipboard: MacroAction?
    @Published var clipboardCut = false
    /// Bloques plegados en el editor (no se guardan en la macro).
    @Published var collapsed: Set<String> = []

    private var pendingSave: DispatchWorkItem?
    private var undoStack: [String: [MacroProject]] = [:]
    private var redoStack: [String: [MacroProject]] = [:]
    private var lastChange: [String: Date] = [:]
    private var restoring = false

    init() {
        projects = LocalProjectStore.loadState().projects
    }

    // MARK: Acceso

    func project(_ id: String) -> MacroProject? { projects.first { $0.id == id } }

    func binding(_ id: String) -> Binding<MacroProject> {
        Binding(
            get: { self.projects.first { $0.id == id } ?? MacroProject(id: id) },
            set: { value in if let i = self.projects.firstIndex(where: { $0.id == id }) { self.projects[i] = value } }
        )
    }

    // MARK: Crear, duplicar, borrar

    @discardableResult
    func create(named name: String) -> MacroProject {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = MacroProject(name: clean.isEmpty ? "Macro \(projects.count + 1)" : String(clean.prefix(100)))
        projects.insert(p, at: 0)
        return p
    }

    @discardableResult
    func duplicate(_ id: String) -> MacroProject? {
        guard let i = projects.firstIndex(where: { $0.id == id }) else { return nil }
        var copy = projects[i]
        copy.id = UUID().uuidString.lowercased()
        copy.name = "\(copy.name) (copia)"
        projects.insert(copy, at: i + 1)
        return copy
    }

    func delete(_ id: String) { projects.removeAll { $0.id == id } }

    func rename(_ id: String, to name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, let i = projects.firstIndex(where: { $0.id == id }) else { return }
        projects[i].name = String(clean.prefix(100))
    }

    // MARK: Importar

    /// Añade lo que trae un archivo: una macro o una copia completa de Android.
    /// Si ya tienes una macro con el mismo identificador, se actualiza en vez de duplicarse.
    /// Devuelve el id de la primera macro recibida.
    @discardableResult
    func importData(_ data: Data, from source: String) throws -> String { // source: «del ordenador», «de un archivo»…
        guard data.count <= 60_000_000 else { throw TransferError.tooBig }
        let incoming: [MacroProject]
        if let backup = try? MacroJSON.decoder.decode(MacroBackup.self, from: data) {
            incoming = backup.projects
        } else if let single = try? MacroJSON.decoder.decode(MacroProject.self, from: data) {
            incoming = [single]
        } else {
            throw TransferError.notAMacro
        }
        let valid = incoming.filter { !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard let first = valid.first else { throw TransferError.notAMacro }
        var list = projects
        var added = 0, updated = 0
        for p in valid {
            if let i = list.firstIndex(where: { $0.id == p.id }) { list[i] = p; updated += 1 }
            else { list.insert(p, at: 0); added += 1 }
        }
        projects = list
        let what = valid.count == 1 ? "«\(first.name)»" : "\(valid.count) macros"
        notice = "📥 Recibido \(source): \(what)" + (updated > 0 && added == 0 ? " (actualizada)" : "")
        return first.id
    }

    // MARK: Exportar

    /// Una macro con sus capturas, en el formato que abre Android.
    func exportData(_ id: String) -> Data? {
        guard let p = project(id) else { return nil }
        return try? MacroJSON.encoder.encode(p)
    }

    /// Archivo temporal «Nombre.prisonmacro.json» para compartir (AirDrop, WhatsApp, Archivos…).
    func exportFile(_ id: String) -> URL? {
        guard let p = project(id), let data = exportData(id) else { return nil }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("compartir", isDirectory: true)
        try? FileManager.default.removeItem(at: folder)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent("\(safeFileName(p.name)).prisonmacro.json")
        do { try data.write(to: url, options: .atomic); return url } catch { return nil }
    }

    // MARK: Capturas

    /// Guarda un recorte en la macro y devuelve el id de la captura nueva.
    @discardableResult
    func addCapture(_ cropped: CroppedTemplateImage, to projectID: String, replacing templateID: String? = nil) -> String? {
        guard let i = projects.firstIndex(where: { $0.id == projectID }) else { return nil }
        var p = projects[i]
        let file = "template-\(UUID().uuidString.lowercased()).png"
        let template = MacroTemplate(file: file, x: cropped.x, y: cropped.y, width: cropped.width, height: cropped.height, screenWidth: cropped.screenWidth, screenHeight: cropped.screenHeight)
        p.imagesBase64[file] = cropped.png.base64EncodedString()
        let id: String
        if let templateID, let t = p.templates.firstIndex(where: { $0.id == templateID }) {
            let old = p.templates[t].template
            p.imagesBase64.removeValue(forKey: old.file)
            p.templates[t].template = template
            if !cropped.name.isEmpty { p.templates[t].name = cropped.name }
            if p.template?.file == old.file { p.template = template }
            id = templateID
        } else {
            id = UUID().uuidString.lowercased()
            let used = Set(p.templates.map(\.name))
            let name = cropped.name.isEmpty ? ((p.templates.count + 1)...(p.templates.count + 500)).map { "Imagen \($0)" }.first { !used.contains($0) } ?? "Imagen" : cropped.name
            // Si hay una carpeta abierta, el recorte nuevo va a ella.
            let open = UserDefaults.standard.string(forKey: "openFolder") ?? ""
            p.templates.append(SavedMacroTemplate(id: id, name: name, template: template, folder: open.isEmpty ? nil : open))
            if p.template == nil { p.template = template }
        }
        projects[i] = p
        return id
    }

    func removeCapture(_ templateID: String, from projectID: String) {
        guard let i = projects.firstIndex(where: { $0.id == projectID }),
              let saved = projects[i].templates.first(where: { $0.id == templateID }) else { return }
        var p = projects[i]
        p.templates.removeAll { $0.id == templateID }
        p.imagesBase64.removeValue(forKey: saved.template.file)
        p.actions = clearTemplateReferences(p.actions, templateID: templateID)
        if p.template?.file == saved.template.file { p.template = p.templates.first?.template }
        projects[i] = p
    }

    // MARK: Deshacer / rehacer (por macro)

    /// Guarda el estado anterior de cada macro que cambia. Los cambios seguidos
    /// (escribir un nombre letra a letra) cuentan como uno solo.
    private func recordUndo(_ old: [MacroProject]) {
        guard !restoring else { return }
        let now = Date()
        for before in old {
            guard let after = projects.first(where: { $0.id == before.id }), after != before else { continue }
            if now.timeIntervalSince(lastChange[before.id] ?? .distantPast) > 1.2 {
                undoStack[before.id, default: []].append(before)
                if undoStack[before.id]!.count > 60 { undoStack[before.id]!.removeFirst() }
            }
            lastChange[before.id] = now
            redoStack[before.id] = []
        }
    }

    func canUndo(_ id: String) -> Bool { !(undoStack[id] ?? []).isEmpty }
    func canRedo(_ id: String) -> Bool { !(redoStack[id] ?? []).isEmpty }

    func undo(_ id: String) {
        guard let previous = undoStack[id]?.popLast(), let i = projects.firstIndex(where: { $0.id == id }) else { return }
        redoStack[id, default: []].append(projects[i])
        restoring = true; projects[i] = previous; restoring = false
        lastChange[id] = .distantPast
    }

    func redo(_ id: String) {
        guard let next = redoStack[id]?.popLast(), let i = projects.firstIndex(where: { $0.id == id }) else { return }
        undoStack[id, default: []].append(projects[i])
        restoring = true; projects[i] = next; restoring = false
        lastChange[id] = .distantPast
    }

    /// Copia una captura de otra macro a esta (con su imagen). Devuelve el id nuevo.
    func copyCapture(_ templateID: String, from sourceID: String, to targetID: String) -> String? {
        guard let source = project(sourceID), let saved = source.templates.first(where: { $0.id == templateID }),
              let encoded = source.imagesBase64[saved.template.file], let i = projects.firstIndex(where: { $0.id == targetID }) else { return nil }
        // Si esta macro ya tiene la misma imagen, se reutiliza en vez de copiarla otra vez.
        if let same = projects[i].templates.first(where: { projects[i].imagesBase64[$0.template.file] == encoded }) { return same.id }
        var p = projects[i]
        var template = saved.template
        template.file = "template-\(UUID().uuidString.lowercased()).png"
        let id = UUID().uuidString.lowercased()
        p.templates.append(SavedMacroTemplate(id: id, name: saved.name, template: template, folder: saved.folder))
        p.imagesBase64[template.file] = encoded
        if p.template == nil { p.template = template }
        projects[i] = p
        return id
    }

    // MARK: Guardado

    private func scheduleSave() {
        pendingSave?.cancel()
        let snapshot = projects
        let work = DispatchWorkItem { LocalProjectStore.saveAll(snapshot) }
        pendingSave = work
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.6, execute: work)
    }

    /// Al pasar la app a segundo plano, se guarda ya.
    func saveNow() {
        pendingSave?.cancel(); pendingSave = nil
        LocalProjectStore.saveAll(projects)
    }
}

enum TransferError: LocalizedError {
    case tooBig, notAMacro
    var errorDescription: String? {
        switch self {
        case .tooBig: return "El archivo es demasiado grande."
        case .notAMacro: return "Ese archivo no es una macro de PrisonMacro."
        }
    }
}

func clearTemplateReferences(_ actions: [MacroAction], templateID: String) -> [MacroAction] {
    actions.map { original in
        var action = original
        if action.templateId == templateID { action.templateId = "" }
        action.children = clearTemplateReferences(action.children, templateID: templateID)
        action.otherwise = clearTemplateReferences(action.otherwise, templateID: templateID)
        return action
    }
}

func safeFileName(_ value: String) -> String {
    let result = value.replacingOccurrences(of: "[^A-Za-z0-9áéíóúñÁÉÍÓÚÑ_ -]", with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    return result.isEmpty ? "macro" : String(result.prefix(40))
}

// MARK: - Pasos dentro de pasos («Si se ve…», «Repetir»)

func findAction(_ list: [MacroAction], _ id: String) -> MacroAction? {
    for a in list {
        if a.id == id { return a }
        if let found = findAction(a.children, id) ?? findAction(a.otherwise, id) { return found }
    }
    return nil
}

/// Cambia (o borra, si `change` devuelve nil) el paso con ese id, esté donde esté.
func updateAction(_ list: [MacroAction], _ id: String, _ change: (MacroAction) -> MacroAction?) -> [MacroAction] {
    list.compactMap { a in
        if a.id == id { return change(a) }
        var copy = a
        copy.children = updateAction(a.children, id, change)
        copy.otherwise = updateAction(a.otherwise, id, change)
        return copy
    }
}

/// Aplica `change` a la lista que contiene el paso `id` (para subir, bajar, insertar al lado…).
private func editSiblings(_ list: [MacroAction], containing id: String, _ change: ([MacroAction], Int) -> [MacroAction]) -> [MacroAction] {
    if let i = list.firstIndex(where: { $0.id == id }) { return change(list, i) }
    return list.map { a in
        var copy = a
        copy.children = editSiblings(a.children, containing: id, change)
        copy.otherwise = editSiblings(a.otherwise, containing: id, change)
        return copy
    }
}

func moveAction(_ list: [MacroAction], _ id: String, by delta: Int) -> [MacroAction] {
    editSiblings(list, containing: id) { items, i in
        var items = items
        guard items.indices.contains(i + delta) else { return items }
        items.swapAt(i, i + delta); return items
    }
}

/// Pone el paso en el puesto [index] de su mismo bloque (al arrastrarlo).
func moveActionTo(_ list: [MacroAction], _ id: String, index: Int) -> [MacroAction] {
    editSiblings(list, containing: id) { items, i in
        var items = items
        let moving = items.remove(at: i)
        items.insert(moving, at: min(max(index, 0), items.count)); return items
    }
}

/// La lista (el bloque) donde está el paso, o nil si no está.
func siblingsOf(_ list: [MacroAction], _ id: String) -> [MacroAction]? {
    if list.contains(where: { $0.id == id }) { return list }
    for a in list { if let s = siblingsOf(a.children, id) ?? siblingsOf(a.otherwise, id) { return s } }
    return nil
}

func insertAction(_ list: [MacroAction], _ action: MacroAction, next id: String, after: Bool) -> [MacroAction] {
    editSiblings(list, containing: id) { items, i in var items = items; items.insert(action, at: after ? i + 1 : i); return items }
}

func duplicateAction(_ list: [MacroAction], _ id: String) -> [MacroAction] {
    guard let a = findAction(list, id) else { return list }
    return insertAction(list, renewIDs(a), next: id, after: true)
}

func addInside(_ list: [MacroAction], _ action: MacroAction, parent id: String, otherwise: Bool) -> [MacroAction] {
    updateAction(list, id) { p in var p = p; if otherwise { p.otherwise.append(action) } else { p.children.append(action) }; return p }
}

/// ¿Es el primero / el último de su lista? (para desactivar Subir / Bajar)
func siblingPosition(_ list: [MacroAction], _ id: String) -> (first: Bool, last: Bool)? {
    if let i = list.firstIndex(where: { $0.id == id }) { return (i == 0, i == list.count - 1) }
    for a in list { if let r = siblingPosition(a.children, id) ?? siblingPosition(a.otherwise, id) { return r } }
    return nil
}

/// El bloque («Si se ve…» o «Repetir») que contiene el paso, o nil si está en la lista principal.
func parentOf(_ list: [MacroAction], _ id: String) -> MacroAction? {
    for a in list {
        if a.children.contains(where: { $0.id == id }) || a.otherwise.contains(where: { $0.id == id }) { return a }
        if let p = parentOf(a.children, id) ?? parentOf(a.otherwise, id) { return p }
    }
    return nil
}

/// Saca el paso de su bloque y lo deja justo después del bloque.
func moveOutOfBlock(_ list: [MacroAction], _ id: String) -> [MacroAction] {
    guard let parent = parentOf(list, id), let action = findAction(list, id) else { return list }
    return insertAction(updateAction(list, id) { _ in nil }, action, next: parent.id, after: true)
}

/// Ids de todos los bloques (para plegar todo).
func containerIds(_ list: [MacroAction]) -> Set<String> {
    var ids = Set<String>()
    for a in list {
        if [.REPEAT, .IF_IMAGE, .IF_COLOR, .WHEN_IMAGE].contains(a.kind) { ids.insert(a.id) }
        ids.formUnion(containerIds(a.children)); ids.formUnion(containerIds(a.otherwise))
    }
    return ids
}


// MARK: - Borrar varios a la vez

extension Library {
    /// Quita varios recortes de la macro (aunque estén en uso: esos pasos se quedan sin imagen).
    func removeCaptures(_ ids: Set<String>, from projectID: String) {
        for id in ids { removeCapture(id, from: projectID) }
    }
}

/// Quita los pasos [ids] (con lo que tengan dentro).
func removeActions(_ list: [MacroAction], ids: Set<String>) -> [MacroAction] {
    list.filter { !ids.contains($0.id) }.map { a in
        var a = a; a.children = removeActions(a.children, ids: ids); a.otherwise = removeActions(a.otherwise, ids: ids); return a
    }
}

/// Todos los ids de pasos, también los de dentro de los bloques.
func allActionIds(_ list: [MacroAction]) -> Set<String> {
    list.reduce(into: Set<String>()) { set, a in set.insert(a.id); set.formUnion(allActionIds(a.children)); set.formUnion(allActionIds(a.otherwise)) }
}
