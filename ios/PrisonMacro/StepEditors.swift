import SwiftUI
import PhotosUI
import UIKit
import UniformTypeIdentifiers

// MARK: - Fotos y archivos: recortar capturas, marcar puntos y probar imágenes

/// Abre el carrete o Archivos y, según para qué sea, muestra el recorte, el marcador de
/// puntos o la prueba de la imagen. Cada hoja (sheet) tiene el suyo, porque iOS solo
/// deja presentar una ventana encima de otra.
@MainActor
final class PhotoFlow: ObservableObject {
    struct CropTask: Identifiable {
        let id = UUID()
        let image: UIImage
        var name = ""
        var replacing: SavedMacroTemplate?
        var then: ((String, String) -> Void)?
    }
    struct PointTask: Identifiable {
        let id = UUID()
        let image: UIImage
        let count: Int
        let then: ([MacroPoint], Int32) -> Void
    }
    struct TestTask: Identifiable {
        let id = UUID()
        let screenshot: UIImage
        let saved: SavedMacroTemplate
    }
    struct LibraryTask: Identifiable {
        let id = UUID()
        let selected: String?
        let then: (String, String) -> Void
    }
    private enum Goal {
        case capture(then: ((String, String) -> Void)?)
        case point(count: Int, then: ([MacroPoint], Int32) -> Void)
        case test(SavedMacroTemplate)
    }

    let projectID: String
    @Published var pickerShown = false
    /// Ventana «📸 Mis capturas» (capturas guardadas y compartidas con la cuenta).
    @Published var shotsShown = false
    var shotsTitle = ""
    private var chosenShot: UIImage?
    @Published var filesShown = false
    @Published var item: PhotosPickerItem?
    @Published var cropTask: CropTask?
    @Published var pointTask: PointTask?
    @Published var testTask: TestTask?
    @Published var libraryTask: LibraryTask?
    @Published var error: String?
    private var goal: Goal?

    init(projectID: String) { self.projectID = projectID }

    /// Foto del carrete → recortar → captura nueva. `then` recibe (id, nombre).
    func newCapture(then: ((String, String) -> Void)? = nil) { goal = .capture(then: then); shotsTitle = "Elige la captura donde está el botón; luego lo recortas."; shotsShown = true }
    /// Directamente desde el carrete, sin guardarla en «Mis capturas».
    func newCaptureFromPhotos(then: ((String, String) -> Void)? = nil) { goal = .capture(then: then); pickerShown = true }
    /// Igual, pero desde Archivos (Drive, iCloud, descargas…).
    func newCaptureFromFiles(then: ((String, String) -> Void)? = nil) { goal = .capture(then: then); filesShown = true }
    /// Elegir entre las imágenes guardadas (de esta macro o de otras).
    func chooseSaved(selected: String?, then: @escaping (String, String) -> Void) { libraryTask = LibraryTask(selected: selected, then: then) }
    /// Lo que hay que hacer al cerrarse «Elegir imagen» (recortar una captura, abrir Fotos o Archivos):
    /// iOS no deja abrir otra ventana encima.
    var afterLibrary: (() -> Void)?
    func libraryClosed() { let next = afterLibrary; afterLibrary = nil; next?() }
    /// Desde «Elegir imagen»: recortar esta captura entera y usar el recorte.
    func cropShotLater(_ image: UIImage, then: @escaping (String, String) -> Void) { afterLibrary = { [weak self] in self?.cropTask = CropTask(image: image, then: then) } }
    func photosLater(then: @escaping (String, String) -> Void) { afterLibrary = { [weak self] in self?.newCaptureFromPhotos(then: then) } }
    func filesLater(then: @escaping (String, String) -> Void) { afterLibrary = { [weak self] in self?.newCaptureFromFiles(then: then) } }
    /// Captura de la pantalla entera → tocar 1 punto (o 2 para deslizar).
    func pickPoints(_ count: Int, then: @escaping ([MacroPoint], Int32) -> Void) { goal = .point(count: count, then: then); shotsTitle = "Elige una captura de la pantalla entera y toca el sitio."; shotsShown = true }
    /// Captura de la pantalla entera → ¿la encontraría Android?
    func test(_ saved: SavedMacroTemplate) { goal = .test(saved); shotsTitle = "Elige una captura de la pantalla entera para comprobar si encuentra «\(saved.name)»."; shotsShown = true }

    /// Desde «Mis capturas»: se guarda y se usa al cerrarse la ventana (iOS no deja abrir otra encima).
    func shotChosen(_ image: UIImage) { chosenShot = image }
    func shotsClosed() {
        if let image = chosenShot { chosenShot = nil; handle(image) } else { goal = nil }
    }

    func recrop(_ saved: SavedMacroTemplate, image: UIImage) { cropTask = CropTask(image: image, name: saved.name, replacing: saved) }

    func load(_ item: PhotosPickerItem) async {
        defer { self.item = nil }
        guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
            error = "No se pudo abrir esa foto."; return
        }
        handle(image)
    }

    func loadFile(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { goal = nil; return }
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { error = "Ese archivo no es una imagen."; goal = nil; return }
        handle(image)
    }

    private func handle(_ image: UIImage) {
        switch goal {
        case .capture(let then): cropTask = CropTask(image: image, then: then)
        case .point(let count, let then): pointTask = PointTask(image: image, count: count, then: then)
        case .test(let saved): testTask = TestTask(screenshot: image, saved: saved)
        case nil: break
        }
        goal = nil
    }
}

/// Engancha a una vista el carrete, Archivos, el recorte, el marcador de puntos y la prueba.
struct PhotoFlowHost: ViewModifier {
    @ObservedObject var flow: PhotoFlow
    @EnvironmentObject var library: Library
    @EnvironmentObject var shots: ShotLibrary
    @EnvironmentObject var cloud: CloudStore

    func body(content: Content) -> some View {
        content
            .environmentObject(flow)
            .photosPicker(isPresented: $flow.pickerShown, selection: $flow.item, matching: .images)
            .onChange(of: flow.item) { item in if let item { Task { await flow.load(item) } } }
            .fileImporter(isPresented: $flow.filesShown, allowedContentTypes: [.image]) { flow.loadFile($0) }
            .sheet(isPresented: $flow.shotsShown, onDismiss: { flow.shotsClosed() }) {
                ShotPickerSheet(title: flow.shotsTitle) { flow.shotChosen($0) }.environmentObject(shots).environmentObject(cloud)
            }
            .sheet(item: $flow.cropTask) { task in
                ImageCropView(image: task.image, name: task.name,
                              screenWidth: task.replacing?.template.screenWidth, screenHeight: task.replacing?.template.screenHeight,
                              originX: task.replacing?.template.x ?? 0, originY: task.replacing?.template.y ?? 0) { cropped in
                    if let id = library.addCapture(cropped, to: flow.projectID, replacing: task.replacing?.id) {
                        let name = library.project(flow.projectID)?.templates.first { $0.id == id }?.name ?? cropped.name
                        task.then?(id, name)
                    }
                }
            }
            .sheet(item: $flow.pointTask) { task in PointPickerView(image: task.image, count: task.count, onDone: task.then) }
            .sheet(item: $flow.testTask) { task in
                ImageTestView(task: task, project: library.project(flow.projectID) ?? MacroProject())
            }
            .sheet(item: $flow.libraryTask, onDismiss: { flow.libraryClosed() }) { task in
                SavedImagePicker(projectID: flow.projectID, selected: task.selected, onChoose: task.then, flow: flow)
                    .environmentObject(library).environmentObject(shots).environmentObject(cloud)
            }
            .alert("PrisonMacro", isPresented: Binding(get: { flow.error != nil }, set: { if !$0 { flow.error = nil } })) {
                Button("Entendido") { flow.error = nil }
            } message: { Text(flow.error ?? "") }
    }
}

// MARK: - Sección de imagen de un paso (como en Android)

