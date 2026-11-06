import SwiftUI
import PhotosUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    @ObservedObject var cloud: CloudStore
    @State private var project: MacroProject
    @State private var localProjects: [MacroProject]
    @State private var link = ""
    @State private var showingImporter = false
    @State private var showingImageImporter = false
    @State private var showingExporter = false
    @State private var document = MacroFileDocument()
    @State private var alert = ""
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var sourceImage: UIImage?
    @State private var showingCropper = false
    @State private var recropTemplateID: String?
    @State private var deleteTemplate: SavedMacroTemplate?
    @State private var deleteProjectID: String?

    init(cloud: CloudStore) {
        self.cloud = cloud
        let state = LocalProjectStore.loadState()
        _project = State(initialValue: state.active)
        _localProjects = State(initialValue: state.projects)
    }

    var body: some View {
        TabView {
            editorTab
                .tabItem { Label("Editor", systemImage: "list.number") }
            capturesTab
                .tabItem { Label("Capturas", systemImage: "photo.on.rectangle") }
            projectsTab
                .tabItem { Label("Proyectos", systemImage: "folder") }
            cloudTab
                .tabItem { Label("Nube", systemImage: "arrow.triangle.2.circlepath") }
            helpTab
                .tabItem { Label("Ayuda", systemImage: "questionmark.circle") }
        }
        .onChange(of: project) { value in persistCurrent(value) }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.json]) { result in importProject(result) }
        .fileExporter(isPresented: $showingExporter, document: document, contentType: .json, defaultFilename: "PrisonMacro-\(safeFileName(project.name)).json") { result in
            if case .failure(let error) = result { alert = "No se pudo exportar: \(error.localizedDescription)" }
        }
        .fileImporter(isPresented: $showingImageImporter, allowedContentTypes: [.image]) { result in importImage(result) }
        .alert("PrisonMacro", isPresented: Binding(get: { !alert.isEmpty }, set: { if !$0 { alert = "" } })) {
            Button("OK") { alert = "" }
        } message: { Text(alert) }
        .onOpenURL { url in Task { await cloud.consumeLink(url.absoluteString) } }
        .onChange(of: selectedPhoto) { item in
            guard let item else { return }
            Task {
                do {
                    guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                        await MainActor.run { alert = "No se pudo leer la imagen seleccionada" }
                        return
                    }
                    await MainActor.run {
                        sourceImage = image; recropTemplateID = nil; showingCropper = true; selectedPhoto = nil
                    }
                } catch { await MainActor.run { alert = "No se pudo importar la captura: \(error.localizedDescription)" } }
            }
        }
        .sheet(isPresented: $showingCropper) {
            if let sourceImage {
                ImageCropView(
                    image: sourceImage,
                    screenWidth: recropTemplateID.flatMap { id in project.templates.first(where: { $0.id == id })?.template.screenWidth },
                    screenHeight: recropTemplateID.flatMap { id in project.templates.first(where: { $0.id == id })?.template.screenHeight },
                    originX: recropTemplateID.flatMap { id in project.templates.first(where: { $0.id == id })?.template.x } ?? 0,
                    originY: recropTemplateID.flatMap { id in project.templates.first(where: { $0.id == id })?.template.y } ?? 0
                ) { cropped in saveCropped(cropped) }
            }
        }
        .confirmationDialog("Borrar captura", isPresented: Binding(get: { deleteTemplate != nil }, set: { if !$0 { deleteTemplate = nil } }), titleVisibility: .visible) {
            Button("Borrar", role: .destructive) {
                if let deleteTemplate { removeTemplate(deleteTemplate) }
                deleteTemplate = nil
            }
            Button("Cancelar", role: .cancel) { deleteTemplate = nil }
        } message: {
            Text("Se quitará de la biblioteca y de los pasos que la usen. El archivo exportado anterior no cambiará.")
        }
        .confirmationDialog("Borrar proyecto", isPresented: Binding(get: { deleteProjectID != nil }, set: { if !$0 { deleteProjectID = nil } }), titleVisibility: .visible) {
            Button("Borrar del iPhone", role: .destructive) {
                if let id = deleteProjectID { removeLocalProject(id) }
                deleteProjectID = nil
            }
            Button("Cancelar", role: .cancel) { deleteProjectID = nil }
        } message: {
            Text("Se borrará esta copia local y sus capturas. Las copias exportadas o subidas a la nube no cambian.")
        }
    }

    private var editorTab: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    projectHeader
                    executionReadiness
                    Divider()
                    Text("Pasos de la macro").font(.title2.weight(.semibold))
                    if project.actions.isEmpty {
                        EmptyState(title: "Aún no hay pasos", detail: "Añade una espera, un clic, una condición o una función del dispositivo.")
                    } else {
                        ForEach(Array(project.actions.indices), id: \.self) { index in
                            ActionEditorCard(
                                number: index + 1,
                                action: $project.actions[index],
                                templates: project.templates,
                                images: project.imagesBase64,
                                onMoveUp: { moveAction(at: index, delta: -1) },
                                onMoveDown: { moveAction(at: index, delta: 1) },
                                onDuplicate: { duplicateAction(at: index) },
                                onDelete: { project.actions.remove(at: index) }
                            )
                        }
                    }
                    addActionMenu
                }
                .padding(.horizontal).padding(.bottom, 24)
            }
            .navigationTitle("PrisonMacro")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var projectHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image("PrisonLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 54, height: 54)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .accessibilityLabel("Logo de PrisonMacro")
                VStack(alignment: .leading, spacing: 3) {
                    Text("PrisonMacro").font(.headline)
                    Text("Editor de macros y capturas").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            TextField("Nombre del proyecto", text: $project.name).font(.title3.weight(.semibold)).textFieldStyle(.roundedBorder)
            HStack {
                Label("\(recursiveActionCount(project.actions)) pasos", systemImage: "list.number")
                Label("\(project.templates.count) capturas", systemImage: "photo")
                Spacer()
            }.font(.subheadline).foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Button { showingImporter = true } label: { Label("Importar", systemImage: "square.and.arrow.down") }.buttonStyle(.bordered)
                Button {
                    document = MacroFileDocument(data: (try? MacroJSON.encoder.encode(project)) ?? Data()); showingExporter = true
                } label: { Label("Exportar", systemImage: "square.and.arrow.up") }.buttonStyle(.borderedProminent)
            }
            Text("El mismo JSON conserva nombres, descripciones, esperas, acciones y capturas para abrirlo en Android o iPhone.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var executionReadiness: some View {
        let issues = projectExecutionIssues(project)
        return Group {
            if issues.isEmpty {
                Label("Lista para ejecutar en Android", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.green)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Label("Revisa antes de ejecutar", systemImage: "exclamationmark.triangle.fill")
                        .font(.headline).foregroundStyle(.red)
                    ForEach(Array(issues.prefix(4).enumerated()), id: \.offset) { _, issue in
                        Text("• \(issue)").font(.subheadline)
                    }
                    if issues.count > 4 { Text("• Y \(issues.count - 4) aviso(s) más").font(.subheadline) }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.red.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private var addActionMenu: some View {
        Menu {
            macroKindMenu { project.actions.append(defaultAction($0)) }
        } label: {
            Label("Añadir paso", systemImage: "plus.circle.fill").font(.headline).frame(maxWidth: .infinity)
        }.buttonStyle(.borderedProminent)
    }

    private var capturesTab: some View {
        NavigationStack {
            List {
                Section {
                    PhotosPicker(selection: $selectedPhoto, matching: .images, photoLibrary: .shared()) {
                        Label("Importar captura y recortarla", systemImage: "photo.on.rectangle")
                    }
                    Button { showingImageImporter = true } label: { Label("Importar PNG/JPG desde Archivos", systemImage: "folder") }
                    Text("Puedes traer capturas hechas en Android/BlueStacks o elegirlas del carrete. Cada paso puede usar una captura distinta.")
                        .font(.footnote).foregroundStyle(.secondary)
                } header: { Text("Biblioteca de capturas") }
                Section {
                    if project.templates.isEmpty {
                        Text("No hay capturas guardadas todavía.").foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(project.templates.indices), id: \.self) { index in
                            CaptureEditorRow(
                                saved: $project.templates[index],
                                image: project.imagesBase64[project.templates[index].template.file],
                                isPrincipal: project.template?.file == project.templates[index].template.file,
                                onMakePrincipal: { project.template = project.templates[index].template },
                                onRecrop: { beginRecrop(project.templates[index]) },
                                onDelete: { deleteTemplate = project.templates[index] }
                            )
                        }
                    }
                } header: { Text("Capturas guardadas · \(project.templates.count)") } footer: {
                    Text("La captura principal solo sirve como referencia de resolución/inicio rápido. En cada condición o clic eliges otra desde su propio selector.")
                }
            }.navigationTitle("Capturas")
        }
    }

    private var projectsTab: some View {
        NavigationStack {
            List {
                Section {
                    Button { createLocalProject() } label: { Label("Proyecto nuevo", systemImage: "plus") }
                    Button { duplicateCurrentProject() } label: { Label("Duplicar proyecto actual", systemImage: "doc.on.doc") }
                } footer: {
                    Text("Cada proyecto conserva por separado sus pasos, descripciones y capturas. Puedes exportarlo o subirlo desde las otras pestañas.")
                }
                Section("Guardados en este iPhone · \(localProjects.count)") {
                    ForEach(localProjects) { saved in
                        HStack(spacing: 10) {
                            Image(systemName: saved.id == project.id ? "checkmark.circle.fill" : "folder")
                                .foregroundStyle(saved.id == project.id ? Color.accentColor : Color.secondary)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(saved.name).font(.headline)
                                Text("\(recursiveActionCount(saved.actions)) pasos · \(saved.templates.count) capturas")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if saved.id != project.id { Button("Abrir") { project = saved } }
                            Menu {
                                Button("Duplicar") { duplicateProject(saved) }
                                Button("Borrar", role: .destructive) { deleteProjectID = saved.id }
                                    .disabled(localProjects.count <= 1)
                            } label: { Image(systemName: "ellipsis.circle") }
                        }
                    }
                }
            }
            .navigationTitle("Mis proyectos")
        }
    }

    private var cloudTab: some View {
        NavigationStack {
            Form {
                Section("Cuenta") {
                    TextField("Correo", text: $cloud.email).textInputAutocapitalization(.never).keyboardType(.emailAddress)
                    Button("Enviar enlace de acceso") { Task { await cloud.sendLink() } }
                    TextField("Pega aquí el enlace recibido", text: $link, axis: .vertical)
                    Button("Conectar con enlace") { Task { await cloud.consumeLink(link) } }
                    Text(cloud.status).font(.footnote).foregroundStyle(.secondary)
                    if cloud.idTokenAvailable { Button("Cerrar sesión y cambiar de cuenta", role: .destructive) { cloud.signOut() } }
                }
                Section("Sincronización") {
                    HStack { Text("Sesiones activas"); Spacer(); Text("\(cloud.activeSessions)").font(.title3.weight(.semibold)) }
                    Text("Se cuentan dispositivos vistos durante los últimos 3 minutos. Cada correo tiene sus propios proyectos.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("Subir proyecto actual") { Task { await cloud.upload(project) } }.disabled(!cloud.idTokenAvailable)
                    Button("Actualizar nube") { Task { await cloud.refresh() } }
                    if cloud.projects.isEmpty {
                        Text("No hay proyectos descargables en esta cuenta todavía.").foregroundStyle(.secondary)
                    } else {
                        ForEach(cloud.projects) { item in
                            HStack {
                                VStack(alignment: .leading) { Text(item.name); Text(Date(timeIntervalSince1970: Double(item.updatedAtMs) / 1000), style: .date).font(.caption).foregroundStyle(.secondary) }
                                Spacer()
                                Button("Abrir") { Task { if let downloaded = await cloud.download(item) { project = downloaded } } }
                            }
                        }
                    }
                }
                Section("Flujo recomendado") {
                    Text("Crea o corrige la macro en iPhone, pulsa Subir proyecto y en Android usa Nube → Descargar. Para pasarla sin cuenta, Exportar genera un JSON con las capturas incluidas.")
                }
            }.navigationTitle("Nube y dispositivos")
        }
    }

    private var helpTab: some View {
        NavigationStack {
            List {
                Section("Cómo crear un paso") {
                    HelpRow(title: "Detectar imagen y hacer clic", detail: "Añade el paso, elige la captura desde su miniatura y selecciona centro, desplazamiento o punto fijo. En Android puedes capturar el punto tocando la pantalla.")
                    HelpRow(title: "SI SE VE UNA IMAGEN", detail: "La condición tiene su propia captura. ENTONCES ejecuta los pasos si aparece; SI NO vacío significa continuar.")
                    HelpRow(title: "Esperar y repetir", detail: "Usa esperas entre abrir una app, detectar una pantalla y realizar el siguiente clic. Repetir agrupa pasos en una secuencia.")
                    HelpRow(title: "Colores", detail: "En Android puedes detectar un color en un punto exacto o en cualquier lugar, con tolerancia. El iPhone conserva esa configuración al editar y sincronizar.")
                }
                Section("Qué hace cada dispositivo") {
                    Text("El iPhone es el editor y la biblioteca sincronizada. Android/BlueStacks es quien ejecuta las acciones mediante sus permisos de captura y accesibilidad. Las capturas viajan dentro del JSON o de la nube.")
                }
                Section("Seguridad de pruebas") {
                    Text("Prueba primero en el Laboratorio de PrisonMacro. No ejecutes macros sobre compras, mensajes, cuentas ni partidas competitivas mientras estés ajustando coordenadas.")
                }
            }.navigationTitle("Ayuda")
        }
    }

    private func importProject(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else {
            if case .failure(let error) = result { alert = "No se pudo abrir el proyecto: \(error.localizedDescription)" }
            return
        }
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let value = try MacroJSON.decoder.decode(MacroProject.self, from: Data(contentsOf: url))
            guard !value.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ImportError.invalidProject }
            project = value
        } catch { alert = "El JSON no es una macro válida: \(error.localizedDescription)" }
    }

    private func importImage(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else {
            if case .failure(let error) = result { alert = "No se pudo abrir la imagen: \(error.localizedDescription)" }
            return
        }
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { alert = "No se pudo leer la imagen elegida"; return }
        sourceImage = image; recropTemplateID = nil; showingCropper = true
    }

    private func saveCropped(_ cropped: CroppedTemplateImage) {
        let file = "template-\(UUID().uuidString.lowercased()).png"
        if let targetID = recropTemplateID, let index = project.templates.firstIndex(where: { $0.id == targetID }) {
            let old = project.templates[index]
            let replacement = MacroTemplate(file: file, x: cropped.x, y: cropped.y, width: cropped.width, height: cropped.height, screenWidth: cropped.screenWidth, screenHeight: cropped.screenHeight)
            project.imagesBase64.removeValue(forKey: old.template.file); project.imagesBase64[file] = cropped.png.base64EncodedString()
            project.templates[index].template = replacement
            if project.template?.file == old.template.file { project.template = replacement }
        } else {
            let template = MacroTemplate(file: file, x: cropped.x, y: cropped.y, width: cropped.width, height: cropped.height, screenWidth: cropped.screenWidth, screenHeight: cropped.screenHeight)
            let saved = SavedMacroTemplate(id: UUID().uuidString.lowercased(), name: "Captura \(project.templates.count + 1)", template: template)
            project.templates.append(saved); project.imagesBase64[file] = cropped.png.base64EncodedString()
            if project.template == nil { project.template = template }
        }
        recropTemplateID = nil; sourceImage = nil
    }

    private func beginRecrop(_ saved: SavedMacroTemplate) {
        guard let encoded = project.imagesBase64[saved.template.file], let data = Data(base64Encoded: encoded), let image = UIImage(data: data) else { alert = "La captura no tiene datos PNG dentro del proyecto"; return }
        recropTemplateID = saved.id; sourceImage = image; showingCropper = true
    }

    private func removeTemplate(_ saved: SavedMacroTemplate) {
        project.templates.removeAll { $0.id == saved.id }; project.imagesBase64.removeValue(forKey: saved.template.file)
        project.actions = clearTemplateReferences(project.actions, templateID: saved.id)
        if project.template?.file == saved.template.file { project.template = project.templates.first?.template }
    }

    private func moveAction(at index: Int, delta: Int) {
        let target = index + delta; guard project.actions.indices.contains(target) else { return }; project.actions.swapAt(index, target)
    }

    private func duplicateAction(at index: Int) {
        guard project.actions.indices.contains(index) else { return }
        var copy = project.actions[index]; copy.id = UUID().uuidString.lowercased(); project.actions.insert(copy, at: index + 1)
    }

    private func persistCurrent(_ value: MacroProject) {
        var updated = localProjects
        if let index = updated.firstIndex(where: { $0.id == value.id }) { updated[index] = value }
        else { updated.append(value) }
        if updated != localProjects { localProjects = updated }
        LocalProjectStore.save(active: value, projects: updated)
    }

    private func createLocalProject() {
        project = MacroProject(name: "Proyecto \(localProjects.count + 1)")
    }

    private func duplicateCurrentProject() { duplicateProject(project) }

    private func duplicateProject(_ source: MacroProject) {
        var copy = source
        copy.id = UUID().uuidString.lowercased()
        copy.name = "\(source.name) · copia"
        project = copy
    }

    private func removeLocalProject(_ id: String) {
        guard localProjects.count > 1 else { return }
        let remaining = localProjects.filter { $0.id != id }
        localProjects = remaining
        if project.id == id, let next = remaining.first {
            project = next
            LocalProjectStore.save(active: next, projects: remaining)
        } else {
            LocalProjectStore.save(active: project, projects: remaining)
        }
    }
}

private enum ImportError: LocalizedError {
    case invalidProject
    var errorDescription: String? { "Falta el nombre del proyecto" }
}

private struct ActionEditorCard: View {
    let number: Int
    @Binding var action: MacroAction
    let templates: [SavedMacroTemplate]
    let images: [String: String]
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onDuplicate: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("\(number)").font(.title3.weight(.bold)).foregroundStyle(.tint)
                VStack(alignment: .leading) { Text(kindLabel(action.kind)).font(.headline); Text("Paso editable y sincronizable").font(.caption).foregroundStyle(.secondary) }
                Spacer(); Toggle("Activo", isOn: $action.enabled).labelsHidden()
            }
            TextField("Nombre del paso", text: $action.name).textFieldStyle(.roundedBorder)
            TextField("Descripción para no liarte (opcional)", text: $action.description, axis: .vertical).textFieldStyle(.roundedBorder)
            if action.kind == .IF_IMAGE || action.kind == .CLICK_IMAGE || action.kind == .WAIT_IMAGE { ImageActionEditor(action: $action, templates: templates, images: images) }
            actionDetails
            if action.kind == .IF_IMAGE || action.kind == .IF_COLOR {
                ConditionFlowSummary(action: action)
                BranchEditor(title: "ENTONCES", actions: $action.children, templates: templates, images: images)
                BranchEditor(title: "SI NO", actions: $action.otherwise, templates: templates, images: images)
            }
            HStack(spacing: 5) {
                Button("↑", action: onMoveUp).buttonStyle(.bordered); Button("↓", action: onMoveDown).buttonStyle(.bordered)
                Button("Duplicar", action: onDuplicate).buttonStyle(.bordered); Spacer()
                Button("Borrar", role: .destructive, action: onDelete).buttonStyle(.bordered)
            }
        }
        .padding(12).background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder private var actionDetails: some View {
        switch action.kind {
        case .WAIT, .WAIT_IMAGE, .CLOSE_ACTIVE:
            IntegerField(title: action.kind == .WAIT_IMAGE ? "Tiempo máximo esperando la imagen (ms)" : action.kind == .WAIT ? "Duración de espera (ms)" : "Espera para mostrar Recientes (ms)", value: $action.durationMs, minimum: action.kind == .WAIT_IMAGE ? 1_000 : action.kind == .WAIT ? 1 : 500, maximum: action.kind == .WAIT || action.kind == .WAIT_IMAGE ? 600_000 : 3_000)
        case .REPEAT:
            IntegerField(title: "Repeticiones", value: Binding(get: { Int64(action.count) }, set: { action.count = Int($0) }), minimum: 1, maximum: 100)
        case .GESTURE: GestureEditor(action: $action)
        case .OPEN_APP:
            TextField("Paquete de la aplicación", text: $action.packageName).textFieldStyle(.roundedBorder)
            Text("Android abrirá el paquete indicado. En iPhone este paso se guarda y se sincroniza, pero no ejecuta BlueStacks.").font(.footnote).foregroundStyle(.secondary)
        case .IF_COLOR: ColorActionEditor(action: $action)
        case .HOME, .BACK, .RECENTS, .NOTIFICATIONS, .QUICK_SETTINGS:
            Text("Función del dispositivo. Se ejecuta en Android con Accesibilidad.").font(.footnote).foregroundStyle(.secondary)
        case .CLICK_IMAGE, .IF_IMAGE: EmptyView()
        }
    }
}

