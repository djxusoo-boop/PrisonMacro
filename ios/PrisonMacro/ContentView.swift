import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum Route: Hashable {
    case editor(String)
    case images(String)
    case settings
}

/// Pantalla principal: «Mis macros», como en Android.
struct ContentView: View {
    @ObservedObject var cloud: CloudStore
    @StateObject private var library = Library()
    @StateObject private var link = ComputerLink()
    @StateObject private var shots = ShotLibrary()
    @State private var path: [Route] = []
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(cloud: cloud, path: $path)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .editor(let id): EditorView(projectID: id, path: $path)
                    case .images(let id): ImagesView(projectID: id)
                    case .settings: SettingsView(cloud: cloud)
                    }
                }
        }
        .environmentObject(library)
        .environmentObject(link)
        .environmentObject(shots)
        .environmentObject(cloud)
        .onChange(of: scenePhase) { phase in if phase != .active { library.saveNow() } }
        // Abrir un archivo .json desde Archivos, WhatsApp, Mail, AirDrop… o el enlace de la nube.
        .onOpenURL { url in
            if url.isFileURL { openFile(url) } else { Task { await cloud.consumeLink(url.absoluteString) } }
        }
        .alert("PrisonMacro", isPresented: Binding(get: { library.notice != nil }, set: { if !$0 { library.notice = nil } })) {
            Button("OK") { library.notice = nil }
        } message: { Text(library.notice ?? "") }
        #if DEBUG
        // Solo en pruebas (simulador): `-importFile <ruta>` importa un archivo y `-open editor|images|settings` abre esa pantalla.
        .onAppear {
            let defaults = UserDefaults.standard
            if let file = defaults.string(forKey: "importFile") { openFile(URL(fileURLWithPath: file)); library.notice = nil }
            if let first = library.projects.first?.id {
                switch defaults.string(forKey: "open") {
                case "editor": path = [.editor(first)]
                case "images": path = [.editor(first), .images(first)]
                case "settings": path = [.settings]
                default: break
                }
            }
        }
        #endif
    }

    private func openFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let id = try library.importData(try Data(contentsOf: url), from: "de un archivo")
            path = [.editor(id)]
        } catch { library.notice = "No se pudo abrir: \(error.localizedDescription)" }
    }
}

private struct HomeView: View {
    @ObservedObject var cloud: CloudStore
    @Binding var path: [Route]
    @EnvironmentObject private var library: Library
    @State private var creating = false
    @State private var namingEmpty = false
    @State private var quick = false
    @State private var newName = ""
    @State private var renamingID: String?
    @State private var deletingID: String?
    @State private var sendingID: String?
    @State private var receiving = false
    /// Modo seleccionar: borrar varias macros de golpe.
    @State private var selecting = false
    @State private var ordering = false
    @State private var marked: Set<String> = []
    @State private var confirmBulk = false