struct ImageSection: View {
    let title: String
    @Binding var templateId: String
    let project: MacroProject
    var onChosen: (String) -> Void = { _ in }
    @EnvironmentObject private var photos: PhotoFlow
    private var selected: SavedMacroTemplate? { project.templates.first { $0.id == templateId } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            HStack(spacing: 12) {
                Button { choose() } label: {
                    TemplateThumbnail(saved: selected, encoded: selected.flatMap { project.imagesBase64[$0.template.file] }, size: CGSize(width: 130, height: 90))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected == nil ? Color.orange : Color.accentColor, lineWidth: 2))
                }.buttonStyle(.plain)
                VStack(alignment: .leading, spacing: 4) {
                    if let selected {
                        Text(selected.name).font(.body.weight(.semibold)).lineLimit(2)
                        if let warning = templateWarning(selected, encoded: project.imagesBase64[selected.template.file]) {
                            Text("⚠ \(warning)").font(.caption).foregroundStyle(.orange)
                        }
                    } else {
                        Text("Toca para elegir o añadir la imagen").font(.body.weight(.semibold)).foregroundStyle(.orange)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8)], alignment: .leading, spacing: 8) {
                SmallButton(selected == nil ? "🖼 Elegir imagen" : "🖼 Cambiar", prominent: true) { choose() }
                if let selected {
                    SmallButton("✂ Recortar") { recrop(selected) }
                    SmallButton("🔍 ¿La encuentra?") { photos.test(selected) }
                }
            }
        }
    }

    private func choose() { photos.chooseSaved(selected: templateId, then: chosen) }
    private func chosen(_ id: String, _ name: String) { templateId = id; onChosen(name) }
    private func recrop(_ saved: SavedMacroTemplate) {
        guard let encoded = project.imagesBase64[saved.template.file], let data = Data(base64Encoded: encoded), let image = UIImage(data: data) else { return }
        photos.recrop(saved, image: image)
    }
}

struct SmallButton: View {
    let title: String
    var prominent = false
    let action: () -> Void
    init(_ title: String, prominent: Bool = false, action: @escaping () -> Void) { self.title = title; self.prominent = prominent; self.action = action }
    var body: some View {
        if prominent {
            Button(action: action) { Text(title).frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
        } else {
            Button(action: action) { Text(title).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }
    }
}

/// «🖼 Elegir imagen», todo en un sitio y con buscador (igual que en Android):
///  · ✂ Recortadas: las de esta macro y las de tus otras macros (se copian al elegirlas, sin repetidas).
///  · 📸 Capturas enteras: toca una y recortas el botón.
///  · Abajo: 📷 Fotos y 📁 Archivos.
struct SavedImagePicker: View {
    let projectID: String
    let selected: String?
    let onChoose: (String, String) -> Void
    let flow: PhotoFlow
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var shots: ShotLibrary
    @EnvironmentObject private var cloud: CloudStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var tab = 0

    private var q: String { query.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        let current = library.project(projectID)
        let filter = shots.openFolder
        let mine = (current?.templates ?? []).filter { (matches($0.name) || matches($0.folder ?? "")) && inFolder($0.folder, filter) }
        let others = otherImages(current).filter { (matches($0.saved.name) || matches($0.project.name)) && inFolder($0.saved.folder, filter) }
        let shotList = shots.shots.filter { (matches(shotLabel($0.id)) || matches(shots.folderOf($0))) && inFolder(shots.folderOf($0), filter) }
        let folders = Array(Set(shots.folders + (current?.templates ?? []).compactMap { $0.folder }.filter { !$0.isEmpty })).sorted { $0.lowercased() < $1.lowercased() }
        NavigationStack {
            VStack(spacing: 0) {
                if !folders.isEmpty {
                    FolderChips(folders: folders, count: { f in tab == 0 ? (current?.templates ?? []).filter { inFolder($0.folder, f) }.count : shots.shots.filter { inFolder(shots.folderOf($0), f) }.count }, selected: $shots.openFolder)
                        .padding(.horizontal).padding(.top, 6)
                }
                Picker("Qué", selection: $tab) {
                    Text("✂ Recortadas (\(mine.count + others.count))").tag(0)
                    Text("📸 Capturas enteras (\(shotList.count))").tag(1)
                }
                .pickerStyle(.segmented).padding(.horizontal).padding(.vertical, 8)
                ScrollView {
                    if tab == 0 {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("De esta macro (\(mine.count))").font(.headline)
                            if mine.isEmpty { Text(q.isEmpty ? "Esta macro todavía no tiene recortes." : "Ninguno con «\(q)».").font(.subheadline).foregroundStyle(.secondary) }
                            grid(mine.map { ($0, current ?? MacroProject(), false) })
                            if !others.isEmpty {
                                Text("De tus otras macros (\(others.count))").font(.headline).padding(.top, 6)
                                Text("Al elegir una, se copia a esta macro.").font(.caption).foregroundStyle(.secondary)
                                grid(others.map { ($0.saved, $0.project, true) })
                            }
                        }.padding(.horizontal)
                    } else {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text(shots.status.isEmpty ? "Toca una captura y recorta el botón." : shots.status).font(.caption).foregroundStyle(.secondary)
                                Spacer()
                                Button { Task { await shots.sync(cloud: cloud) } } label: { if shots.running { ProgressView() } else { Label("Sincronizar", systemImage: "arrow.triangle.2.circlepath") } }
                                    .buttonStyle(.bordered).disabled(shots.running)
                            }
                            if shotList.isEmpty { Text(q.isEmpty ? "No hay capturas. Añádelas con «📷 Fotos» o haz «📸 Varias capturas» en Android." : "Ninguna con «\(q)».").font(.subheadline).foregroundStyle(.secondary) }
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 10)], spacing: 10) {
                                ForEach(shotList) { shot in
                                    Button {
                                        if let image = shots.image(shot) { flow.cropShotLater(image, then: onChoose); dismiss() }
                                    } label: {
                                        VStack(alignment: .leading, spacing: 2) { ShotThumb(shot: shot); Text(shotLabel(shot.id)).font(.caption2).foregroundStyle(.secondary) }
                                    }.buttonStyle(.plain)
                                }
                            }
                        }.padding(.horizontal)
                        .task { await shots.sync(cloud: cloud) }
                    }
                }
                HStack(spacing: 10) {
                    Button { flow.photosLater(then: onChoose); dismiss() } label: { Label("Fotos", systemImage: "photo").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    Button { flow.filesLater(then: onChoose); dismiss() } label: { Label("Archivos", systemImage: "folder").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                }
                .padding().background(.bar)
            }
            .searchable(text: $query, prompt: "Buscar (nombre, macro o fecha: «9 nov»)")
            .navigationTitle("Elegir imagen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { dismiss() } } }
        }
    }

    private func matches(_ text: String) -> Bool { q.isEmpty || text.localizedCaseInsensitiveContains(q) }

    /// Recortes de las otras macros: solo los que tienen imagen y cada uno una sola vez.
    private func otherImages(_ current: MacroProject?) -> [(saved: SavedMacroTemplate, project: MacroProject)] {
        var seen = Set((current?.templates ?? []).compactMap { current?.imagesBase64[$0.template.file] })
        var out: [(saved: SavedMacroTemplate, project: MacroProject)] = []
        for other in library.projects where other.id != projectID {
            for saved in other.templates {
                guard let data = other.imagesBase64[saved.template.file], !seen.contains(data) else { continue }
                seen.insert(data); out.append((saved, other))
            }
        }
        return out
    }

    private func grid(_ items: [(SavedMacroTemplate, MacroProject, Bool)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
            ForEach(items, id: \.0.id) { saved, owner, copy in
                Button {
                    if copy { if let id = library.copyCapture(saved.id, from: owner.id, to: projectID) { onChoose(id, saved.name) } }
                    else { onChoose(saved.id, saved.name) }
                    dismiss()
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        TemplateThumbnail(saved: saved, encoded: owner.imagesBase64[saved.template.file], size: CGSize(width: 140, height: 80))
                        Text((saved.id == selected ? "✓ " : "") + saved.name).font(.subheadline.weight(.semibold)).lineLimit(2).foregroundStyle(.primary)
                        Text(copy ? "de «\(owner.name)»" : "\(saved.template.width)×\(saved.template.height) px").font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(6)
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(saved.id == selected ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: saved.id == selected ? 3 : 1))
                }.buttonStyle(.plain)
            }
        }
    }
}

/// «9 nov 20:08» a partir de «shot-20261109-200812-829».
func shotLabel(_ id: String) -> String {
    let parts = id.split(separator: "-")
    guard parts.count >= 3, parts[1].count == 8, parts[2].count >= 4 else { return id }
    let d = Array(parts[1]), t = Array(parts[2])
    let months = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"]
    let month = Int(String(d[4...5])) ?? 1, day = Int(String(d[6...7])) ?? 1
    return "\(day) \(months[max(0, min(11, month - 1))]) \(String(t[0...1])):\(String(t[2...3]))"
}