private struct ImageActionEditor: View {
    @Binding var action: MacroAction
    let templates: [SavedMacroTemplate]
    let images: [String: String]
    var selected: SavedMacroTemplate? { templates.first(where: { $0.id == action.templateId }) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(action.kind == .CLICK_IMAGE ? "Imagen que debe detectar para hacer clic" : action.kind == .WAIT_IMAGE ? "Imagen que debe aparecer para continuar" : "Imagen de esta condición").font(.subheadline.weight(.semibold))
            HStack(alignment: .top, spacing: 10) {
                TemplateThumbnail(saved: selected, encoded: selected.flatMap { images[$0.template.file] }, size: CGSize(width: 88, height: 62))
                VStack(alignment: .leading, spacing: 5) {
                    Text(selected?.name ?? "NINGUNA").font(.subheadline.weight(.semibold))
                    Text(selected == nil ? "Elige una captura para este paso" : "\(selected!.template.width)×\(selected!.template.height) px").font(.caption).foregroundStyle(selected == nil ? .red : .secondary)
                }
            }
            if templates.isEmpty {
                Text("No hay capturas guardadas. Ve a la pestaña Capturas para importar o recortar una.")
                    .font(.caption).foregroundStyle(.red)
            } else {
                Text("Capturas disponibles para este paso").font(.caption.weight(.semibold))
                Text("Pulsa una miniatura para cambiarla. La captura elegida queda marcada y no modifica los demás pasos.")
                    .font(.caption).foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 8) {
                        ForEach(templates) { saved in
                            CaptureChoice(
                                saved: saved,
                                encoded: images[saved.template.file],
                                isSelected: saved.id == action.templateId,
                                onSelect: { action.templateId = saved.id }
                            )
                        }
                    }
                }
            }
            Picker("Cambiar captura de este paso", selection: $action.templateId) {
                Text("Sin seleccionar").tag("")
                ForEach(templates) { saved in Text(saved.name).tag(saved.id) }
            }.pickerStyle(.menu)
            if action.kind == .CLICK_IMAGE {
                Picker("Dónde pulsar", selection: $action.clickTarget) {
                    Text("Centro de la coincidencia").tag(MacroClickTarget.CENTER)
                    Text("Desplazamiento relativo").tag(MacroClickTarget.OFFSET)
                    Text("Punto fijo de pantalla").tag(MacroClickTarget.FIXED)
                }.pickerStyle(.menu)
                if action.clickTarget == .OFFSET || action.clickTarget == .FIXED {
                    HStack {
                        DecimalField(title: action.clickTarget == .FIXED ? "Punto X (%)" : "Desplazamiento X (%)", value: $action.clickOffsetX)
                        DecimalField(title: action.clickTarget == .FIXED ? "Punto Y (%)" : "Desplazamiento Y (%)", value: $action.clickOffsetY)
                    }
                }
                Picker("Tipo de clic", selection: $action.clickStyle) {
                    Text("Un clic").tag(MacroClickStyle.SINGLE); Text("Doble clic").tag(MacroClickStyle.DOUBLE); Text("Pulsación larga").tag(MacroClickStyle.LONG)
                }.pickerStyle(.menu)
            }
        }.padding(10).background(Color.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct CaptureChoice: View {
    let saved: SavedMacroTemplate
    let encoded: String?
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 4) {
                TemplateThumbnail(saved: saved, encoded: encoded, size: CGSize(width: 124, height: 76))
                Text(saved.name)
                    .font(.caption)
                    .fontWeight(.semibold)
                    .lineLimit(2)
                Text(isSelected ? "ELEGIDA" : "ELEGIR")
                    .font(.caption2)
                    .fontWeight(.bold)
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
            }
            .padding(6)
            .frame(width: 136, alignment: .leading)
            .background(isSelected ? Color.accentColor.opacity(0.16) : Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(isSelected ? Color.accentColor : Color.clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(saved.name), \(isSelected ? "elegida" : "elegir")")
    }
}

private struct GestureEditor: View {
    @Binding var action: MacroAction
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("Coordenadas relativas a la pantalla (0–100 %)").font(.footnote).foregroundStyle(.secondary)
            if action.points.isEmpty { Button("Añadir punto") { action.points = [MacroPoint(x: 0.5, y: 0.5)] } }
            else {
                HStack {
                    DecimalField(title: "X (%)", value: Binding(get: { action.points[0].x * 100 }, set: { action.points[0].x = $0 / 100 }))
                    DecimalField(title: "Y (%)", value: Binding(get: { action.points[0].y * 100 }, set: { action.points[0].y = $0 / 100 }))
                }
                if action.points.count > 1 { Text("Trayectoria guardada: \(action.points.count) puntos. El trazado completo se conserva en el JSON.").font(.caption).foregroundStyle(.secondary) }
            }
            IntegerField(title: "Duración (ms)", value: $action.durationMs, minimum: 1, maximum: 60_000)
            Text("Para obtener el punto exacto sin escribirlo, abre esta misma acción en Android y pulsa «Elegir X/Y tocando la pantalla».").font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct ColorActionEditor: View {
    @Binding var action: MacroAction
    @State private var hex: String
    init(action: Binding<MacroAction>) { _action = action; _hex = State(initialValue: colorHex(action.wrappedValue.targetColor)) }
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack { Circle().fill(colorFromInt(action.targetColor)).frame(width: 28, height: 28); TextField("Color #RRGGBB", text: $hex).textFieldStyle(.roundedBorder).onSubmit { if let value = parseColor(hex) { action.targetColor = value } } }
            IntegerField(title: "Tolerancia RGB", value: Binding(get: { Int64(action.colorTolerance) }, set: { action.colorTolerance = Int($0) }), minimum: 0, maximum: 255)
            Picker("Búsqueda", selection: $action.colorMode) { Text("Cualquier punto").tag(MacroSearchMode.ANYWHERE); Text("Punto exacto").tag(MacroSearchMode.EXACT) }.pickerStyle(.menu)
            if action.colorMode == .EXACT { HStack { DecimalField(title: "X (%)", value: Binding(get: { action.colorX * 100 }, set: { action.colorX = $0 / 100 })); DecimalField(title: "Y (%)", value: Binding(get: { action.colorY * 100 }, set: { action.colorY = $0 / 100 })) } }
        }.onChange(of: hex) { value in if let parsed = parseColor(value) { action.targetColor = parsed } }
    }
}