    var body: some View {
        Group {
            if library.projects.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Image("PrisonLogo").resizable().scaledToFit().frame(width: 64, height: 64).clipShape(RoundedRectangle(cornerRadius: 16))
                        Text("Todavía no tienes macros").font(.title3.bold())
                        Text("Crea una aquí o tráete las que ya tienes en Android.").foregroundStyle(.secondary)
                        Button { newName = ""; creating = true } label: { Label("Crear mi primera macro", systemImage: "plus").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent).controlSize(.large)
                        Button { receiving = true } label: { Label("Recibir macros de Android", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity) }
                            .buttonStyle(.bordered).controlSize(.large)
                    }
                    .padding(20)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
                    .padding()
                }
                .background(Color(.systemGroupedBackground))
            } else {
                List {
                Section {
                    ForEach(library.projects) { p in
                      if selecting {
                        Button { if marked.contains(p.id) { marked.remove(p.id) } else { marked.insert(p.id) } } label: {
                            HStack {
                                Image(systemName: marked.contains(p.id) ? "checkmark.circle.fill" : "circle").font(.title2)
                                    .foregroundStyle(marked.contains(p.id) ? Color.red : Color.secondary)
                                MacroRow(project: p)
                            }
                        }.buttonStyle(.plain)
                      } else if ordering {
                        MacroRow(project: p)
                      } else {
                        NavigationLink(value: Route.editor(p.id)) { MacroRow(project: p) }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) { deletingID = p.id } label: { Label("Borrar", systemImage: "trash") }
                                Button { sendingID = p.id } label: { Label("Enviar", systemImage: "paperplane") }.tint(.blue)
                            }
                            .contextMenu {
                                Button { sendingID = p.id } label: { Label("Enviar a Android / compartir", systemImage: "paperplane") }
                                Button { newName = p.name; renamingID = p.id } label: { Label("Cambiar nombre", systemImage: "pencil") }
                                Button { library.duplicate(p.id) } label: { Label("Duplicar", systemImage: "plus.square.on.square") }
                                if let i = library.projects.firstIndex(where: { $0.id == p.id }), library.projects.count > 1 {
                                    Divider()
                                    if i > 0 {
                                        Button { library.projects.move(fromOffsets: [i], toOffset: 0) } label: { Label("Arriba del todo", systemImage: "arrow.up.to.line") }
                                        Button { library.projects.move(fromOffsets: [i], toOffset: i - 1) } label: { Label("Subir", systemImage: "arrow.up") }
                                    }
                                    if i < library.projects.count - 1 {
                                        Button { library.projects.move(fromOffsets: [i], toOffset: i + 2) } label: { Label("Bajar", systemImage: "arrow.down") }
                                        Button { library.projects.move(fromOffsets: [i], toOffset: library.projects.count) } label: { Label("Abajo del todo", systemImage: "arrow.down.to.line") }
                                    }
                                }
                                Divider()
                                Button(role: .destructive) { deletingID = p.id } label: { Label("Borrar", systemImage: "trash") }
                            }
                      }
                    }
                    .onMove { library.projects.move(fromOffsets: $0, toOffset: $1) }
                } footer: {
                    Text(ordering ? "Arrastra cada macro por ≡ para cambiarla de sitio. Pulsa «Listo» al terminar."
                         : "Toca una macro para editarla. Mantén pulsado o desliza para enviarla, duplicarla, moverla o borrarla.")
                }
                }
                .environment(\.editMode, .constant(ordering ? .active : .inactive))
            }
        }
        .navigationTitle("Mis macros")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { path.append(.settings) } label: { Image(systemName: "gearshape") }.accessibilityLabel("Ajustes")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if library.projects.count > 1 && !selecting { Button(ordering ? "Listo" : "Ordenar") { ordering.toggle() } }
                if library.projects.count > 1 && !ordering { Button(selecting ? "Listo" : "Seleccionar") { selecting.toggle(); marked = [] } }
                Button { receiving = true } label: { Label("Recibir", systemImage: "square.and.arrow.down") }
                Button { newName = ""; creating = true } label: { Image(systemName: "plus") }.accessibilityLabel("Nueva macro")
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting {
                HStack(spacing: 10) {
                    Button(marked.count == library.projects.count ? "☐ Ninguna" : "☑ Todas") { marked = marked.count == library.projects.count ? [] : Set(library.projects.map(\.id)) }.buttonStyle(.bordered)
                    Button("🗑 Borrar (\(marked.count))", role: .destructive) { confirmBulk = true }.buttonStyle(.borderedProminent).tint(.red).disabled(marked.isEmpty)
                }
                .padding().frame(maxWidth: .infinity).background(.bar)
            } else if !library.projects.isEmpty {
                HStack(spacing: 10) {
                    Button { newName = ""; creating = true } label: { Label("Nueva macro", systemImage: "plus").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6) }
                        .buttonStyle(.borderedProminent)
                    Button { receiving = true } label: { Label("Recibir", systemImage: "square.and.arrow.down").font(.headline).padding(.vertical, 6) }
                        .buttonStyle(.bordered)
                }
                .padding(.horizontal).padding(.vertical, 8).background(.bar)
            }
        }
        // «Nueva macro»: rápida (4 preguntas) o vacía (paso a paso), como en Android.
        .confirmationDialog("Nueva macro", isPresented: $creating, titleVisibility: .visible) {
            Button("⚡ Rápida: te pregunto 4 cosas y la hago yo") { quick = true }
            Button("📝 Vacía: la hago paso a paso") {
                // Sin preguntar el nombre: se cambia tocándolo arriba en el editor (como en Android).
                let p = library.create(named: "Macro \(library.projects.count + 1)")
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { path.append(.editor(p.id)) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("La rápida abre el juego, juega un rato, lo cierra y repite.") }
        .sheet(isPresented: $quick) { QuickMacroSheet { id in path.append(.editor(id)) }.environmentObject(library) }
        .alert("Nueva macro", isPresented: $namingEmpty) {
            TextField("Nombre (por ejemplo «Zooba»)", text: $newName)
            Button("Crear") { let p = library.create(named: newName); path.append(.editor(p.id)) }
            Button("Cancelar", role: .cancel) {}
        }
        .alert("Cambiar nombre", isPresented: Binding(get: { renamingID != nil }, set: { if !$0 { renamingID = nil } })) {
            TextField("Nombre", text: $newName)
            Button("Guardar") { if let id = renamingID { library.rename(id, to: newName) }; renamingID = nil }
            Button("Cancelar", role: .cancel) { renamingID = nil }
        }
        .confirmationDialog("¿Borrar \(marked.count) macro(s)?", isPresented: $confirmBulk, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { for id in marked { library.delete(id) }; marked = []; selecting = false }
        } message: { Text("Se borran del iPhone con sus pasos y recortes. Tus capturas enteras no se tocan. Lo que ya enviaste a Android no cambia.") }
        .confirmationDialog("¿Borrar «\(deletingID.flatMap { library.project($0)?.name } ?? "")»?", isPresented: Binding(get: { deletingID != nil }, set: { if !$0 { deletingID = nil } }), titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { if let id = deletingID { library.delete(id) }; deletingID = nil }
        } message: { Text("Se borrará del iPhone con sus capturas. Lo que ya enviaste a Android no cambia.") }
        .sheet(item: Binding(get: { sendingID.map(IDBox.init) }, set: { sendingID = $0?.id })) { box in
            SendSheet(projectID: box.id).environmentObject(library)
        }
        .sheet(isPresented: $receiving) {
            ReceiveSheet { id in receiving = false; path = [.editor(id)] }.environmentObject(library)
        }
    }
}

