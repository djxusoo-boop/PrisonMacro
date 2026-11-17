import SwiftUI
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// «📸 Mis capturas»: capturas enteras del juego guardadas en el iPhone y compartidas con la
/// cuenta (las mismas que en Android). El id es «shot-…» y es igual en todos los aparatos,
/// así que nunca se duplican.
@MainActor
final class ShotLibrary: ObservableObject {
    struct Shot: Identifiable, Hashable { let id: String; let url: URL }

    @Published private(set) var shots: [Shot] = []
    @Published var status = ""
    @Published var running = false

    private static var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = base.appendingPathComponent("PrisonMacro/shots", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    init() { reload(); loadFolders(); UserDefaults.standard.set("", forKey: "openFolder") }

    // MARK: Carpetas (para ordenar capturas y recortes; se guardan en este iPhone)

    @Published private(set) var folders: [String] = []
    @Published private(set) var folderOf: [String: String] = [:]
    /// Carpeta abierta: nil = todas, "" = sin carpeta. Lo nuevo (capturas y recortes) va a ella.
    @Published var openFolder: String? = nil { didSet { UserDefaults.standard.set(openFolder ?? "", forKey: "openFolder") } }

    private struct FolderFile: Codable { var folders: [String]; var of: [String: String] }
    private static var foldersFile: URL { folder.appendingPathComponent("carpetas.json") }

    private func loadFolders() {
        guard let data = try? Data(contentsOf: Self.foldersFile), let f = try? JSONDecoder().decode(FolderFile.self, from: data) else { return }
        folders = f.folders; folderOf = f.of
    }
    private func saveFolders() {
        let ids = Set(shots.map(\.id))
        let f = FolderFile(folders: folders, of: folderOf.filter { ids.contains($0.key) && folders.contains($0.value) })
        try? JSONEncoder().encode(f).write(to: Self.foldersFile, options: .atomic)
    }
    static func clean(_ name: String) -> String { String(name.split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(40)) }

    func folderOf(_ shot: Shot) -> String { folderOf[shot.id] ?? "" }

    @discardableResult func createFolder(_ name: String) -> String {
        let n = Self.clean(name); guard !n.isEmpty else { return "" }
        if !folders.contains(n) { folders.append(n); folders.sort { $0.lowercased() < $1.lowercased() }; saveFolders() }
        return n
    }
    /// Mete las capturas en [folder] ("" = sacarlas de su carpeta).
    func assign(_ ids: [String], to folder: String) {
        if !folder.isEmpty { createFolder(folder) }
        for id in ids { if folder.isEmpty { folderOf.removeValue(forKey: id) } else { folderOf[id] = folder } }
        saveFolders()
    }
    func renameFolder(_ old: String, to new: String) {
        let n = Self.clean(new); guard !n.isEmpty else { return }
        folders = Array(Set(folders.map { $0 == old ? n : $0 })).sorted { $0.lowercased() < $1.lowercased() }
        folderOf = folderOf.mapValues { $0 == old ? n : $0 }
        if openFolder == old { openFolder = n }
        saveFolders()
    }
    /// Quita la carpeta: sus capturas no se borran, pasan a «Sin carpeta».
    func deleteFolder(_ name: String) {
        folders.removeAll { $0 == name }; folderOf = folderOf.filter { $0.value != name }
        if openFolder == name { openFolder = nil }
        saveFolders()
    }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.folder, includingPropertiesForKeys: nil)) ?? []
        shots = files.filter { $0.lastPathComponent.hasPrefix("shot-") && $0.pathExtension == "png" }
            .map { Shot(id: $0.deletingPathExtension().lastPathComponent, url: $0) }
            .sorted { $0.id > $1.id }
    }

    func image(_ shot: Shot) -> UIImage? { UIImage(contentsOfFile: shot.url.path) }

    /// Guarda una captura nueva (en PNG, sin perder calidad) y devuelve su id.
    @discardableResult
    func add(_ image: UIImage) -> String? {
        guard let png = ImageCropView.normalize(image).pngData() else { return nil }
        let formatter = DateFormatter(); formatter.dateFormat = "yyyyMMdd-HHmmss-SSS"; formatter.locale = Locale(identifier: "en_US_POSIX")
        var id = "shot-" + formatter.string(from: Date())
        while FileManager.default.fileExists(atPath: Self.folder.appendingPathComponent("\(id).png").path) { id += "1" }
        do { try png.write(to: Self.folder.appendingPathComponent("\(id).png"), options: .atomic) } catch { return nil }
        reload()
        if let f = openFolder, !f.isEmpty { folderOf[id] = f; saveFolders() }
        return id
    }

    func deleteHere(_ shot: Shot) { try? FileManager.default.removeItem(at: shot.url); reload() }

    // MARK: Cuenta

    func deleteEverywhere(_ shot: Shot, cloud: CloudStore) async {
        deleteHere(shot)
        await cloud.deleteShot(shot.id)
    }

    /// Sube las que faltan en la cuenta y baja las que faltan aquí. Quita las borradas en otro aparato.
    func sync(cloud: CloudStore) async {
        guard !running else { return }
        guard cloud.isSignedIn else { status = "Entra en tu cuenta (⚙ Ajustes › Tu cuenta) para tener tus capturas en todos los aparatos."; return }
        running = true; defer { running = false }
        status = "Mirando las capturas de tu cuenta…"
        guard let remote = await cloud.shotIds() else { status = "No se pudo leer la cuenta (¿sin internet?)."; return }
        for shot in shots where remote.deleted.contains(shot.id) { try? FileManager.default.removeItem(at: shot.url) }
        reload()
        let local = Set(shots.map(\.id))
        let toUpload = shots.filter { !remote.existing.contains($0.id) && !remote.deleted.contains($0.id) }
        let toDownload = remote.existing.subtracting(local).subtracting(remote.deleted).sorted()
        var up = 0, down = 0
        for (i, shot) in toUpload.enumerated() {
            status = "Subiendo captura \(i + 1) de \(toUpload.count)…"
            if let data = try? Data(contentsOf: shot.url), data.count < 8_000_000, await cloud.uploadShot(id: shot.id, data: data) { up += 1 }
        }
        for (i, id) in toDownload.enumerated() {
            status = "Bajando captura \(i + 1) de \(toDownload.count)…"
            if let data = await cloud.downloadShot(id), UIImage(data: data) != nil {
                try? data.write(to: Self.folder.appendingPathComponent("\(id).png"), options: .atomic); down += 1
            }
        }
        reload()
        status = up + down == 0 ? "✔ Capturas al día con tu cuenta" : "✔ Sincronizado: \(up) subida(s), \(down) bajada(s)"
    }
}