private struct BranchEditor: View {
    let title: String
    @Binding var actions: [MacroAction]
    let templates: [SavedMacroTemplate]
    let images: [String: String]
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(.subheadline.weight(.semibold)); Spacer()
                Menu {
                    macroKindMenu { actions.append(defaultAction($0)) }
                } label: { Label("Añadir acción", systemImage: "plus.circle").font(.caption) }
            }
            if actions.isEmpty { Text(title == "SI NO" ? "Vacío = continuar con el siguiente paso" : "Todavía no hay acciones").font(.caption).foregroundStyle(.secondary) }
            ForEach(Array(actions.indices), id: \.self) { index in
                BranchActionRow(action: $actions[index], templates: templates, images: images, onDelete: { actions.remove(at: index) })
            }
        }.padding(10).background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct BranchActionRow: View {
    @Binding var action: MacroAction
    let templates: [SavedMacroTemplate]
    let images: [String: String]
    let onDelete: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack { Text(kindLabel(action.kind)).font(.caption.weight(.semibold)); Spacer(); Toggle("Activo", isOn: $action.enabled).labelsHidden(); Button(role: .destructive, action: onDelete) { Image(systemName: "trash") } }
            TextField("Nombre", text: $action.name).textFieldStyle(.roundedBorder)
            TextField("Descripción", text: $action.description).font(.caption)
            if action.kind == .IF_IMAGE || action.kind == .CLICK_IMAGE || action.kind == .WAIT_IMAGE { ImageActionEditor(action: $action, templates: templates, images: images) }
            else if action.kind == .GESTURE { GestureEditor(action: $action) }
            else if action.kind == .WAIT || action.kind == .WAIT_IMAGE || action.kind == .CLOSE_ACTIVE { IntegerField(title: action.kind == .WAIT_IMAGE ? "Tiempo máximo esperando la imagen (ms)" : action.kind == .WAIT ? "Duración de espera (ms)" : "Espera para mostrar Recientes (ms)", value: $action.durationMs, minimum: action.kind == .WAIT_IMAGE ? 1_000 : action.kind == .WAIT ? 1 : 500, maximum: action.kind == .WAIT || action.kind == .WAIT_IMAGE ? 600_000 : 3_000) }
            else if action.kind == .REPEAT { IntegerField(title: "Repeticiones", value: Binding(get: { Int64(action.count) }, set: { action.count = Int($0) }), minimum: 1, maximum: 100) }
            else if action.kind == .OPEN_APP { TextField("Paquete", text: $action.packageName).textFieldStyle(.roundedBorder) }
            else if action.kind == .IF_COLOR { ColorActionEditor(action: $action) }
            if action.kind == .IF_IMAGE || action.kind == .IF_COLOR {
                ConditionFlowSummary(action: action)
                AnyView(BranchEditor(title: "ENTONCES", actions: $action.children, templates: templates, images: images))
                AnyView(BranchEditor(title: "SI NO", actions: $action.otherwise, templates: templates, images: images))
            } else if action.kind == .REPEAT {
                AnyView(BranchEditor(title: "PASOS QUE SE REPITEN", actions: $action.children, templates: templates, images: images))
            }
        }.padding(8).background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct ConditionFlowSummary: View {
    let action: MacroAction
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("ENTONCES: \(branchSummary(action.children, empty: "sin acciones"))")
            Text("SI NO: \(branchSummary(action.otherwise, empty: "continuar con el siguiente paso"))").foregroundStyle(.secondary)
        }
        .font(.caption).padding(8).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.09), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct CaptureEditorRow: View {
    @Binding var saved: SavedMacroTemplate
    let image: String?
    let isPrincipal: Bool
    let onMakePrincipal: () -> Void
    let onRecrop: () -> Void
    let onDelete: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            TemplateThumbnail(saved: saved, encoded: image, size: CGSize(width: 100, height: 70))
            VStack(alignment: .leading, spacing: 5) {
                TextField("Nombre de la captura", text: $saved.name).textFieldStyle(.roundedBorder)
                Text("\(saved.template.width)×\(saved.template.height) px · pantalla \(saved.template.screenWidth)×\(saved.template.screenHeight)").font(.caption).foregroundStyle(.secondary)
                HStack { Button(isPrincipal ? "Principal" : "Hacer principal", action: onMakePrincipal).buttonStyle(.bordered).disabled(isPrincipal); Button("Recortar más", action: onRecrop).buttonStyle(.bordered); Button(role: .destructive, action: onDelete) { Image(systemName: "trash") }.buttonStyle(.bordered) }
            }
        }
    }
}