private struct IDBox: Identifiable { let id: String }

private struct MacroRow: View {
    let project: MacroProject
    var body: some View {
        let issues = projectExecutionIssues(project)
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(Color.accentColor.opacity(0.15)).frame(width: 46, height: 46)
                Text(project.actions.first.map { kindEmoji($0.kind) } ?? "📝").font(.title2)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(project.name).font(.headline).lineLimit(1)
                Text("\(plural(recursiveActionCount(project.actions), "paso", "pasos")) · \(plural(project.templates.count, "captura", "capturas")) · \(project.repeatCount == 0 ? "sin fin" : project.repeatCount == 1 ? "1 vez" : "\(project.repeatCount) veces")")
                    .font(.caption).foregroundStyle(.secondary)
                if let first = issues.first { Text("⚠ \(first)").font(.caption2).foregroundStyle(.orange).lineLimit(1) }
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Enviar

/// Enviar una macro: al ordenador (Android la recoge), compartir (AirDrop, WhatsApp, Archivos…) o a la nube.
struct SendSheet: View {
    let projectID: String
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var link: ComputerLink
    @EnvironmentObject private var cloud: CloudStore
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var result: String?
    @State private var failed = false
    @State private var shareURL: URL?

    var body: some View {
        let name = library.project(projectID)?.name ?? "macro"
        NavigationStack {
            List {
                if let result {
                    Section {
                        Label(result, systemImage: failed ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                            .foregroundStyle(failed ? .red : .green)
                    }
                }
                Section {
                    BigOption(emoji: "💻", title: "Al ordenador (wifi)", detail: "Luego, en BlueStacks o el móvil Android: PrisonMacro › ⚙ Ajustes › 📥 Recibir macros.", busy: busy) { Task { await sendToComputer() } }
                    BigOption(emoji: "📤", title: "Compartir…", detail: "AirDrop, WhatsApp, Telegram, Correo, Guardar en Archivos o Drive.") { shareURL = library.exportFile(projectID) }
                    if cloud.isSignedIn {
                        BigOption(emoji: "☁️", title: "A la nube", detail: "En Android: ☁ Nube › Descargar.") { Task { busy = true; await cloud.upload(library.project(projectID)!); busy = false; result = cloud.status; failed = !cloud.status.contains("subido") } }
                    }
                } footer: { Text("Se envía con todas sus capturas.") }
            }
            .navigationTitle("Enviar «\(name)»")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { dismiss() } } }
            .sheet(item: Binding(get: { shareURL.map(ShareItem.init) }, set: { shareURL = $0?.url })) { item in ShareSheet(items: [item.url]) }
        }
        .presentationDetents([.medium, .large])
    }

    private func sendToComputer() async {
        guard let data = library.exportData(projectID) else { return }
        busy = true; result = "Buscando el ordenador…"; failed = false
        do {
            _ = try await link.send(data)
            result = "Enviada al ordenador ✔︎\nAhora en Android: ⚙ Ajustes › 📥 Recibir macros."
        } catch { result = error.localizedDescription; failed = true }
        busy = false
    }
}

// MARK: - Recibir

struct ReceiveSheet: View {
    let onReceived: (String) -> Void
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var link: ComputerLink
    @EnvironmentObject private var cloud: CloudStore
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var result: String?
    @State private var picking = false