/// Resultado de «🔍 ¿La encuentra?»: dónde la vería Android y con qué parecido.
struct ImageTestView: View {
    let task: PhotoFlow.TestTask
    let project: MacroProject
    @Environment(\.dismiss) private var dismiss
    @State private var outcome: TemplateMatcher.Outcome?
    @State private var screenSize = CGSize(width: 1, height: 1)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    switch outcome {
                    case nil:
                        HStack { ProgressView(); Text("Buscando «\(task.saved.name)» en la captura…") }
                    case .found(let m, let needed)?:
                        Label("Sí la encuentra: parecido \(pct(m.confidence)) (pide \(pct(needed)))", systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.headline)
                        preview(m)
                    case .notFound(let m, let needed)?:
                        Label(m == nil ? "No la encuentra" : "No la encuentra: lo más parecido es \(pct(m!.confidence)) y hace falta \(pct(needed))", systemImage: "xmark.octagon.fill").foregroundStyle(.red).font(.headline)
                        if let m { preview(m) } else { Image(uiImage: task.screenshot).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 10)) }
                        Hint("Ideas: recorta la imagen otra vez con menos fondo, baja un poco el «parecido exigido» en ⚙ Ajustes de la macro, o comprueba que la captura es del mismo aparato.")
                    case .problem(let text)?:
                        Label(text, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    }
                    Hint(project.mode == .EXACT ? "Busca solo en el sitio donde se recortó (lo tienes así en ⚙ Ajustes de la macro)." : "Busca en toda la pantalla, igual que Android.")
                }
                .padding()
            }
            .navigationTitle("¿La encuentra?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() }.bold() } }
            .task {
                guard let encoded = project.imagesBase64[task.saved.template.file], let data = Data(base64Encoded: encoded), let image = UIImage(data: data) else {
                    outcome = .problem("No se pudo leer la imagen guardada."); return
                }
                let shot = task.screenshot, saved = task.saved.template, mode = project.mode, threshold = project.threshold
                let result = await Task.detached(priority: .userInitiated) {
                    TemplateMatcher.test(screenshot: shot, template: saved, templateImage: image, mode: mode, threshold: threshold)
                }.value
                screenSize = result.screenSize; outcome = result.0
            }
        }
    }

    private func pct(_ v: Double) -> String { "\(Int((v * 100).rounded())) %" }

    private func preview(_ m: TemplateMatcher.Match) -> some View {
        Image(uiImage: task.screenshot).resizable().scaledToFit()
            .overlay {
                GeometryReader { geo in
                    let sx = geo.size.width / screenSize.width, sy = geo.size.height / screenSize.height
                    Rectangle().stroke(Color.green, lineWidth: 3)
                        .frame(width: CGFloat(task.saved.template.width) * sx, height: CGFloat(task.saved.template.height) * sy)
                        .position(x: (CGFloat(m.x) + CGFloat(task.saved.template.width) / 2) * sx, y: (CGFloat(m.y) + CGFloat(task.saved.template.height) / 2) * sy)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Lo que se ajusta de cada tipo de paso

struct ActionKindEditor: View {
    @Binding var action: MacroAction
    let project: MacroProject
    let knownApps: [String]
    /// false = lo imprescindible; true = lo de «⚙ Más opciones».
    var advanced = false
    @EnvironmentObject private var photos: PhotoFlow
    @State private var repeatUntilImage: Bool

    init(action: Binding<MacroAction>, project: MacroProject, knownApps: [String], advanced: Bool = false) {
        _action = action; self.project = project; self.knownApps = knownApps; self.advanced = advanced
        _repeatUntilImage = State(initialValue: !action.wrappedValue.templateId.isEmpty)
    }

    private var imageTitle: String {
        switch action.kind {
        case .CLICK_IMAGE: return "Imagen que hay que tocar"
        case .WAIT_IMAGE: return "Imagen que hay que esperar"
        case .REPEAT: return "Repetir hasta que se vea esta imagen"
        case .WHEN_IMAGE: return "Imagen que hay que tocar siempre que salga"
        default: return "Imagen que hay que comprobar"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if advanced { advancedOptions } else { essentials }
        }
    }

    /// Lo imprescindible de cada tipo de paso (lo demás va en «⚙ Más opciones»).
    @ViewBuilder private var essentials: some View {
            switch action.kind {
            case .CLICK_IMAGE:
                ImageSection(title: imageTitle, templateId: $action.templateId, project: project) { renameForImage(&action, $0) }
            case .WAIT_IMAGE:
                ImageSection(title: imageTitle, templateId: $action.templateId, project: project) { renameForImage(&action, $0) }
                Picker("Esperar", selection: Binding(get: { action.untilGone }, set: { gone in
                    action.untilGone = gone
                    action.waitMs = gone ? (action.waitMs > 0 ? action.waitMs : 10_000) : 0
                })) { Text("👀 Hasta que salga").tag(false); Text("🙈 Hasta que ya no salga").tag(true) }.pickerStyle(.segmented)
                if action.untilGone {
                    Hint("Sirve para «hasta que no quede ninguna caja por abrir»: mientras espera, tus reglas ⚡ siguen tocando. Sigue cuando lleva un rato sin verla.")
                    SecondsField(title: "Rato seguido sin verla (segundos)", value: $action.waitMs, minimum: 1_000, maximum: 600_000)
                    QuickChoices(values: [5_000, 10_000, 15_000, 20_000], selected: action.waitMs) { action.waitMs = $0 }
                }
                SecondsField(title: "Esperar como máximo (segundos)", value: $action.durationMs, minimum: 1_000, maximum: 600_000)
                QuickChoices(values: [10_000, 30_000, 60_000, 120_000, 300_000], selected: action.durationMs) { action.durationMs = $0 }
            case .IF_IMAGE:
                ImageSection(title: imageTitle, templateId: $action.templateId, project: project) { renameForImage(&action, $0) }
                insideHint
            case .WHEN_IMAGE:
                ImageSection(title: imageTitle, templateId: $action.templateId, project: project) { renameForImage(&action, $0) }
                Hint("Mientras la macro funciona, en cuanto salga esta imagen la toca, esté donde esté la macro.")
                    .onChange(of: action.templateId) { _ in addTapIfEmpty() }
                    .onAppear { addTapIfEmpty() }
            case .REPEAT:
                Picker("Repetir", selection: $repeatUntilImage) { Text("Un número de veces").tag(false); Text("Hasta ver una imagen").tag(true) }
                    .pickerStyle(.segmented)
                    .onChange(of: repeatUntilImage) { until in if !until { action.templateId = "" } }
                if repeatUntilImage {
                    ImageSection(title: imageTitle, templateId: $action.templateId, project: project) { renameForImage(&action, $0) }
                }
                Stepper(repeatUntilImage ? "Como máximo \(action.count) veces" : "Repetir \(action.count) veces", value: $action.count, in: 1...100)
                    .font(.body.weight(.semibold))
                QuickChoices(values: [2, 3, 5, 10, 20, 50], selected: Int64(action.count), label: { "\($0)" }) { action.count = Int($0) }
                insideHint
            case .WAIT:
                SecondsField(title: "Tiempo (segundos, o «4 min»)", value: $action.durationMs, minimum: 1, maximum: 600_000)
                QuickChoices(values: [1_000, 2_000, 5_000, 10_000, 30_000, 60_000, 240_000, 300_000, 600_000], selected: action.durationMs) { action.durationMs = $0 }
            case .CLOSE_ACTIVE:
                Hint("Abre Recientes, cierra el juego y vuelve a Inicio. En el PC con Windows pulsa «Cerrar todo» (se elige en Android: ⚙ Ajustes › 🖥 Este aparato).")
            case .STOP:
                Hint("Termina la macro aquí. Úsalo dentro de «Si se ve…».")
            case .GESTURE:
                GestureEditor(action: $action)
            case .OPEN_APP:
                if !knownApps.isEmpty {
                    Menu {
                        ForEach(knownApps, id: \.self) { name in
                            Button("\(friendlyApp(name))  ·  \(name)") {
                                action.packageName = name
                                if action.name == kindLabel(.OPEN_APP) || action.name.hasPrefix("Abrir ") || action.name.isEmpty { action.name = "Abrir \(friendlyApp(name))" }
                            }
                        }
                    } label: { Label(action.packageName.isEmpty ? "📱 Elegir el juego" : "📱 Cambiar de juego", systemImage: "chevron.down").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                }
                LabeledField(title: "Paquete (si no sale en la lista)") {
                    TextField("com.ejemplo.juego", text: $action.packageName)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                }
            case .IF_COLOR:
                ColorActionEditor(action: $action)
                insideHint
            case .HOME, .BACK, .RECENTS, .NOTIFICATIONS, .QUICK_SETTINGS:
                Hint("No hay nada que ajustar: Android pulsa ese botón.")
            }
    }

    /// Lo que casi nunca hace falta tocar.
    @ViewBuilder private var advancedOptions: some View {
            switch action.kind {
            case .CLICK_IMAGE:
                clickOptions
            case .WAIT_IMAGE:
                if action.untilGone { Hint("Si al llegar al máximo aún sale, sigue igualmente.") }
                else { missingChoice(title: "Si no sale a tiempo") }
            case .WHEN_IMAGE:
                Text("Después de tocarla…").font(.headline)
                VStack(spacing: 6) {
                    ForEach(MacroAfterRule.allCases, id: \.self) { choice in
                        Button { action.afterRule = choice } label: {
                            HStack {
                                Image(systemName: action.afterRule == choice ? "checkmark.circle.fill" : "circle")
                                Text(afterRuleChoice(choice)).foregroundStyle(.primary)
                                Spacer()
                            }
                            .padding(.vertical, 10).padding(.horizontal, 12)
                            .background((action.afterRule == choice ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.08)), in: RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain)
                    }
                }
                SecondsField(title: "Descanso antes de volver a tocarla (segundos)", value: Binding(get: { action.waitMs > 0 ? action.waitMs : 3_000 }, set: { action.waitMs = $0 }), minimum: 500, maximum: 3_600_000)
                QuickChoices(values: [1_000, 3_000, 5_000, 10_000, 30_000], selected: action.waitMs > 0 ? action.waitMs : 3_000) { action.waitMs = $0 }
            case .CLOSE_ACTIVE:
                SecondsField(title: "Espera para que se abran las apps recientes (0,5–3 s)", value: $action.durationMs, minimum: 500, maximum: 3_000)
                QuickChoices(values: [500, 1_000, 2_000], selected: action.durationMs) { action.durationMs = $0 }
            default:
                EmptyView()
            }
    }

    /// Una regla nueva ya sabe tocar su imagen: no hace falta añadir el paso a mano (como en Android).
    private func addTapIfEmpty() {
        guard action.kind == .WHEN_IMAGE, action.children.isEmpty, let image = project.templates.first(where: { $0.id == action.templateId }) else { return }
        action.children = [ruleTap(image.id, image.name)]
    }

    private var insideHint: some View {
        Hint("Los pasos de dentro se añaden en la lista, con «＋ Añadir paso aquí».")
    }

    @ViewBuilder private var clickOptions: some View {
        Text("Dónde tocar al encontrarla").font(.headline)
        Picker("Dónde tocar", selection: Binding(get: { action.clickTarget }, set: { new in
            if new != action.clickTarget { let v: Float = new == .FIXED ? 50 : 0; action.clickOffsetX = v; action.clickOffsetY = v }
            action.clickTarget = new
        })) {
            Text("En el centro").tag(MacroClickTarget.CENTER); Text("Desplazado").tag(MacroClickTarget.OFFSET); Text("Otro punto").tag(MacroClickTarget.FIXED)
        }.pickerStyle(.segmented)
        if action.clickTarget == .OFFSET {
            Hint("Distancia desde el centro de la imagen, en % de su tamaño (−100 a 100). Ej.: X 100 = un ancho de imagen a la derecha.")
            HStack { DecimalField(title: "X (%)", value: $action.clickOffsetX); DecimalField(title: "Y (%)", value: $action.clickOffsetY) }
        }
        if action.clickTarget == .FIXED {
            Hint("La imagen sirve de aviso y el toque se hace en este punto de la pantalla (0–100 %).")
            Button { photos.pickPoints(1) { p, _ in action.clickOffsetX = p[0].x * 100; action.clickOffsetY = p[0].y * 100 } } label: {
                Label("Marcar el punto tocando una captura", systemImage: "hand.tap.fill").frame(maxWidth: .infinity)
            }.buttonStyle(.borderedProminent)
            HStack { DecimalField(title: "X (%)", value: $action.clickOffsetX); DecimalField(title: "Y (%)", value: $action.clickOffsetY) }
        }
        // (un toque, doble o mantener pulsado se elige arriba, en «¿Qué hace cuando ve la imagen?»)
        if action.clickStyle == .LONG {
            SecondsField(title: "Cuánto tiempo mantener pulsado (segundos, mínimo 0,5)", value: $action.durationMs, minimum: 500, maximum: 60_000)
        }
        Text("Si la imagen no aparece").font(.headline)
        SecondsField(title: "Buscarla durante (segundos)", value: Binding(get: { action.waitMs > 0 ? action.waitMs : project.timeoutMs }, set: { action.waitMs = $0 == project.timeoutMs ? 0 : $0 }), minimum: 1_000, maximum: 3_600_000)
        QuickChoices(values: [2_000, 5_000, 10_000, 30_000, 60_000], selected: action.waitMs > 0 ? action.waitMs : project.timeoutMs) { action.waitMs = $0 == project.timeoutMs ? 0 : $0 }
        missingChoice(title: nil)
    }

    @ViewBuilder private func missingChoice(title: String?) -> some View {
        if let title { Text(title).font(.headline) }
        Picker("Si no aparece", selection: $action.skipIfMissing) { Text("✖ Darlo por fallado").tag(false); Text("⏭ Saltar y seguir").tag(true) }.pickerStyle(.segmented)
        if !action.skipIfMissing { Hint("Fallado: si la macro es sin fin, la vuelta empieza otra vez; si no, se para.") }
    }
}

struct GestureEditor: View {
    @Binding var action: MacroAction
    @EnvironmentObject private var photos: PhotoFlow
    private var recorded: Bool { action.points.count > 2 }
    private var swipe: Binding<Bool> {
        Binding(get: { action.points.count == 2 }, set: { wanted in
            guard let first = action.points.first else { return }
            if wanted && action.points.count == 1 {
                action.points.append(MacroPoint(x: first.x, y: max(0, first.y - 0.3)))
                if action.durationMs < 300 { action.durationMs = 300 }
            } else if !wanted && action.points.count == 2 { action.points = [first]; if action.durationMs > 1000 { action.durationMs = 80 } }
        })
    }
    private func coordinate(_ index: Int, _ x: Bool) -> Binding<Float> {
        Binding(get: { index < action.points.count ? (x ? action.points[index].x : action.points[index].y) * 100 : 50 },
                set: { v in guard index < action.points.count else { return }; let c = min(max(v, 0), 100) / 100; if x { action.points[index].x = c } else { action.points[index].y = c } })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if action.points.isEmpty { Button("Añadir punto") { action.points = [MacroPoint(x: 0.5, y: 0.5)] } }
            else if recorded {
                Text("Movimiento grabado de \(action.points.count) puntos").font(.headline)
                Hint("Se grabó en Android. Para cambiarlo, vuelve a grabarlo allí o conviértelo en un deslizamiento simple.")
                Button("Convertir en deslizamiento simple") { action.points = [action.points.first!, action.points.last!] }
                SecondsField(title: "Duración del gesto (segundos)", value: $action.durationMs, minimum: 1, maximum: 60_000)
            } else {
                Picker("Tipo", selection: swipe) { Text("👆 Tocar").tag(false); Text("↕ Deslizar").tag(true) }.pickerStyle(.segmented)
                Button {
                    photos.pickPoints(swipe.wrappedValue ? 2 : 1) { points, _ in action.points = points }
                } label: {
                    Label(swipe.wrappedValue ? "Marcarlo tocando una captura" : "Marcar el punto tocando una captura", systemImage: "hand.tap.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Text(readableActionSummary(action)).font(.subheadline.weight(.semibold))
                Hint("Usa una captura de la pantalla entera del juego (hecha en BlueStacks o en el móvil Android). Si es horizontal, gira el iPhone para verla más grande.")
                DisclosureGroup("Escribir los números a mano") {
                    VStack(alignment: .leading, spacing: 8) {
                        if swipe.wrappedValue { Text("Desde").font(.caption.bold()) }
                        HStack { DecimalField(title: "X (%)", value: coordinate(0, true)); DecimalField(title: "Y (%)", value: coordinate(0, false)) }
                        if swipe.wrappedValue {
                            Text("Hasta").font(.caption.bold())
                            HStack { DecimalField(title: "X (%)", value: coordinate(1, true)); DecimalField(title: "Y (%)", value: coordinate(1, false)) }
                        }
                        Hint("0 = arriba / izquierda, 100 = abajo / derecha.")
                    }
                }.font(.subheadline)
                SecondsField(title: swipe.wrappedValue ? "Duración del deslizamiento (segundos)" : "Duración del toque (segundos)", value: $action.durationMs, minimum: 1, maximum: 60_000)
                if swipe.wrappedValue {
                    QuickChoices(values: [250, 300, 1_000, 2_000], selected: action.durationMs) { action.durationMs = $0 }
                    Hint("0,3 s = deslizamiento rápido · 1 s o más = arrastre lento.")
                } else {
                    QuickChoices(values: [80, 600, 1_000], selected: action.durationMs) { action.durationMs = $0 }
                    Hint("0,08 s = toque normal · 0,6 s o más = pulsación larga.")
                }
            }
        }
    }
}

struct ColorActionEditor: View {
    @Binding var action: MacroAction
    @EnvironmentObject private var photos: PhotoFlow
    @State private var hex: String
    init(action: Binding<MacroAction>) { _action = action; _hex = State(initialValue: colorHex(action.wrappedValue.targetColor)) }

    private let samples: [(String, String)] = [("Rojo", "#FF0000"), ("Verde", "#00FF00"), ("Azul", "#0080FF"), ("Amarillo", "#FFFF00"), ("Blanco", "#FFFFFF"), ("Negro", "#000000")]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Color que hay que buscar").font(.headline)
            Button {
                photos.pickPoints(1) { points, color in
                    action.targetColor = color; hex = colorHex(color)
                    action.colorX = points[0].x; action.colorY = points[0].y
                }
            } label: { Label("🎯 Elegir el color tocándolo en una captura", systemImage: "eyedropper").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent)
            HStack {
                RoundedRectangle(cornerRadius: 8).fill(colorFromInt(action.targetColor)).frame(width: 46, height: 46).overlay(RoundedRectangle(cornerRadius: 8).stroke(.secondary))
                LabeledField(title: "Color, p. ej. #FF5500") { TextField("#RRGGBB", text: $hex).textFieldStyle(.roundedBorder).textInputAutocapitalization(.characters).autocorrectionDisabled() }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(samples, id: \.1) { label, sample in
                        Button { hex = sample } label: {
                            HStack(spacing: 4) { RoundedRectangle(cornerRadius: 3).fill(colorFromInt(parseColor(sample)!)).frame(width: 14, height: 14).overlay(RoundedRectangle(cornerRadius: 3).stroke(.secondary)); Text(label) }
                        }.buttonStyle(.bordered)
                    }
                }
            }
            Stepper("Tolerancia: \(action.colorTolerance)", value: $action.colorTolerance, in: 0...255, step: 4)
            Hint("Cuánto puede variar el color. Entre 20 y 40 suele ir bien.")
            Picker("Dónde mirar", selection: $action.colorMode) { Text("En toda la pantalla").tag(MacroSearchMode.ANYWHERE); Text("En un punto concreto").tag(MacroSearchMode.EXACT) }.pickerStyle(.segmented)
            if action.colorMode == .EXACT {
                HStack {
                    DecimalField(title: "X (%)", value: Binding(get: { action.colorX * 100 }, set: { action.colorX = $0 / 100 }))
                    DecimalField(title: "Y (%)", value: Binding(get: { action.colorY * 100 }, set: { action.colorY = $0 / 100 }))
                }
            }
        }
        .onChange(of: hex) { value in if let parsed = parseColor(value) { action.targetColor = parsed } }
    }
}

/// Qué le falta a un paso (sin mirar los de dentro).
func actionIssues(_ action: MacroAction, in project: MacroProject) -> [String] {
    var issues: [String] = []
    if action.name.trimmingCharacters(in: .whitespaces).isEmpty { issues.append("Escribe un nombre") }
    let needsImage = [.IF_IMAGE, .CLICK_IMAGE, .WAIT_IMAGE, .WHEN_IMAGE].contains(action.kind)
    if needsImage || (action.kind == .REPEAT && !action.templateId.isEmpty) {
        if let saved = project.templates.first(where: { $0.id == action.templateId }) {
            if project.imagesBase64[saved.template.file] == nil { issues.append("Faltan los datos de la imagen «\(saved.name)»") }
        } else if needsImage { issues.append("Elige o añade una imagen") }
    }
    if action.kind == .OPEN_APP && action.packageName == noAppPackage { issues.append("Elige el juego") }
    if action.kind == .OPEN_APP {
        let pattern = try? NSRegularExpression(pattern: "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        let range = NSRange(action.packageName.startIndex..<action.packageName.endIndex, in: action.packageName)
        if pattern?.firstMatch(in: action.packageName, range: range) == nil { issues.append("Elige la app") }
    }
    if action.kind == .CLOSE_ACTIVE && !(500...3_000).contains(action.durationMs) { issues.append("La espera va de 0,5 a 3 segundos") }
    if action.kind == .CLICK_IMAGE && action.clickStyle == .LONG && action.durationMs < 500 { issues.append("La pulsación larga dura al menos 0,5 s") }
    if action.kind == .REPEAT && !action.children.contains(where: \.enabled) { issues.append("Añade dentro los pasos que se repiten") }
    if action.kind == .WHEN_IMAGE && !action.children.contains(where: \.enabled) { issues.append("Todavía no toca nada") }
    return issues
}

// MARK: - Piezas pequeñas

struct TemplateThumbnail: View {
    let saved: SavedMacroTemplate?
    let encoded: String?
    let size: CGSize
    var body: some View {
        Group {
            if let encoded, let data = Data(base64Encoded: encoded), let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFit() }
            else { Image(systemName: "photo").resizable().scaledToFit().padding(18).foregroundStyle(.secondary) }
        }
        .frame(width: size.width, height: size.height).background(Color.black.opacity(0.18)).clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel(saved?.name ?? "Sin imagen")
    }
}

struct Hint: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
}

/// Botones rápidos de tiempo: «1 s», «40 s», «5 min»…
struct QuickChoices: View {
    let values: [Int64]
    let selected: Int64
    var label: (Int64) -> String = { $0 == 0 ? "Nada" : readableDuration($0) }
    let onPick: (Int64) -> Void
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(values, id: \.self) { v in
                    Button(label(v)) { onPick(v) }
                        .buttonStyle(.bordered).tint(v == selected ? .accentColor : .secondary)
                }
            }
        }
    }
}