private struct TemplateThumbnail: View {
    let saved: SavedMacroTemplate?
    let encoded: String?
    let size: CGSize
    var body: some View {
        Group {
            if let encoded, let data = Data(base64Encoded: encoded), let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFit() }
            else { Image(systemName: "photo").resizable().scaledToFit().padding(18).foregroundStyle(.secondary) }
        }.frame(width: size.width, height: size.height).background(Color.black.opacity(0.18)).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityLabel(saved?.name ?? "Sin imagen")
    }
}

private struct IntegerField: View {
    let title: String
    @Binding var value: Int64
    let minimum: Int64
    let maximum: Int64
    @State private var text: String
    init(title: String, value: Binding<Int64>, minimum: Int64, maximum: Int64) { self.title = title; _value = value; self.minimum = minimum; self.maximum = maximum; _text = State(initialValue: String(value.wrappedValue)) }
    var body: some View {
        TextField(title, text: $text).keyboardType(.numberPad).textFieldStyle(.roundedBorder).onSubmit(commit).onChange(of: value) { text = String($0) }.onChange(of: text) { input in if let parsed = Int64(input), parsed >= minimum, parsed <= maximum { value = parsed } }
    }
    private func commit() { text = String(value) }
}

private struct DecimalField: View {
    let title: String
    @Binding var value: Float
    @State private var text: String
    init(title: String, value: Binding<Float>) { self.title = title; _value = value; _text = State(initialValue: String(format: "%.2f", value.wrappedValue)) }
    var body: some View {
        TextField(title, text: $text).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).onSubmit { if let parsed = Float(text) { value = parsed } }.onChange(of: value) { text = String(format: "%.2f", $0) }.onChange(of: text) { if let parsed = Float($0) { value = parsed } }
    }
}