/// Elegir una de «📸 Mis capturas» (o añadir desde Fotos / Archivos). Devuelve la imagen elegida.
struct ShotPickerSheet: View {
    let title: String
    let onChoose: (UIImage) -> Void
    @EnvironmentObject private var shots: ShotLibrary
    @EnvironmentObject private var cloud: CloudStore
    @Environment(\.dismiss) private var dismiss
    @State private var items: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var deleting: ShotLibrary.Shot?
    @State private var selecting = false
    @State private var marked: Set<String> = []
    @State private var confirmBulk = false
    @State private var moving = false

    var body: some View {
        let visible = shots.shots.filter { inFolder(shots.folderOf($0), shots.openFolder) }
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(title).font(.subheadline).foregroundStyle(.secondary)
                    FolderChips(folders: shots.folders, count: { f in shots.shots.filter { inFolder(shots.folderOf($0), f) }.count }, selected: $shots.openFolder,
                                onCreate: { n in let c = shots.createFolder(n); if !c.isEmpty { shots.openFolder = c } },
                                onRename: { shots.renameFolder($0, to: $1) }, onDelete: { shots.deleteFolder($0) })
                    if let f = shots.openFolder, !f.isEmpty { Text("📁 Estás en «\(f)»: las capturas nuevas se guardan aquí.").font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        PhotosPicker(selection: $items, maxSelectionCount: 20, matching: .images) {
                            Label("Añadir desde Fotos", systemImage: "photo.badge.plus").frame(maxWidth: .infinity)
                        }.buttonStyle(.borderedProminent)
                        Button { importing = true } label: { Label("Archivos", systemImage: "folder").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    }
                    HStack {
                        Text(shots.status.isEmpty ? "Tus capturas se comparten con tu cuenta (también en Android)." : shots.status)
                            .font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button { Task { await shots.sync(cloud: cloud) } } label: { if shots.running { ProgressView() } else { Label("Sincronizar", systemImage: "arrow.triangle.2.circlepath") } }
                            .buttonStyle(.bordered).disabled(shots.running)
                    }
                    if shots.shots.isEmpty {
                        Text("Todavía no tienes capturas. Añádelas desde Fotos, o haz «📸 Varias capturas» en Android y pulsa Sincronizar.")
                            .font(.subheadline).foregroundStyle(.secondary).padding(.vertical, 20)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 10)], spacing: 10) {
                        ForEach(visible) { shot in
                            Button {
                                if selecting { if marked.contains(shot.id) { marked.remove(shot.id) } else { marked.insert(shot.id) } }
                                else if let image = shots.image(shot) { onChoose(image); dismiss() }
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    ShotThumb(shot: shot)
                                        .overlay(alignment: .topLeading) {
                                            if selecting { Image(systemName: marked.contains(shot.id) ? "checkmark.circle.fill" : "circle").font(.title2).foregroundStyle(marked.contains(shot.id) ? Color.red : Color.white).padding(6) }
                                        }
                                    Text(shotLabel(shot.id) + (shots.openFolder == nil && !shots.folderOf(shot).isEmpty ? " · 📁 \(shots.folderOf(shot))" : "")).font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Menu("📁 Mover a carpeta") {
                                    ForEach(shots.folders, id: \.self) { f in Button(f) { shots.assign([shot.id], to: f) } }
                                    Button("Sin carpeta") { shots.assign([shot.id], to: "") }
                                }
                                Button(role: .destructive) { deleting = shot } label: { Label("Borrar", systemImage: "trash") }
                            }
                        }
                    }
                    if !shots.shots.isEmpty { Text("Mantén pulsada una captura para borrarla.").font(.caption2).foregroundStyle(.tertiary) }
                }
                .padding()
            }
            .navigationTitle("📸 Mis capturas")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { if !shots.shots.isEmpty { Button(selecting ? "Listo" : "Seleccionar") { selecting.toggle(); marked = [] } } }
            }
            .safeAreaInset(edge: .bottom) {
                if selecting {
                    HStack {
                        Button(marked.count == visible.count ? "☐ Ninguna" : "☑ Todas") { marked = marked.count == visible.count ? [] : Set(visible.map(\.id)) }.buttonStyle(.bordered)
                        Button("📁 Mover (\(marked.count))") { moving = true }.buttonStyle(.bordered).disabled(marked.isEmpty)
                        Button("🗑 Borrar (\(marked.count))", role: .destructive) { confirmBulk = true }.buttonStyle(.borderedProminent).tint(.red).disabled(marked.isEmpty)
                    }
                    .padding().frame(maxWidth: .infinity).background(.bar)
                }
            }
            .confirmationDialog("¿Borrar \(marked.count) captura(s)?", isPresented: $confirmBulk, titleVisibility: .visible) {
                let list = shots.shots.filter { marked.contains($0.id) }
                if cloud.isSignedIn { Button("Borrar en todos mis aparatos", role: .destructive) { Task { for s in list { await shots.deleteEverywhere(s, cloud: cloud) } }; marked = []; selecting = false } }
                Button(cloud.isSignedIn ? "Solo en este iPhone" : "Borrar", role: .destructive) { list.forEach { shots.deleteHere($0) }; marked = []; selecting = false }
            } message: { Text("No se puede deshacer.") }
            .modifier(MoveToFolder(isPresented: $moving, folders: shots.folders) { f in shots.assign(Array(marked), to: f); marked = []; selecting = false })
            .task { await shots.sync(cloud: cloud) }
            .onChange(of: items) { picked in
                guard !picked.isEmpty else { return }
                Task {
                    for item in picked { if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) { shots.add(image) } }
                    items = []
                    await shots.sync(cloud: cloud)
                }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.image], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                for url in urls {
                    let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    if let data = try? Data(contentsOf: url), let image = UIImage(data: data) { shots.add(image) }
                }
                Task { await shots.sync(cloud: cloud) }
            }
            .confirmationDialog("¿Borrar esta captura?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                if let shot = deleting {
                    if cloud.isSignedIn { Button("Borrar en todos los aparatos", role: .destructive) { Task { await shots.deleteEverywhere(shot, cloud: cloud) } } }
                    Button(cloud.isSignedIn ? "Solo en este iPhone" : "Borrar", role: .destructive) { shots.deleteHere(shot) }
                }
            }
        }
    }
}