/// «⏱ Esperar antes» y «⏱ Esperar después» de un paso.
struct StepDelaysEditor: View {
    @Binding var action: MacroAction
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("⏱ Esperas de este paso").font(.headline)
            SecondsField(title: action.kind == .CLICK_IMAGE ? "Esperar antes de buscar y tocar (segundos)" : "Esperar antes de hacerlo (segundos)", value: $action.delayBeforeMs, minimum: 0, maximum: 3_600_000)
            QuickChoices(values: [0, 1_000, 2_000, 5_000, 15_000, 40_000], selected: action.delayBeforeMs) { action.delayBeforeMs = $0 }
            SecondsField(title: "Esperar después, antes del siguiente paso (segundos)", value: $action.delayAfterMs, minimum: 0, maximum: 3_600_000)
            QuickChoices(values: [0, 1_000, 2_000, 5_000, 15_000, 60_000, 300_000], selected: action.delayAfterMs) { action.delayAfterMs = $0 }
            Hint("Así no hacen falta pasos «Esperar» sueltos. Necesita PrisonMacro 0.6.5 o más nueva en Android.")
        }
        .padding(12)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct IntegerField: View {
    let title: String
    @Binding var value: Int64
    let minimum: Int64
    let maximum: Int64
    @State private var text: String
    init(title: String, value: Binding<Int64>, minimum: Int64, maximum: Int64) { self.title = title; _value = value; self.minimum = minimum; self.maximum = maximum; _text = State(initialValue: String(value.wrappedValue)) }
    var body: some View {
        LabeledField(title: title) {
            TextField(title, text: $text).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                .onSubmit { text = String(value) }
                .onChange(of: value) { if Int64(text) != $0 { text = String($0) } }
                .onChange(of: text) { input in if let parsed = Int64(input), parsed >= minimum, parsed <= maximum { value = parsed } }
        }
    }
}