private struct EmptyState: View {
    let title: String; let detail: String
    var body: some View { VStack(alignment: .leading, spacing: 5) { Text(title).font(.headline); Text(detail).font(.subheadline).foregroundStyle(.secondary) }.padding().frame(maxWidth: .infinity, alignment: .leading).background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 12)) }
}

private struct HelpRow: View {
    let title: String; let detail: String
    var body: some View { VStack(alignment: .leading, spacing: 4) { Text(title).font(.headline); Text(detail).font(.subheadline).foregroundStyle(.secondary) } }
}

private func kindLabel(_ kind: MacroKind) -> String {
    switch kind {
    case .GESTURE: return "Pulsación / trayectoria"
    case .WAIT: return "Esperar"
    case .WAIT_IMAGE: return "Esperar hasta ver una imagen"
    case .REPEAT: return "Repetir pasos"
    case .CLICK_IMAGE: return "Detectar imagen y hacer clic"
    case .IF_IMAGE: return "SI SE VE UNA IMAGEN"
    case .IF_COLOR: return "SI SE VE UN COLOR"
    case .HOME: return "Ir al inicio"
    case .BACK: return "Volver atrás"
    case .RECENTS: return "Aplicaciones recientes"
    case .CLOSE_ACTIVE: return "Cerrar aplicación activa"
    case .NOTIFICATIONS: return "Desplegar notificaciones"
    case .QUICK_SETTINGS: return "Desplegar ajustes rápidos"
    case .OPEN_APP: return "Abrir aplicación"
    }
}