    var body: some View {
        NavigationStack {
            List {
                if let result { Section { Label(result, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red) } }
                Section {
                    BigOption(emoji: "💻", title: "Del ordenador (wifi)", detail: "Primero, en Android: ⚙ Ajustes › 📤 Enviar al ordenador. Trae lo último que se envió.", busy: busy) { Task { await fromComputer() } }
                    BigOption(emoji: "📁", title: "De un archivo", detail: "Un .json que te pasaste por AirDrop, WhatsApp, Correo, Drive… (también puedes tocar el archivo y elegir «PrisonMacro»).") { picking = true }
                } footer: { Text("Si ya tienes esa macro, se actualiza en vez de duplicarse.") }
                if cloud.isSignedIn {
                    Section("De la nube") {
                        if cloud.projects.isEmpty { Text("No hay macros en la nube.").foregroundStyle(.secondary) }
                        ForEach(cloud.projects) { item in
                            Button {
                                Task {
                                    if let p = await cloud.download(item), let data = try? MacroJSON.encoder.encode(p), let id = try? library.importData(data, from: "de la nube") { onReceived(id) }
                                }
                            } label: {
                                HStack {
                                    Text("☁️"); Text(item.name).foregroundStyle(.primary); Spacer()
                                    Text(library.project(item.id) != nil ? "⟳ Actualizar" : "⬇ Descargar").font(.caption.bold())
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Recibir macros")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { dismiss() } } }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.json, .data, .plainText]) { picked in
                guard case .success(let url) = picked else { return }
                let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do { onReceived(try library.importData(try Data(contentsOf: url), from: "de un archivo")) }
                catch { result = error.localizedDescription }
            }
            .task { if cloud.isSignedIn { await cloud.refresh() } }
        }
        .presentationDetents([.medium, .large])
    }

    private func fromComputer() async {
        busy = true; result = nil
        do { onReceived(try library.importData(try await link.receive(), from: "del ordenador")) }
        catch { result = error.localizedDescription }
        busy = false
    }
}

private struct BigOption: View {
    let emoji: String
    let title: String
    let detail: String
    var busy = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Text(emoji).font(.largeTitle)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    Text(detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                if busy { ProgressView() }
            }
            .padding(.vertical, 6)
        }
        .disabled(busy)
    }
}

private struct ShareItem: Identifiable { let url: URL; var id: URL { url } }

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - Ajustes

struct SettingsView: View {
    @ObservedObject var cloud: CloudStore
    @EnvironmentObject private var link: ComputerLink
    @State private var searching = false
    @State private var searchResult: String?
    @State private var password = ""
    @State private var working = false
    @State private var cloudError: String?

    var body: some View {
        Form {
            Section {
                LabeledField(title: "Dirección del ordenador (déjala vacía para buscarlo solo)") {
                    TextField("Automática", text: $link.address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                }
                Button {
                    Task {
                        searching = true; searchResult = nil
                        if let found = await link.discover() { link.address = found; searchResult = "Encontrado: \(link.foundName ?? found)" }
                        else { searchResult = LinkError.notFound.localizedDescription }
                        searching = false
                    }
                } label: { HStack { Label("Buscar el ordenador en la wifi", systemImage: "magnifyingglass"); if searching { Spacer(); ProgressView() } } }
                .disabled(searching)
                if let searchResult { Text(searchResult).font(.footnote).foregroundStyle(.secondary) }
            } header: { Text("💻 Ordenador (para pasar macros por wifi)") } footer: {
                Text("En el Mac tiene que estar abierto el buzón (doble clic en «Abrir buzón PrisonMacro», en el Escritorio; se cierra solo a los 15 minutos sin uso), y el iPhone en la misma wifi. La primera vez, el iPhone te preguntará si PrisonMacro puede buscar dispositivos en la red: di que sí.")
            }

            Section {
                if cloud.isSignedIn {
                    Text(cloud.email.isEmpty ? "Cuenta conectada" : cloud.email)
                    Button("Cerrar sesión", role: .destructive) { cloud.signOut() }
                } else {
                    TextField("Correo", text: $cloud.email).textInputAutocapitalization(.never).keyboardType(.emailAddress).autocorrectionDisabled()
                    SecureField("Contraseña (mínimo 6)", text: $password)
                    HStack {
                        Button("🔓 Entrar") { Task { working = true; cloudError = await cloud.passwordAuth(email: cloud.email, password: password, create: false); working = false } }
                            .buttonStyle(.borderedProminent)
                        Button("✨ Crear cuenta") { Task { working = true; cloudError = await cloud.passwordAuth(email: cloud.email, password: password, create: true); working = false } }
                            .buttonStyle(.borderedProminent).tint(.orange)
                        if working { ProgressView() }
                    }
                    .disabled(working)
                    Text("¿Primera vez? Escribe tu correo, inventa una contraseña y pulsa «✨ Crear cuenta». En los demás aparatos, el mismo correo y contraseña con «🔓 Entrar».")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("¿Olvidaste la contraseña?") {
                        Task { cloudError = await cloud.resetPassword(email: cloud.email) ?? "Te hemos enviado un correo para poner una contraseña nueva (mira también en Spam). Ábrelo, pon la contraseña y vuelve aquí a «Entrar»." }
                    }
                    .font(.footnote)
                    if let cloudError { Text(cloudError).font(.footnote).foregroundStyle(.orange) }
                }
                if cloud.isSignedIn { Text(cloud.status).font(.footnote).foregroundStyle(.secondary) }
            } header: { Text("☁️ Tu cuenta (Nube)") } footer: {
                Text("Crea la cuenta una vez y entra con el mismo correo y contraseña en todos tus aparatos. Lo que subes en uno lo descargas en otro, por internet.")
            }

            Section("❓ Cómo se usa") {
                HelpRow(title: "El iPhone prepara, Android juega", detail: "Aquí creas y cambias las macros cómodamente. Las ejecuta PrisonMacro en BlueStacks o en el móvil Android.")
                HelpRow(title: "De Android al iPhone", detail: "En Android: ⚙ Ajustes › 📤 Enviar al ordenador. En el iPhone: Recibir › 💻 Del ordenador.")
                HelpRow(title: "Del iPhone a Android", detail: "En el iPhone: abre la macro › Enviar › 💻 Al ordenador. En Android: ⚙ Ajustes › 📥 Recibir macros.")
                HelpRow(title: "Capturas", detail: "Haz una captura en BlueStacks o en el móvil Android, pásala al iPhone y, en el paso, pulsa «＋ Nueva captura» y recorta el botón.")
                HelpRow(title: "Puntos y colores", detail: "En los pasos de tocar o de color, «Marcar tocando una captura» te deja señalarlo con el dedo sobre una captura de la pantalla entera.")
            }
            Section {
                Text("PrisonMacro \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · no ejecutes macros sobre compras, mensajes o cuentas.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Ajustes")
    }
}

private struct HelpRow: View {
    let title: String; let detail: String
    var body: some View { VStack(alignment: .leading, spacing: 4) { Text(title).font(.headline); Text(detail).font(.subheadline).foregroundStyle(.secondary) } }
}

extension CloudStore {
    var isSignedIn: Bool { UserDefaults.standard.string(forKey: "prisonmacro.idToken") != nil }
}


/// «⚡ Macro rápida»: 4 preguntas y la macro de siempre queda hecha (las imágenes, después).
struct QuickMacroSheet: View {
    let onCreated: (String) -> Void
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @State private var game = "Zooba"
    @State private var byIcon = true
    @State private var playMs: Int64 = 15_000
    @State private var everyMs: Int64 = 240_000
    @State private var popups = true

    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Abre el juego, juega un rato, lo cierra y vuelve a empezar, sin fin. Las imágenes las eliges después.").font(.callout).foregroundStyle(.secondary) }
                Section("1. ¿Qué juego?") { TextField("Nombre del juego", text: $game) }
                Section("2. ¿Cómo se abre?") {
                    Picker("Cómo se abre", selection: $byIcon) { Text("🎯 Tocando su icono").tag(true); Text("📱 Abriendo la app").tag(false) }.pickerStyle(.segmented)
                }
                Section("3. ¿Cuánto juega antes de cerrarlo?") {
                    SecondsField(title: "Tiempo (segundos, o «2 min»)", value: $playMs, minimum: 1_000, maximum: 600_000)
                    QuickChoices(values: [15_000, 30_000, 60_000, 300_000], selected: playMs) { playMs = $0 }
                }
                Section("4. ¿Cuánto espera con el juego cerrado?") {
                    SecondsField(title: "Tiempo (segundos, o «4 min»)", value: $everyMs, minimum: 0, maximum: 4_200_000)
                    QuickChoices(values: [60_000, 240_000, 300_000, 600_000], selected: everyMs) { everyMs = $0 }
                }
                Section { Toggle("Salen ventanas o anuncios que hay que cerrar (la X)", isOn: $popups) }
            }
            .navigationTitle("⚡ Macro rápida")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crear") {
                        let p = library.create(named: game)
                        library.binding(p.id).wrappedValue = quickMacro(p, game: game, byIcon: byIcon, playMs: playMs, everyMs: everyMs, popups: popups)
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { onCreated(p.id) }
                    }.bold().disabled(game.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