/// El nombre del campo siempre visible encima (el texto gris de iOS desaparece al escribir).
struct LabeledField<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View { VStack(alignment: .leading, spacing: 3) { Text(title).font(.caption).foregroundStyle(.secondary); content } }
}

/// Tiempo en segundos para la persona; se guarda en milisegundos como en Android.
struct SecondsField: View {
    let title: String
    @Binding var value: Int64
    let minimum: Int64
    let maximum: Int64
    @State private var text: String
    init(title: String, value: Binding<Int64>, minimum: Int64, maximum: Int64) { self.title = title; _value = value; self.minimum = minimum; self.maximum = maximum; _text = State(initialValue: timeText(value.wrappedValue)) }
    var body: some View {
        LabeledField(title: title) {
            TextField(title, text: $text).keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder)
                .onSubmit { text = timeText(value) }
                .onChange(of: value) { if parseTime(text) != $0 { text = timeText($0) } }
                .onChange(of: text) { if let ms = parseTime($0), ms >= minimum, ms <= maximum { value = ms } }
        }
    }
}

struct DecimalField: View {
    let title: String
    @Binding var value: Float
    @State private var text: String
    init(title: String, value: Binding<Float>) { self.title = title; _value = value; _text = State(initialValue: decimalText(value.wrappedValue)) }
    var body: some View {
        LabeledField(title: title) {
            TextField(title, text: $text).keyboardType(.decimalPad).textFieldStyle(.roundedBorder)
                .onChange(of: value) { if parseDecimal(text).map(Float.init) != $0 { text = decimalText($0) } }
                .onChange(of: text) { if let parsed = parseDecimal($0) { value = Float(parsed) } }
        }
    }
}