@ViewBuilder private func macroKindMenu(onChoose: @escaping (MacroKind) -> Void) -> some View {
    Section("Gestos e imágenes") {
        Button(kindLabel(.GESTURE)) { onChoose(.GESTURE) }
        Button(kindLabel(.CLICK_IMAGE)) { onChoose(.CLICK_IMAGE) }
    }
    Section("Condiciones") {
        Button(kindLabel(.IF_IMAGE)) { onChoose(.IF_IMAGE) }
        Button(kindLabel(.IF_COLOR)) { onChoose(.IF_COLOR) }
    }
    Section("Control de la secuencia") {
        Button(kindLabel(.WAIT)) { onChoose(.WAIT) }
        Button(kindLabel(.WAIT_IMAGE)) { onChoose(.WAIT_IMAGE) }
        Button(kindLabel(.REPEAT)) { onChoose(.REPEAT) }
    }
    Section("Dispositivo y aplicación") {
        ForEach([MacroKind.OPEN_APP, .BACK, .HOME, .RECENTS, .CLOSE_ACTIVE, .NOTIFICATIONS, .QUICK_SETTINGS], id: \.self) { kind in
            Button(kindLabel(kind)) { onChoose(kind) }
        }
    }
}

private func recursiveActionCount(_ actions: [MacroAction]) -> Int {
    actions.reduce(0) { $0 + 1 + recursiveActionCount($1.children) + recursiveActionCount($1.otherwise) }
}