struct ShotThumb: View {
    let shot: ShotLibrary.Shot
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Color.black
            if let image { Image(uiImage: image).resizable().scaledToFit() } else { ProgressView() }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: shot.id) {
            let url = shot.url
            image = await Task.detached { () -> UIImage? in
                guard let full = UIImage(contentsOfFile: url.path) else { return nil }
                let scale = 360 / max(full.size.width, 1)
                return full.preparingThumbnail(of: CGSize(width: full.size.width * scale, height: full.size.height * scale))
            }.value
        }
    }
}

// MARK: - Cuenta: capturas en la base de datos (mismo sitio que Android)

extension CloudStore {
    struct ShotIds { let existing: Set<String>; let deleted: Set<String> }

    func shotIds() async -> ShotIds? {
        guard let (token, user) = await session() else { return nil }
        do {
            let index = try await getRaw("users/\(user)/shotsIndex", token: token, shallow: true)
            let deleted = try await getRaw("users/\(user)/shotsDeleted", token: token, shallow: true)
            func keys(_ data: Data) -> Set<String> { Set(((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?.keys.filter { $0.hasPrefix("shot-") } ?? []) }
            return ShotIds(existing: keys(index), deleted: keys(deleted))
        } catch { return nil }
    }

    func uploadShot(id: String, data: Data) async -> Bool {
        guard let (token, user) = await session() else { return false }
        do {
            try await putRaw("users/\(user)/shots/\(id)", token: token, json: try JSONEncoder().encode(data.base64EncodedString()))
            let meta: [String: Any] = ["createdAtMs": Int64(Date().timeIntervalSince1970 * 1000), "sizeBytes": data.count, "device": "iPhone"]
            try await putRaw("users/\(user)/shotsIndex/\(id)", token: token, json: try JSONSerialization.data(withJSONObject: meta))
            return true
        } catch { return false }
    }

    func downloadShot(_ id: String) async -> Data? {
        guard let (token, user) = await session() else { return nil }
        guard let raw = try? await getRaw("users/\(user)/shots/\(id)", token: token, shallow: false),
              let text = try? JSONDecoder().decode(String.self, from: raw) else { return nil }
        return Data(base64Encoded: text)
    }

    func deleteShot(_ id: String) async {
        guard let (token, user) = await session() else { return }
        try? await putRaw("users/\(user)/shotsDeleted/\(id)", token: token, json: Data(String(Int64(Date().timeIntervalSince1970 * 1000)).utf8))
        try? await deleteRaw("users/\(user)/shotsIndex/\(id)", token: token)
        try? await deleteRaw("users/\(user)/shots/\(id)", token: token)
    }
}


/// Fila de carpetas: «Todas», «Sin carpeta», cada carpeta con cuántas tiene y «＋ Carpeta».
/// Con la carpeta abierta se puede renombrar o quitar (las imágenes no se borran).
struct FolderChips: View {
    let folders: [String]
    let count: (String?) -> Int
    @Binding var selected: String?
    var onCreate: ((String) -> Void)? = nil
    var onRename: ((String, String) -> Void)? = nil
    var onDelete: ((String) -> Void)? = nil
    @State private var creating = false
    @State private var renaming: String?
    @State private var deleting: String?
    @State private var newName = ""

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip("Todas (\(count(nil)))", selected == nil) { selected = nil }
                chip("Sin carpeta (\(count("")))", selected == "") { selected = "" }
                ForEach(folders, id: \.self) { f in chip("📁 \(f) (\(count(f)))", selected == f) { selected = f } }
                if onCreate != nil { Button("＋ Carpeta") { newName = ""; creating = true }.buttonStyle(.bordered).font(.subheadline) }
                if let s = selected, !s.isEmpty {
                    if onRename != nil { Button("Renombrar") { newName = s; renaming = s }.font(.subheadline) }
                    if onDelete != nil { Button("Quitar carpeta", role: .destructive) { deleting = s }.font(.subheadline) }
                }
            }
            .padding(.vertical, 2)
        }
        .alert("Nueva carpeta", isPresented: $creating) {
            TextField("Nombre (p. ej. «Lobby»)", text: $newName)
            Button("Crear") { onCreate?(newName) }
            Button("Cancelar", role: .cancel) {}
        }
        .alert("Nombre de la carpeta", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Nombre", text: $newName)
            Button("Guardar") { if let r = renaming { onRename?(r, newName) } }
            Button("Cancelar", role: .cancel) {}
        }
        .confirmationDialog("¿Quitar la carpeta «\(deleting ?? "")»?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Quitar carpeta", role: .destructive) { if let d = deleting { onDelete?(d) } }
        } message: { Text("Las imágenes que tiene NO se borran: pasan a «Sin carpeta».") }
    }

    private func chip(_ text: String, _ on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.subheadline).padding(.horizontal, 12).padding(.vertical, 6)
                .background(on ? Color.accentColor : Color.secondary.opacity(0.15), in: Capsule())
                .foregroundStyle(on ? Color.white : Color.primary)
        }.buttonStyle(.plain)
    }
}

/// Elegir a qué carpeta mover lo marcado (o crear una nueva, o sacarlo de su carpeta).
struct MoveToFolder: ViewModifier {
    @Binding var isPresented: Bool
    let folders: [String]
    let onPick: (String) -> Void
    @State private var creating = false
    @State private var newName = ""
    func body(content: Content) -> some View {
        content
            .confirmationDialog("📁 Mover a carpeta", isPresented: $isPresented, titleVisibility: .visible) {
                ForEach(folders, id: \.self) { f in Button("📁 \(f)") { onPick(f) } }
                Button("＋ Carpeta nueva…") { newName = ""; creating = true }
                Button("Sacar de su carpeta") { onPick("") }
            }
            .alert("Carpeta nueva", isPresented: $creating) {
                TextField("Nombre (p. ej. «Lobby»)", text: $newName)
                Button("Crear y mover") { let n = ShotLibrary.clean(newName); if !n.isEmpty { onPick(n) } }
                Button("Cancelar", role: .cancel) {}
            }
    }
}

/// ¿Esta carpeta entra en el filtro? (nil = todas, "" = sin carpeta).
func inFolder(_ folder: String?, _ filter: String?) -> Bool { filter == nil || (folder ?? "") == filter }