// MARK: - Textos y utilidades compartidas

func kindLabel(_ kind: MacroKind) -> String {
    // Mismos nombres que en Android, para no liarse al pasar de una app a otra.
    switch kind {
    case .GESTURE: return "Tocar un punto"
    case .CLICK_IMAGE: return "Tocar una imagen"
    case .WAIT: return "Esperar"
    case .WAIT_IMAGE: return "Esperar a que salga una imagen"
    case .IF_IMAGE: return "Si se ve una imagen…"
    case .IF_COLOR: return "Si se ve un color…"
    case .REPEAT: return "Repetir pasos"
    case .STOP: return "Parar la macro"
    case .OPEN_APP: return "Abrir un juego"
    case .BACK: return "Botón Atrás"
    case .HOME: return "Botón Inicio"
    case .RECENTS: return "Apps recientes"
    case .CLOSE_ACTIVE: return "Cerrar el juego"
    case .NOTIFICATIONS: return "Abrir notificaciones"
    case .QUICK_SETTINGS: return "Ajustes rápidos"
    case .WHEN_IMAGE: return "Tocar una imagen siempre que salga"
    }
}

func kindEmoji(_ kind: MacroKind) -> String {
    switch kind {
    case .GESTURE: return "👆"
    case .CLICK_IMAGE: return "🎯"
    case .WAIT: return "⏱"
    case .WAIT_IMAGE: return "👀"
    case .IF_IMAGE: return "🔀"
    case .IF_COLOR: return "🎨"
    case .REPEAT: return "🔁"
    case .STOP: return "⛔"
    case .OPEN_APP: return "🚀"
    case .BACK: return "◀️"
    case .HOME: return "🏠"
    case .RECENTS: return "🗂"
    case .CLOSE_ACTIVE: return "✖️"
    case .NOTIFICATIONS: return "🔔"
    case .QUICK_SETTINGS: return "🎛"
    case .WHEN_IMAGE: return "⚡"
    }
}

func kindExplanation(_ kind: MacroKind) -> String {
    switch kind {
    case .GESTURE: return "Toca o desliza siempre en el mismo sitio"
    case .CLICK_IMAGE: return "Espera a que salga el botón y lo toca"
    case .WAIT: return "Unos segundos o minutos"
    case .WAIT_IMAGE: return "Sigue cuando aparece (por ejemplo, el lobby)"
    case .IF_IMAGE: return "Hace unos pasos u otros según lo que se vea"
    case .IF_COLOR: return "Igual, pero mirando un color"
    case .REPEAT: return "Repite varios pasos"
    case .STOP: return "Termina la macro"
    case .OPEN_APP: return "Abre una app instalada"
    case .BACK: return "Pulsa la flecha atrás"
    case .HOME: return "Vuelve a la pantalla de inicio"
    case .RECENTS: return "Abre la lista de apps abiertas"
    case .CLOSE_ACTIVE: return "Lo cierra desde Recientes y vuelve a Inicio"
    case .NOTIFICATIONS: return "Baja la barra de notificaciones"
    case .QUICK_SETTINGS: return "Abre los ajustes rápidos"
    case .WHEN_IMAGE: return "Vigila toda la macro: anuncios, ventanas, la X…"
    }
}

func afterRuleLabel(_ value: MacroAfterRule) -> String {
    switch value {
    case .CONTINUE: return "sigue donde estaba"
    case .SKIP_STEP: return "salta el paso que esperaba"
    case .EXIT_LOOP: return "sale del bucle"
    case .RESTART: return "empieza la vuelta de nuevo"
    case .STOP: return "para la macro"
    }
}

func afterRuleChoice(_ value: MacroAfterRule) -> String {
    switch value {
    case .CONTINUE: return "Seguir donde estaba"
    case .SKIP_STEP: return "Saltar el paso que esperaba"
    case .EXIT_LOOP: return "Salir del bucle"
    case .RESTART: return "Empezar la vuelta de nuevo"
    case .STOP: return "Parar la macro"
    }
}

func afterRuleExplanation(_ value: MacroAfterRule) -> String {
    switch value {
    case .CONTINUE: return "La macro sigue justo donde se quedó, como si nada."
    case .SKIP_STEP: return "Si la macro estaba esperando o buscando una imagen, deja de esperar y pasa al siguiente paso."
    case .EXIT_LOOP: return "Sale del bloque «🔁 Repetir» en el que esté y sigue con lo de después. Si no está dentro de ninguno, termina esta vuelta."
    case .RESTART: return "Vuelve al primer paso de la macro (empieza la vuelta otra vez)."
    case .STOP: return "Termina la macro."
    }
}

/// Tipos que no tienen nada que ajustar.
let noConfigKinds: Set<MacroKind> = [.BACK, .HOME, .RECENTS, .NOTIFICATIONS, .QUICK_SETTINGS]

/// Los pasos de siempre (como en Android): salen arriba en «Añadir paso».
let basicKinds: [MacroKind] = [.CLICK_IMAGE, .WHEN_IMAGE, .WAIT, .WAIT_IMAGE, .GESTURE, .OPEN_APP, .CLOSE_ACTIVE]

/// El resto, plegado bajo «Más tipos de paso».
let kindGroups: [(String, [MacroKind])] = [
    ("Decidir y repetir", [.IF_IMAGE, .IF_COLOR, .REPEAT, .STOP]),
    ("Botones de Android", [.BACK, .HOME, .RECENTS, .NOTIFICATIONS, .QUICK_SETTINGS]),
]

/// Regla «⚡» que solo toca su propia imagen: en la lista se ve en una línea, sin bloque.
func isSimpleRule(_ a: MacroAction) -> Bool {
    a.kind == .WHEN_IMAGE && a.otherwise.isEmpty && a.children.count == 1 && a.children[0].kind == .CLICK_IMAGE && a.children[0].templateId == a.templateId && a.children[0].enabled
}

/// El toque que hace una regla «⚡» sobre su propia imagen.
func ruleTap(_ templateId: String, _ image: String) -> MacroAction {
    var tap = defaultAction(.CLICK_IMAGE)
    tap.name = "Tocar «\(image)»"; tap.templateId = templateId; tap.waitMs = 2_000; tap.skipIfMissing = true
    return tap
}

@ViewBuilder func macroKindMenu(onChoose: @escaping (MacroKind) -> Void) -> some View {
    Section { ForEach(basicKinds, id: \.self) { kind in Button("\(kindEmoji(kind))  \(kindLabel(kind))") { onChoose(kind) } } }
    ForEach(kindGroups, id: \.0) { group in
        Section(group.0) { ForEach(group.1, id: \.self) { kind in Button("\(kindEmoji(kind))  \(kindLabel(kind))") { onChoose(kind) } } }
    }
}

/// Como en Android: al elegir la imagen, el paso se llama solo «Tocar «Jugar»» si aún tenía
/// el nombre genérico o uno puesto así automáticamente.
func renameForImage(_ action: inout MacroAction, _ image: String) {
    // «⚡» sencilla: su toque pasa a la imagen nueva.
    if action.kind == .WHEN_IMAGE {
        if action.children.isEmpty { action.children = [ruleTap(action.templateId, image)] }
        else if action.otherwise.isEmpty, action.children.count == 1, action.children[0].kind == .CLICK_IMAGE, action.children[0].templateId != action.templateId {
            action.children[0].templateId = action.templateId; action.children[0].name = "Tocar «\(image)»"
        }
    }
    let oldNames: Set<String> = ["Tocar / deslizar", "Buscar imagen y tocarla", "Esperar a ver una imagen", "Abrir app o juego", "Cerrar la app actual", "Siempre que se vea una imagen"]
    let generic = action.name.isEmpty || action.name == kindLabel(action.kind).replacingOccurrences(of: "…", with: "") || oldNames.contains(action.name) || (action.name.contains("«") && action.name.hasSuffix("»"))
    guard generic else { return }
    switch action.kind {
    case .CLICK_IMAGE: action.name = "Tocar «\(image)»"
    case .WAIT_IMAGE: action.name = "Esperar «\(image)»"
    case .IF_IMAGE: action.name = "Si se ve «\(image)»"
    case .WHEN_IMAGE: action.name = "Siempre que salga «\(image)»"
    case .REPEAT: action.name = "Repetir hasta ver «\(image)»"
    default: break
    }
}