private func branchSummary(_ actions: [MacroAction], empty: String) -> String {
    let enabled = actions.filter(\.enabled)
    guard !enabled.isEmpty else { return empty }
    let shown = enabled.prefix(3).map(readableActionSummary).joined(separator: " → ")
    return enabled.count > 3 ? "\(shown) +\(enabled.count - 3)" : shown
}

private func readableActionSummary(_ action: MacroAction) -> String {
    switch action.kind {
    case .WAIT: return "esperar \(readableDuration(action.durationMs))"
    case .WAIT_IMAGE: return "esperar hasta ver imagen"
    case .OPEN_APP: return "abrir aplicación"
    case .CLOSE_ACTIVE: return "cerrar aplicación"
    case .GESTURE: return action.points.count <= 1 ? "pulsar" : "hacer trayectoria"
    case .CLICK_IMAGE: return "clic en imagen"
    case .IF_IMAGE: return "comprobar imagen"
    case .IF_COLOR: return "comprobar color"
    case .REPEAT: return "repetir \(action.count) veces"
    case .HOME: return "ir al inicio"
    case .BACK: return "volver"
    case .RECENTS: return "abrir recientes"
    case .NOTIFICATIONS: return "abrir notificaciones"
    case .QUICK_SETTINGS: return "abrir ajustes rápidos"
    }
}