func defaultAction(_ kind: MacroKind) -> MacroAction {
    let name = kindLabel(kind).replacingOccurrences(of: "…", with: "")
    switch kind {
    case .WAIT: return MacroAction(kind: kind, name: name, durationMs: 5_000)
    case .WAIT_IMAGE: return MacroAction(kind: kind, name: name, durationMs: 30_000)
    case .REPEAT: return MacroAction(kind: kind, name: name, count: 3)
    case .OPEN_APP: return MacroAction(kind: kind, name: name, packageName: "")
    case .CLOSE_ACTIVE: return MacroAction(kind: kind, name: name, durationMs: 1000)
    default: return MacroAction(kind: kind, name: name)
    }
}

func plural(_ n: Int, _ one: String, _ many: String) -> String { "\(n) \(n == 1 ? one : many)" }

func recursiveActionCount(_ actions: [MacroAction]) -> Int {
    actions.reduce(0) { $0 + 1 + recursiveActionCount($1.children) + recursiveActionCount($1.otherwise) }
}

func readableActionSummary(_ action: MacroAction, image: String? = nil) -> String {
    let img = image.map { "«\($0)»" } ?? "la imagen"
    switch action.kind {
    case .WAIT: return "Espera \(readableDuration(action.durationMs))"
    case .WAIT_IMAGE:
        if action.untilGone { return "Espera a que ya no salga \(img) (\(readableDuration(action.waitMs > 0 ? action.waitMs : 10_000)) sin verla, máx. \(readableDuration(action.durationMs)))" }
        return "Espera \(img) (máx. \(readableDuration(action.durationMs)))" + (action.skipIfMissing ? " · si no, sigue" : "")
    case .OPEN_APP: return action.packageName.isEmpty || action.packageName == noAppPackage ? "Falta elegir el juego" : "Abre \(friendlyApp(action.packageName))"
    case .CLOSE_ACTIVE: return "Cierra la app que está abierta"
    case .GESTURE:
        let p = action.points
        if p.count <= 1 {
            let x = p.first?.x ?? 0.5, y = p.first?.y ?? 0.5
            return "Toca en \(Int(x * 100)) % · \(Int(y * 100)) % de la pantalla" + (x == 0.5 && y == 0.5 ? " (el centro: ¿lo marcaste?)" : "")
        }
        if p.count == 2 { return "Desliza de (\(Int(p[0].x * 100)), \(Int(p[0].y * 100))) a (\(Int(p[1].x * 100)), \(Int(p[1].y * 100)))" }
        return "Movimiento grabado (\(p.count) puntos)"
    case .CLICK_IMAGE: return "Toca \(img)" + (action.skipIfMissing ? " · si no está, sigue" : "")
    case .IF_IMAGE: return "Si se ve \(img)"
    case .WHEN_IMAGE: return isSimpleRule(action) ? "Toca \(img) siempre que salga" + (action.afterRule != .CONTINUE ? " · después \(afterRuleLabel(action.afterRule))" : "") : "En cuanto salga \(img) · después \(afterRuleLabel(action.afterRule))"
    case .IF_COLOR: return "Si se ve el color \(colorHex(action.targetColor))"
    case .REPEAT: return action.templateId.isEmpty ? "Repite \(action.count) veces" : "Repite hasta ver \(img) (máx. \(action.count))"
    case .STOP: return "Termina la macro"
    case .HOME: return "Va al inicio"
    case .BACK: return "Pulsa atrás"
    case .RECENTS: return "Abre las apps recientes"
    case .NOTIFICATIONS: return "Abre las notificaciones"
    case .QUICK_SETTINGS: return "Abre los ajustes rápidos"
    }
}

/// «com.wildlife.games.battle.royale.free.zooba» → «zooba».
func friendlyApp(_ package: String) -> String {
    let known = ["com.wildlife.games.battle.royale.free.zooba": "Zooba"]
    if let name = known[package] { return name }
    return package.split(separator: ".").last.map(String.init) ?? package
}

func readableDuration(_ milliseconds: Int64) -> String {
    if milliseconds < 1_000 { return "\(milliseconds) ms" }
    if milliseconds % 60_000 == 0 { return "\(milliseconds / 60_000) min" }
    if milliseconds < 60_000 { return secondsText(milliseconds) + " s" }
    return "\(milliseconds / 60_000) min \((milliseconds % 60_000) / 1_000) s"
}

/// Lo que le falta a la macro para poder ejecutarse en Android.
func projectExecutionIssues(_ project: MacroProject) -> [String] {
    var issues: [String] = []
    func walk(_ actions: [MacroAction]) {
        for action in actions where action.enabled {
            issues += actionIssues(action, in: project).map { "«\(action.name)»: \($0.prefix(1).lowercased() + $0.dropFirst())" }
            walk(action.children); walk(action.otherwise)
        }
    }
    if !project.actions.contains(where: \.enabled) { issues.append("Añade al menos un paso") } else { walk(project.actions) }
    if project.startOnImage && project.template == nil { issues.append("Elige la imagen con la que empieza la macro") }
    var seen = Set<String>()
    return issues.filter { seen.insert($0).inserted }
}

/// «⏱ 2 s antes · 15 s después» (vacío si no espera).
func delaysSummary(_ a: MacroAction) -> String {
    var parts: [String] = []
    if a.delayBeforeMs > 0 { parts.append("\(readableDuration(a.delayBeforeMs)) antes") }
    if a.delayAfterMs > 0 { parts.append("\(readableDuration(a.delayAfterMs)) después") }
    return parts.isEmpty ? "" : "⏱ " + parts.joined(separator: " · ")
}

/// Convierte los pasos «Esperar» sueltos en esperas del paso de al lado:
/// detrás de otro paso → «esperar después» de ese paso; al principio → «esperar antes» del siguiente.
func mergeWaits(_ list: [MacroAction]) -> (actions: [MacroAction], merged: Int) {
    var result: [MacroAction] = []
    var pendingBefore: Int64 = 0
    var merged = 0, here = 0
    for original in list {
        var a = original
        let inner1 = mergeWaits(a.children), inner2 = mergeWaits(a.otherwise)
        a.children = inner1.actions; a.otherwise = inner2.actions; merged += inner1.merged + inner2.merged
        if a.kind == .WAIT && a.enabled {
            if var last = result.last { last.delayAfterMs += a.durationMs; result[result.count - 1] = last }
            else { pendingBefore += a.durationMs }
            here += 1
            continue
        }
        if pendingBefore > 0 { a.delayBeforeMs += pendingBefore; pendingBefore = 0 }
        result.append(a)
    }
    if pendingBefore > 0 { return (list, 0) }   // solo había esperas: no se toca
    return (result, merged + here)
}

/// 50 → "50"; 12,5 → "12,5".
func decimalText(_ value: Float) -> String {
    abs(value - value.rounded()) < 0.05 ? String(Int(value.rounded())) : String(format: "%.1f", value).replacingOccurrences(of: ".", with: ",")
}

/// Acepta "1,5" o "1.5".
func parseDecimal(_ text: String) -> Double? {
    Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")).flatMap { $0.isFinite ? $0 : nil }
}

/// «4 min» si son minutos justos; si no, segundos («1,5»). Para los campos de tiempo (como en Android).
func timeText(_ ms: Int64) -> String { ms >= 60_000 && ms % 60_000 == 0 ? "\(ms / 60_000) min" : secondsText(ms) }

/// Lee «30», «1,5», «30 s» o «4 min» y lo pasa a milisegundos.
func parseTime(_ input: String) -> Int64? {
    var t = input.trimmingCharacters(in: .whitespaces).lowercased()
    let minutes = t.hasSuffix("min") || t.hasSuffix("m")
    for suffix in ["min", "m", "s"] where t.hasSuffix(suffix) { t = String(t.dropLast(suffix.count)).trimmingCharacters(in: .whitespaces); break }
    guard let v = parseDecimal(t), v >= 0, v < 1e6 else { return nil }
    return Int64(((minutes ? v * 60 : v) * 1000).rounded())
}

func secondsText(_ ms: Int64) -> String {
    if ms % 1000 == 0 { return String(ms / 1000) }
    return String(format: ms % 100 == 0 ? "%.1f" : "%.2f", Double(ms) / 1000).replacingOccurrences(of: ".", with: ",")
}

func colorHex(_ value: Int32) -> String { String(format: "#%06X", Int(value) & 0x00FFFFFF) }

func parseColor(_ value: String) -> Int32? {
    let clean = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
    guard clean.count == 6, let number = Int(clean, radix: 16) else { return nil }
    return Int32(bitPattern: UInt32(0xFF000000) | UInt32(number))
}

/// Avisa si un recorte se puede confundir: muy pequeño o casi de un solo color.
func templateWarning(_ saved: SavedMacroTemplate, encoded: String?) -> String? {
    let t = saved.template
    let small = t.width * t.height < 3_000 || min(t.width, t.height) < 30
    var flat = false
    if let encoded, let data = Data(base64Encoded: encoded), let image = UIImage(data: data), let px = TemplateMatcher.pixels(image) {
        let step = max(1, px.colors.count / 4000)
        var n = 0.0, sum = [0.0, 0.0, 0.0], sq = [0.0, 0.0, 0.0]
        for i in stride(from: 0, to: px.colors.count, by: step) {
            n += 1
            for c in 0..<3 { let v = Double((px.colors[i] >> UInt32(c * 8)) & 255); sum[c] += v; sq[c] += v * v }
        }
        flat = (0..<3).allSatisfy { sq[$0] / n - (sum[$0] / n) * (sum[$0] / n) < 600 }
    }
    if small && flat { return "Recorte muy pequeño y casi de un solo color: se confunde con otras partes de la pantalla. Recórtalo más grande, con texto o bordes." }
    if small { return "Recorte muy pequeño: puede confundirse con otras partes. Mejor recórtalo un poco más grande (con algo de texto o borde)." }
    if flat { return "Recorte casi de un solo color: se parece a muchas zonas de la pantalla y puede tocar donde no toca. Recorta algo con texto, dibujo o bordes." }
    return nil
}

// MARK: «La orden» de un paso con imagen (igual que StepOrder.kt de Android)

/// Qué hace el paso cuando ve la imagen. Cambiarlo conserva la imagen, el sitio del toque,
/// el nombre que puso la persona, la nota y las esperas.
enum ImageOrder: String, CaseIterable, Identifiable {
    case TAP, DOUBLE, HOLD, ALWAYS, WAIT, IF
    var id: String { rawValue }
    var label: String {
        switch self {
        case .TAP: return "👆 Tocarla"
        case .DOUBLE: return "👆👆 Doble toque"
        case .HOLD: return "✋ Mantenerla pulsada"
        case .ALWAYS: return "⚡ Tocarla siempre que salga"
        case .WAIT: return "👀 Solo esperar a que salga"
        case .IF: return "❓ Si sale… y si no…"
        }
    }
    var short: String { String(label.drop(while: { !$0.isLetter })) }
}

func orderOf(_ a: MacroAction) -> ImageOrder? {
    switch a.kind {
    case .CLICK_IMAGE: return a.clickStyle == .DOUBLE ? .DOUBLE : a.clickStyle == .LONG ? .HOLD : .TAP
    case .WHEN_IMAGE: return .ALWAYS
    case .WAIT_IMAGE: return .WAIT
    case .IF_IMAGE: return .IF
    default: return nil
    }
}

private func blockTap(_ a: MacroAction) -> MacroAction? {
    a.children.first { $0.kind == .CLICK_IMAGE && $0.templateId == a.templateId }
}

/// ¿Tiene dentro pasos que se perderían? (algo más que el toque a su imagen).
func hasInnerSteps(_ a: MacroAction) -> Bool {
    guard a.kind == .WHEN_IMAGE || a.kind == .IF_IMAGE else { return false }
    return !a.otherwise.isEmpty || a.children.count > 1 || (a.children.count == 1 && blockTap(a) == nil)
}

func possibleOrders(_ a: MacroAction) -> [ImageOrder] {
    guard orderOf(a) != nil else { return [] }
    let inner = hasInnerSteps(a)
    return ImageOrder.allCases.filter { o in
        switch o {
        case .TAP, .DOUBLE, .HOLD, .WAIT: return !inner
        case .ALWAYS: return a.otherwise.isEmpty
        case .IF: return true
        }
    }
}

private func autoStepName(_ kind: MacroKind, _ image: String) -> String {
    switch kind {
    case .CLICK_IMAGE: return "Tocar «\(image)»"
    case .WAIT_IMAGE: return "Esperar «\(image)»"
    case .IF_IMAGE: return "Si se ve «\(image)»"
    case .WHEN_IMAGE: return "Siempre que salga «\(image)»"
    default: return kindLabel(kind).replacingOccurrences(of: "…", with: "")
    }
}

private func hasAutoName(_ a: MacroAction) -> Bool {
    let oldNames: Set<String> = ["Tocar / deslizar", "Buscar imagen y tocarla", "Esperar a ver una imagen", "Siempre que se vea una imagen"]
    return a.name.isEmpty || a.name == kindLabel(a.kind).replacingOccurrences(of: "…", with: "") || oldNames.contains(a.name) || (a.name.contains("«") && a.name.hasSuffix("»"))
}

/// Cambia lo que hace el paso. Si la orden no es posible, lo devuelve tal cual.
func changeOrder(_ a: MacroAction, to: ImageOrder, image imageName: String, timeoutMs: Int64) -> MacroAction {
    guard let from = orderOf(a), from != to, possibleOrders(a).contains(to) else { return a }
    let tap = blockTap(a) ?? a
    let image = imageName.isEmpty ? "la imagen" : imageName
    func tapStep() -> MacroAction {
        var t = ruleTap(a.templateId, image)
        t.clickTarget = tap.clickTarget; t.clickOffsetX = tap.clickOffsetX; t.clickOffsetY = tap.clickOffsetY
        t.clickStyle = tap.clickStyle; t.durationMs = tap.kind == .CLICK_IMAGE ? tap.durationMs : 80
        return t
    }
    let searchMs: Int64 = a.kind == .CLICK_IMAGE ? (a.waitMs > 0 ? a.waitMs : timeoutMs) : a.kind == .WAIT_IMAGE ? a.durationMs : 30_000
    let kind: MacroKind
    switch to {
    case .TAP, .DOUBLE, .HOLD: kind = .CLICK_IMAGE
    case .ALWAYS: kind = .WHEN_IMAGE
    case .WAIT: kind = .WAIT_IMAGE
    case .IF: kind = .IF_IMAGE
    }
    var b = a
    b.kind = kind
    if hasAutoName(a) { b.name = String(autoStepName(kind, image).prefix(100)) }
    // El sitio y la forma del toque se guardan también en el paso, para poder volver atrás sin perderlos.
    b.clickTarget = tap.clickTarget; b.clickOffsetX = tap.clickOffsetX; b.clickOffsetY = tap.clickOffsetY; b.clickStyle = tap.clickStyle
    b.untilGone = false
    switch to {
    case .TAP, .DOUBLE, .HOLD:
        let style: MacroClickStyle = to == .DOUBLE ? .DOUBLE : to == .HOLD ? .LONG : .SINGLE
        let tapMs: Int64 = tap.kind == .CLICK_IMAGE ? tap.durationMs : 80
        b.clickStyle = style; b.children = []; b.otherwise = []
        b.durationMs = style == .LONG ? max(tapMs, 800) : (tapMs >= 500 ? 80 : min(max(tapMs, 1), 60_000))
        b.waitMs = a.kind == .CLICK_IMAGE ? a.waitMs : a.kind == .WAIT_IMAGE ? min(max(searchMs, 1_000), 3_600_000) : 0
    case .ALWAYS:
        b.durationMs = 80; b.waitMs = 3_000; b.otherwise = []
        b.children = a.kind == .IF_IMAGE ? a.children : [tapStep()]
    case .WAIT:
        b.durationMs = min(max(searchMs, 1_000), 600_000); b.children = []; b.otherwise = []; b.waitMs = 0
    case .IF:
        b.durationMs = 80; b.waitMs = 0
        b.children = a.kind == .WHEN_IMAGE ? a.children : [tapStep()]
        b.otherwise = a.otherwise
    }
    return b
}