private func readableDuration(_ milliseconds: Int64) -> String {
    if milliseconds % 60_000 == 0 { return "\(milliseconds / 60_000) min" }
    if milliseconds % 1_000 == 0 { return "\(milliseconds / 1_000) s" }
    return "\(milliseconds) ms"
}

private func projectExecutionIssues(_ project: MacroProject) -> [String] {
    var issues: [String] = []
    let packagePattern = try? NSRegularExpression(pattern: "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    func walk(_ actions: [MacroAction]) {
        for action in actions where action.enabled {
            if action.kind == .IF_IMAGE || action.kind == .CLICK_IMAGE || action.kind == .WAIT_IMAGE {
                guard let saved = project.templates.first(where: { $0.id == action.templateId }) else {
                    issues.append("«\(action.name)» necesita una captura")
                    walk(action.children); walk(action.otherwise); continue
                }
                if project.imagesBase64[saved.template.file] == nil { issues.append("Faltan los datos PNG de «\(saved.name)»") }
            }
            if action.kind == .OPEN_APP {
                let range = NSRange(action.packageName.startIndex..<action.packageName.endIndex, in: action.packageName)
                if packagePattern?.firstMatch(in: action.packageName, range: range) == nil { issues.append("«\(action.name)» necesita un paquete Android válido") }
            }
            if action.kind == .CLOSE_ACTIVE && !(500...3_000).contains(action.durationMs) { issues.append("«\(action.name)» debe esperar entre 500 y 3000 ms") }
            if action.kind == .REPEAT && !action.children.contains(where: \.enabled) { issues.append("«\(action.name)» no contiene pasos activos") }
            walk(action.children); walk(action.otherwise)
        }
    }
    if !project.actions.contains(where: \.enabled) { issues.append("Añade al menos una acción activa") } else { walk(project.actions) }
    return Array(NSOrderedSet(array: issues)) as? [String] ?? issues
}

private func defaultAction(_ kind: MacroKind) -> MacroAction {
    switch kind {
    case .WAIT: return MacroAction(kind: kind, name: "Esperar 1 segundo", durationMs: 1000)
    case .WAIT_IMAGE: return MacroAction(kind: kind, name: "Esperar hasta ver el lobby", durationMs: 60_000)
    case .REPEAT: return MacroAction(kind: kind, name: "Repetir pasos", count: 2)
    case .CLICK_IMAGE: return MacroAction(kind: kind, name: "Detectar imagen y hacer clic")
    case .IF_IMAGE: return MacroAction(kind: kind, name: "SI SE VE UNA IMAGEN")
    case .IF_COLOR: return MacroAction(kind: kind, name: "SI SE VE UN COLOR")
    case .OPEN_APP: return MacroAction(kind: kind, name: "Abrir aplicación", packageName: "es.macrostudio")
    case .CLOSE_ACTIVE: return MacroAction(kind: kind, name: "Cerrar aplicación activa", durationMs: 1000)
    default: return MacroAction(kind: kind, name: kindLabel(kind))
    }
}

private func clearTemplateReferences(_ actions: [MacroAction], templateID: String) -> [MacroAction] {
    actions.map { original in
        var action = original; if action.templateId == templateID { action.templateId = "" }
        action.children = clearTemplateReferences(action.children, templateID: templateID); action.otherwise = clearTemplateReferences(action.otherwise, templateID: templateID); return action
    }
}

private func colorHex(_ value: Int32) -> String { String(format: "#%06X", Int(value) & 0x00FFFFFF) }

private func parseColor(_ value: String) -> Int32? {
    let clean = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
    guard clean.count == 6, let number = Int(clean, radix: 16) else { return nil }
    return Int32(bitPattern: UInt32(0xFF000000) | UInt32(number))
}

private func colorFromInt(_ value: Int32) -> Color {
    let number = UInt32(bitPattern: value)
    return Color(red: Double((number >> 16) & 0xFF) / 255, green: Double((number >> 8) & 0xFF) / 255, blue: Double(number & 0xFF) / 255)
}

private func safeFileName(_ value: String) -> String {
    let result = value.replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "-", options: .regularExpression)
    return result.isEmpty ? "proyecto" : result
}

private extension CloudStore {
    var idTokenAvailable: Bool { UserDefaults.standard.string(forKey: "prisonmacro.idToken") != nil }
}
