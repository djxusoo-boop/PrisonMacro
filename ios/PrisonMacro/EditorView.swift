import SwiftUI
import UIKit

private struct StepRef: Identifiable { let id: String; var fresh = false }

/// Dónde se añade el próximo paso.
struct AddTarget: Identifiable {
    let id = UUID()
    var parentID: String? = nil
    var otherwise = false
    var nextTo: String? = nil
    var after = true
}

/// Una fila de la lista: un paso, el título de un bloque («✔ Si se ve…») o «＋ Añadir paso aquí».
private enum TreeRow: Identifiable {
    case step(MacroAction, number: String, depth: Int)
    case header(title: String, empty: String?, depth: Int, key: String, negative: Bool)
    case add(parentID: String, otherwise: Bool, depth: Int)
    case folded(parentID: String, count: Int, depth: Int)

    var id: String {
        switch self {
        case .step(let a, _, _): return "s-" + a.id
        case .header(_, _, _, let key, _): return "h-" + key
        case .add(let parent, let otherwise, _): return "a-\(parent)-\(otherwise)"
        case .folded(let parent, _, _): return "f-" + parent
        }
    }
}

private func treeRows(_ list: [MacroAction], project: MacroProject, collapsed: Set<String>, prefix: String = "", depth: Int = 0) -> [TreeRow] {
    var rows: [TreeRow] = []
    for (i, a) in list.enumerated() {
        let number = "\(prefix)\(i + 1)"
        rows.append(.step(a, number: number, depth: depth))
        // «⚡» que solo toca su imagen (o aún vacía): una línea, sin bloque.
        if a.kind == .WHEN_IMAGE && (isSimpleRule(a) || (a.children.isEmpty && a.otherwise.isEmpty)) { continue }
        if collapsed.contains(a.id) && [.REPEAT, .IF_IMAGE, .IF_COLOR, .WHEN_IMAGE].contains(a.kind) {
            rows.append(.folded(parentID: a.id, count: recursiveActionCount(a.children) + recursiveActionCount(a.otherwise), depth: depth + 1))
            continue
        }
        switch a.kind {
        case .REPEAT:
            let image = project.templates.first { $0.id == a.templateId }
            rows.append(.header(title: image.map { "🔁 Se repite hasta ver «\($0.name)» (máx. \(a.count)):" } ?? "🔁 Se repite \(a.count) veces:", empty: a.children.isEmpty ? "Añade los pasos que quieres repetir" : nil, depth: depth + 1, key: a.id + "-c", negative: false))
            rows += treeRows(a.children, project: project, collapsed: collapsed, prefix: number + ".", depth: depth + 1)
            rows.append(.add(parentID: a.id, otherwise: false, depth: depth + 1))
        case .WHEN_IMAGE:
            let image = project.templates.first { $0.id == a.templateId }
            rows.append(.header(title: image.map { "⚡ Al ver «\($0.name)» hace esto (y después \(afterRuleLabel(a.afterRule))):" } ?? "⚡ Cuando se vea la imagen, hace esto:", empty: a.children.isEmpty ? "Añade lo que debe hacer cuando aparezca" : nil, depth: depth + 1, key: a.id + "-c", negative: false))
            rows += treeRows(a.children, project: project, collapsed: collapsed, prefix: number + ".", depth: depth + 1)
            rows.append(.add(parentID: a.id, otherwise: false, depth: depth + 1))
        case .IF_IMAGE, .IF_COLOR:
            let what = a.kind == .IF_IMAGE ? "la imagen" : "el color"
            rows.append(.header(title: "✔ Si se ve \(what):", empty: a.children.isEmpty ? "Sin pasos: no hace nada" : nil, depth: depth + 1, key: a.id + "-c", negative: false))
            rows += treeRows(a.children, project: project, collapsed: collapsed, prefix: number + ".", depth: depth + 1)
            rows.append(.add(parentID: a.id, otherwise: false, depth: depth + 1))
            rows.append(.header(title: "✖ Si NO se ve:", empty: a.otherwise.isEmpty ? "Sin pasos: sigue con el siguiente" : nil, depth: depth + 1, key: a.id + "-o", negative: true))
            rows += treeRows(a.otherwise, project: project, collapsed: collapsed, prefix: number + ".n", depth: depth + 1)
            rows.append(.add(parentID: a.id, otherwise: true, depth: depth + 1))
        default: break
        }
    }
    return rows
}

/// Una macro: su lista de pasos, como en Android. Toca un paso para editarlo.
struct EditorView: View {
    let projectID: String
    @Binding var path: [Route]
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var shots: ShotLibrary
    @EnvironmentObject private var cloud: CloudStore
    @State private var editing: StepRef?
    @State private var pendingEdit: StepRef?
    @State private var addTarget: AddTarget?
    @State private var showSettings = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var sending = false
    @State private var deleting = false
    @State private var mergeResult: String?
    @State private var sorting = false
    /// Modo seleccionar: casillas en cada paso para borrar varios de golpe.
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var confirmDelete = false

    private var project: Binding<MacroProject> { library.binding(projectID) }

    var body: some View {
        let p = project.wrappedValue
        let issues = projectExecutionIssues(p)
        let waits = countWaits(p.actions)
        List {
            if !issues.isEmpty && !p.actions.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        // Los pasos que solo necesitan su imagen ya salen como botones: no se repiten aquí.
                        let finish = stepsToFinish(p)
                        let shown = issues.filter { line in !finish.contains { line.hasPrefix("«\($0.name)»") } }
                        Label(shown.isEmpty ? "Para terminar, elige lo que falta" : "Falta completar antes de usarla en Android", systemImage: shown.isEmpty ? "photo.on.rectangle" : "exclamationmark.triangle.fill").font(.subheadline.bold()).foregroundStyle(shown.isEmpty ? Color.accentColor : .red)
                        ForEach(shown.prefix(4), id: \.self) { Text("• \($0)").font(.footnote) }
                        if shown.count > 4 { Text("• y \(shown.count - 4) más").font(.footnote) }
                        // Lo que falta, con un botón directo a cada paso (se abre ya la elección de imagen).
                        ForEach(stepsToFinish(p), id: \.id) { a in
                            Button(a.kind == .OPEN_APP ? "📱 Elegir el juego de «\(a.name)»" : "🖼 Imagen para «\(a.name)»") { editing = StepRef(id: a.id) }
                                .buttonStyle(.borderedProminent).lineLimit(1)
                        }
                        if fixEmptyRules(p).count > 0 {
                            Button("🎯 Arreglarlo: que toquen su imagen") { project.wrappedValue.actions = fixEmptyRules(project.wrappedValue).actions }
                                .buttonStyle(.borderedProminent).padding(.top, 4)
                        }
                    }
                }
            }
            Section {
                Button { showSettings = true } label: { StartCard(project: p) }.tint(.primary)
            }
            if let clip = library.clipboard {
                Section {
                    HStack {
                        Text("\(library.clipboardCut ? "✂ Cortado" : "📋 Copiado"): «\(clip.name)»").lineLimit(1)
                        Spacer()
                        Button("📌 Pegar al final") { paste(nil) }.buttonStyle(.borderless)
                        Button { library.clipboard = nil } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.borderless).foregroundStyle(.secondary)
                    }
                }
            }
            if selecting {
                let all = allActionIds(p.actions)
                let chosen = selected.intersection(all)
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("☑ \(chosen.count) de \(all.count) paso(s) seleccionado(s). Toca los pasos para marcarlos.").font(.footnote)
                        HStack {
                            Button("☑ Todos") { selected = all }.buttonStyle(.bordered)
                            Button("☐ Ninguno") { selected = [] }.buttonStyle(.bordered).disabled(chosen.isEmpty)
                            Button("🗑 Borrar (\(chosen.count))", role: .destructive) { confirmDelete = true }.buttonStyle(.borderedProminent).tint(.red).disabled(chosen.isEmpty)
                        }
                        Button("✓ Listo") { selecting = false; selected = [] }
                    }
                    .confirmationDialog(chosen.count == all.count ? "¿Borrar todos los pasos?" : "¿Borrar \(chosen.count) paso(s)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                        Button("Borrar", role: .destructive) { change { removeActions($0, ids: chosen) }; selected = []; selecting = false }
                    } message: { Text("Las imágenes no se borran. Si te equivocas, pulsa ↶ Deshacer.") }
                }
            }
            if sorting {
                Section {
                    HStack {
                        Text("↕ Modo ordenar: arrastra el paso por ≡, o usa «▲ Subir» y «▼ Bajar». «⤴ Sacar» lo saca de su bloque. Para meterlo en un bloque: mantén pulsado › ✂ Cortar y luego 📌 Pegar aquí.")
                            .font(.footnote)
                        Button("✓ Listo") { sorting = false }.buttonStyle(.borderedProminent)
                    }
                }
            }
            Section {
                if p.actions.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Esta macro está vacía").font(.headline)
                        Text("Pulsa «＋ Añadir paso»: tocar una imagen, esperar, abrir el juego…").font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.vertical, 6)
                }
                let rows = treeRows(p.actions, project: p, collapsed: library.collapsed)
                ForEach(rows) { row in
                    Group {
                    switch row {
                    case .step(let action, let number, let depth):
                        stepCell(action, number: number, depth: depth, project: p)
                    case .header(let title, let empty, let depth, _, let negative):
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).font(.subheadline.weight(.bold)).foregroundStyle(negative ? Color.red : Color.accentColor)
                            if let empty { Text(empty).font(.caption).foregroundStyle(.secondary) }
                        }
                        .padding(.leading, indent(depth))
                        .overlay(alignment: .leading) { DepthBars(depth: depth) }
                    case .add(let parent, let otherwise, let depth):
                        // «Si se ve la imagen» no toca nada por sí solo: este botón añade el paso que toca la imagen encontrada.
                        if !otherwise, let block = findAction(p.actions, parent), [.IF_IMAGE, .WHEN_IMAGE].contains(block.kind), let image = p.templates.first(where: { $0.id == block.templateId }) {
                            Button {
                                var tap = defaultAction(.CLICK_IMAGE)
                                tap.name = "Tocar «\(image.name)»"; tap.templateId = image.id; tap.waitMs = 2_000; tap.skipIfMissing = true
                                change { addInside($0, tap, parent: parent, otherwise: false) }
                            } label: { Label("🎯 Tocar esta imagen (en su centro)", systemImage: "scope").font(.subheadline.weight(.semibold)) }
                            .buttonStyle(.borderless)
                            .padding(.leading, indent(depth))
                            .overlay(alignment: .leading) { DepthBars(depth: depth) }
                        }
                        HStack(spacing: 16) {
                            Button { addTarget = AddTarget(parentID: parent, otherwise: otherwise) } label: {
                                Label("Añadir paso aquí", systemImage: "plus.circle").font(.subheadline)
                            }.buttonStyle(.borderless)
                            if library.clipboard != nil {
                                Button("📌 Pegar aquí") { paste(AddTarget(parentID: parent, otherwise: otherwise)) }.font(.subheadline).buttonStyle(.borderless)
                            }
                        }
                        .padding(.leading, indent(depth))
                        .overlay(alignment: .leading) { DepthBars(depth: depth) }
                    case .folded(let parent, let count, let depth):
                        Button { library.collapsed.remove(parent) } label: {
                            Text("▶ \(plural(count, "paso", "pasos")) dentro (plegado) · toca para verlos").font(.caption).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .padding(.leading, indent(depth))
                        .overlay(alignment: .leading) { DepthBars(depth: depth) }
                    }
                    }
                    // Solo los pasos se arrastran (no los títulos ni los botones de los bloques).
                    .moveDisabled({ if case .step = row { return false }; return true }())
                }
                .onMove { source, destination in
                    // Al soltarlo, se queda en su mismo bloque: cuenta cuántos de sus hermanos quedan por encima.
                    guard let from = source.first, case .step(let moving, _, _) = rows[from],
                          let siblings = siblingsOf(project.wrappedValue.actions, moving.id) else { return }
                    let above = siblings.filter { s in
                        s.id != moving.id && (rows.firstIndex { if case .step(let a, _, _) = $0 { return a.id == s.id }; return false } ?? Int.max) < destination
                    }.count
                    change { moveActionTo($0, moving.id, index: above) }
                }
            } header: {
                Text("Pasos · \(recursiveActionCount(p.actions))")
            } footer: {
                if !p.actions.isEmpty { Text("Toca un paso para cambiarlo. Mantén pulsado para más opciones.") }
            }
        }
        // En «Ordenar pasos» salen las asas ≡ para arrastrar.
        .environment(\.editMode, .constant(sorting ? .active : .inactive))
        .navigationTitle(p.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button { newName = p.name; renaming = true } label: {
                    VStack(spacing: 0) {
                        Text(p.name).font(.headline).lineLimit(1).foregroundStyle(.primary)
                        Text("toca para cambiar el nombre").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { library.undo(projectID) } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(!library.canUndo(projectID)).accessibilityLabel("Deshacer")
                Menu {
                    Button { library.redo(projectID) } label: { Label("Rehacer", systemImage: "arrow.uturn.forward") }.disabled(!library.canRedo(projectID))
                    Button { sorting = false; selecting = true; selected = [] } label: { Label("Seleccionar pasos (borrar varios)", systemImage: "checkmark.circle") }.disabled(p.actions.isEmpty)
                    Button { sorting.toggle() } label: { Label(sorting ? "Terminar de ordenar" : "Ordenar pasos", systemImage: "arrow.up.arrow.down") }
                    if waits >= 2 { Button { mergeWaitSteps() } label: { Label("Juntar las \(waits) esperas", systemImage: "wand.and.stars") } }
                    let blocks = containerIds(p.actions)
                    if !blocks.isEmpty {
                        Button { library.collapsed.formUnion(blocks) } label: { Label("Plegar todos los bloques", systemImage: "chevron.right.2") }
                        Button { library.collapsed.subtract(blocks) } label: { Label("Desplegar todos los bloques", systemImage: "chevron.down") }
                    }
                    Button { path.append(.images(projectID)) } label: { Label("Imágenes (\(p.templates.count))", systemImage: "photo.on.rectangle") }
                    Button { showSettings = true } label: { Label("Ajustes de la macro", systemImage: "gearshape") }
                    Button { sending = true } label: { Label("Enviar a Android / compartir", systemImage: "paperplane") }
                    Button { if let copy = library.duplicate(projectID) { path = [.editor(copy.id)] } } label: { Label("Duplicar macro", systemImage: "plus.square.on.square") }
                    Divider()
                    Button(role: .destructive) { deleting = true } label: { Label("Borrar macro", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 8) {
                Button { addTarget = AddTarget() } label: { Label("Añadir paso", systemImage: "plus").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6) }
                    .buttonStyle(.borderedProminent)
                Button { path.append(.images(projectID)) } label: { Label("\(p.templates.count)", systemImage: "photo.on.rectangle").font(.headline).padding(.vertical, 6) }
                    .buttonStyle(.bordered).accessibilityLabel("Imágenes")
                Button { sending = true } label: { Image(systemName: "paperplane.fill").font(.headline).padding(.vertical, 6) }
                    .buttonStyle(.bordered).accessibilityLabel("Enviar")
            }
            .padding(.horizontal).padding(.vertical, 8)
            .background(.bar)
        }
        .sheet(item: $editing) { ref in
            StepSheet(projectID: projectID, stepID: ref.id, fresh: ref.fresh) { target in addAfterClose(target) }.environmentObject(library).environmentObject(shots).environmentObject(cloud)
        }
        .sheet(item: $addTarget, onDismiss: { if let next = pendingEdit { pendingEdit = nil; editing = next } }) { target in
            AddStepSheet { kind in insert(kind, target) }
        }
        .sheet(isPresented: $showSettings) { MacroSettingsSheet(projectID: projectID).environmentObject(library).environmentObject(shots).environmentObject(cloud) }
        .sheet(isPresented: $sending) { SendSheet(projectID: projectID).environmentObject(library) }
        .alert("Nombre de la macro", isPresented: $renaming) {
            TextField("Nombre", text: $newName)
            Button("Guardar") { library.rename(projectID, to: newName) }
            Button("Cancelar", role: .cancel) {}
        }
        .alert("Esperas juntadas", isPresented: Binding(get: { mergeResult != nil }, set: { if !$0 { mergeResult = nil } })) {
            Button("Deshacer") { library.undo(projectID) }
            Button("OK", role: .cancel) {}
        } message: { Text(mergeResult ?? "") }
        .confirmationDialog("¿Borrar «\(p.name)»?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { path.removeAll(); library.delete(projectID) }
        } message: { Text("Se borrará del iPhone con sus imágenes. Lo que ya enviaste a Android no cambia.") }
    }

    @ViewBuilder private func stepMenu(_ action: MacroAction) -> some View {
        let pos = siblingPosition(project.wrappedValue.actions, action.id)
        Button { editing = StepRef(id: action.id) } label: { Label("Editar", systemImage: "pencil") }
        Button { change { moveAction($0, action.id, by: -1) } } label: { Label("Subir", systemImage: "arrow.up") }.disabled(pos?.first ?? true)
        Button { change { moveAction($0, action.id, by: 1) } } label: { Label("Bajar", systemImage: "arrow.down") }.disabled(pos?.last ?? true)
        Button { addTarget = AddTarget(nextTo: action.id, after: false) } label: { Label("Añadir paso antes", systemImage: "text.insert") }
        Button { addTarget = AddTarget(nextTo: action.id, after: true) } label: { Label("Añadir paso después", systemImage: "text.append") }
        if isSimpleRule(action) { Button { addTarget = AddTarget(parentID: action.id) } label: { Label("Hacer más cosas cuando salga", systemImage: "plus.square") } }
        if let current = orderOf(action) {
            Menu {
                ForEach(possibleOrders(action)) { o in
                    Button((o == current ? "✓ " : "") + o.label) {
                        let image = project.wrappedValue.templates.first { $0.id == action.templateId }?.name ?? ""
                        change { updateAction($0, action.id) { changeOrder($0, to: o, image: image, timeoutMs: project.wrappedValue.timeoutMs) } }
                    }.disabled(o == current)
                }
            } label: { Label("Cambiar lo que hace", systemImage: "arrow.triangle.2.circlepath") }
        }
        Button { change { duplicateAction($0, action.id) } } label: { Label("Duplicar", systemImage: "plus.square.on.square") }
        Divider()
        Button { library.clipboard = action; library.clipboardCut = false } label: { Label("Copiar", systemImage: "doc.on.doc") }
        Button { library.clipboard = action; library.clipboardCut = true; change { updateAction($0, action.id) { _ in nil } } } label: { Label("Cortar", systemImage: "scissors") }
        if let clip = library.clipboard {
            Button { paste(AddTarget(nextTo: action.id, after: false)) } label: { Label("Pegar «\(clip.name.prefix(24))» antes", systemImage: "doc.on.clipboard") }
            Button { paste(AddTarget(nextTo: action.id, after: true)) } label: { Label("Pegar «\(clip.name.prefix(24))» después", systemImage: "doc.on.clipboard") }
            if action.kind == .REPEAT || action.kind == .WHEN_IMAGE { Button { paste(AddTarget(parentID: action.id)) } label: { Label("Pegar dentro del bloque", systemImage: "arrow.down.right.square") } }
            if action.kind == .IF_IMAGE || action.kind == .IF_COLOR {
                Button { paste(AddTarget(parentID: action.id)) } label: { Label("Pegar dentro de «✔ Si se ve»", systemImage: "arrow.down.right.square") }
                Button { paste(AddTarget(parentID: action.id, otherwise: true)) } label: { Label("Pegar dentro de «✖ Si NO se ve»", systemImage: "arrow.down.right.square") }
            }
        }
        if parentOf(project.wrappedValue.actions, action.id) != nil {
            Button { change { moveOutOfBlock($0, action.id) } } label: { Label("Sacar del bloque", systemImage: "arrow.up.left.square") }
        }
        Divider()
        Button { change { updateAction($0, action.id) { a in var a = a; a.enabled.toggle(); return a } } } label: {
            Label(action.enabled ? "Desactivar" : "Activar", systemImage: action.enabled ? "pause.circle" : "checkmark.circle")
        }
        Divider()
        Button(role: .destructive) { change { updateAction($0, action.id) { _ in nil } } } label: { Label("Borrar", systemImage: "trash") }
    }

    private func change(_ edit: ([MacroAction]) -> [MacroAction]) { project.wrappedValue.actions = edit(project.wrappedValue.actions) }

    /// Marca o desmarca un paso; un bloque se marca con todo lo de dentro.
    private func toggle(_ action: MacroAction) {
        let ids = allActionIds([action])
        if selected.contains(action.id) { selected.subtract(ids) } else { selected.formUnion(ids) }
    }

    /// Un paso de la lista: botón de plegar (bloques), la fila y, en modo ordenar, las flechas.
    @ViewBuilder private func stepCell(_ action: MacroAction, number: String, depth: Int, project p: MacroProject) -> some View {
        let isBlock = [.REPEAT, .IF_IMAGE, .IF_COLOR, .WHEN_IMAGE].contains(action.kind) && !(action.kind == .WHEN_IMAGE && (isSimpleRule(action) || (action.children.isEmpty && action.otherwise.isEmpty)))
        let folded = library.collapsed.contains(action.id)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                if isBlock {
                    Button { if folded { library.collapsed.remove(action.id) } else { library.collapsed.insert(action.id) } } label: {
                        Image(systemName: folded ? "chevron.right" : "chevron.down").font(.subheadline.bold())
                            .frame(width: 30, height: 30).background(Color.accentColor.opacity(0.15), in: Circle())
                    }
                    .buttonStyle(.borderless)
                    .padding(.leading, indent(depth))
                    .accessibilityLabel(folded ? "Desplegar" : "Plegar")
                }
                if selecting {
                    Button { toggle(action) } label: {
                        Image(systemName: selected.contains(action.id) ? "checkmark.circle.fill" : "circle").font(.title2)
                            .foregroundStyle(selected.contains(action.id) ? Color.accentColor : Color.secondary)
                    }.buttonStyle(.borderless)
                }
                Button { if selecting { toggle(action) } else { editing = StepRef(id: action.id) } } label: { StepRow(number: number, action: action, project: p, depth: isBlock ? 0 : depth) }
                    .buttonStyle(.plain)
            }
            if sorting {
                let pos = siblingPosition(p.actions, action.id)
                HStack(spacing: 8) {
                    Button("▲ Subir") { change { moveAction($0, action.id, by: -1) } }.disabled(pos?.first ?? true)
                    Button("▼ Bajar") { change { moveAction($0, action.id, by: 1) } }.disabled(pos?.last ?? true)
                    if parentOf(p.actions, action.id) != nil { Button("⤴ Sacar") { change { moveOutOfBlock($0, action.id) } } }
                }
                .buttonStyle(.bordered).font(.subheadline)
                .padding(.leading, indent(depth) + 30)
            }
        }
        .overlay(alignment: .leading) { if isBlock { DepthBars(depth: depth) } }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { change { updateAction($0, action.id) { _ in nil } } } label: { Label("Borrar", systemImage: "trash") }
            Button { change { duplicateAction($0, action.id) } } label: { Label("Duplicar", systemImage: "plus.square.on.square") }.tint(.indigo)
        }
        .swipeActions(edge: .leading) {
            Button { library.clipboard = action; library.clipboardCut = false } label: { Label("Copiar", systemImage: "doc.on.doc") }.tint(.blue)
            Button { change { updateAction($0, action.id) { a in var a = a; a.enabled.toggle(); return a } } } label: { Label(action.enabled ? "Desactivar" : "Activar", systemImage: action.enabled ? "pause" : "play") }.tint(.orange)
        }
        .contextMenu { stepMenu(action) }
    }

    /// Pega una copia nueva del paso copiado/cortado. Sin destino, al final de la macro.
    private func paste(_ target: AddTarget?) {
        guard let clip = library.clipboard else { return }
        let copy = renewIDs(clip)
        change { list in
            guard let target else { return list + [copy] }
            if let next = target.nextTo { return insertAction(list, copy, next: next, after: target.after) }
            if let parent = target.parentID { return addInside(list, copy, parent: parent, otherwise: target.otherwise) }
            return list + [copy]
        }
        if let parent = target?.parentID { library.collapsed.remove(parent) }
        if library.clipboardCut { library.clipboard = nil; library.clipboardCut = false }
    }

    private func insert(_ kind: MacroKind, _ target: AddTarget) {
        let action = defaultAction(kind)
        change { list in
            if let next = target.nextTo { return insertAction(list, action, next: next, after: target.after) }
            if let parent = target.parentID { return addInside(list, action, parent: parent, otherwise: target.otherwise) }
            // Las reglas «⚡» van arriba, con las demás: vigilan toda la macro, da igual dónde estén.
            if kind == .WHEN_IMAGE, let i = list.firstIndex(where: { $0.kind != .WHEN_IMAGE }) { var l = list; l.insert(action, at: i); return l }
            return list + [action]
        }
        if !noConfigKinds.contains(kind) { pendingEdit = StepRef(id: action.id, fresh: true) }
    }

    /// Desde la hoja de un paso: «añadir otro después» cierra la hoja y abre la de añadir.
    private func addAfterClose(_ target: AddTarget) {
        editing = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { addTarget = target }
    }

    private func mergeWaitSteps() {
        let result = mergeWaits(project.wrappedValue.actions)
        guard result.merged > 0 else { mergeResult = "No hay esperas que se puedan juntar."; return }
        project.wrappedValue.actions = result.actions
        mergeResult = "Listo: \(plural(result.merged, "paso «Esperar» convertido", "pasos «Esperar» convertidos")) en esperas de los pasos de al lado. La macro hace lo mismo con menos pasos. (Necesita PrisonMacro 0.6.5 en Android.)"
    }

    private func countWaits(_ list: [MacroAction]) -> Int { list.reduce(0) { $0 + ($1.kind == .WAIT && $1.enabled ? 1 : 0) + countWaits($1.children) + countWaits($1.otherwise) } }
}

private func indent(_ depth: Int) -> CGFloat { CGFloat(depth) * 16 }

/// Rayas a la izquierda para ver qué pasos van dentro de otro.
private struct DepthBars: View {
    let depth: Int
    var body: some View {
        HStack(spacing: 13) { ForEach(0..<depth, id: \.self) { _ in Capsule().fill(Color.accentColor.opacity(0.35)).frame(width: 3) } }
            .padding(.vertical, -6)
    }
}

/// «Cómo empieza y cuántas veces se repite» (toca para cambiarlo).
private struct StartCard: View {
    let project: MacroProject
    var body: some View {
        let start = project.templates.first { $0.template.file == project.template?.file }
        HStack(spacing: 12) {
            Text("🔁").font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text(project.repeatCount == 0 ? "Sin fin, hasta que pulses ■" : project.repeatCount == 1 ? "Una vez" : "\(project.repeatCount) veces").font(.subheadline.weight(.semibold))
                Text((project.startOnImage ? "Empieza cuando se vea una imagen" : "Empieza al pulsar ▶") + (project.repeatCount != 1 && project.intervalMs > 0 ? " · \(readableDuration(project.intervalMs)) de pausa entre vueltas" : "") + (project.repeatCount == 0 && project.keepGoing ? " · si algo falla, vuelve a empezar" : "")).font(.caption).foregroundStyle(.secondary)
                if project.actions.contains(where: { $0.enabled }) {
                    Text(totalText(project)).font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor)
                }
            }
            Spacer()
            if project.startOnImage, let start, let encoded = project.imagesBase64[start.template.file] {
                TemplateThumbnail(saved: start, encoded: encoded, size: CGSize(width: 60, height: 42))
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
    }
}

func renewIDs(_ action: MacroAction) -> MacroAction {
    var copy = action
    copy.id = UUID().uuidString.lowercased()
    copy.children = copy.children.map(renewIDs)
    copy.otherwise = copy.otherwise.map(renewIDs)
    return copy
}

func repeatSummary(_ p: MacroProject) -> String {
    let pause = p.intervalMs > 0 ? ", con \(readableDuration(p.intervalMs)) de pausa" : ""
    if p.repeatCount == 0 { return "Se repite sin fin" + pause }
    if p.repeatCount == 1 { return "Se hace una vez" }
    return "Se repite \(p.repeatCount) veces" + pause
}

private struct StepRow: View {
    let number: String
    let action: MacroAction
    let project: MacroProject
    let depth: Int

    var body: some View {
        let image = project.templates.first { $0.id == action.templateId }
        let issues = actionIssues(action, in: project)
        let delays = delaysSummary(action)
        HStack(spacing: 10) {
            Text(number).font(.caption2.bold()).foregroundStyle(.white).padding(.horizontal, 6).padding(.vertical, 2)
                .background(Color.accentColor, in: Capsule()).frame(minWidth: 26)
            Text(kindEmoji(action.kind)).font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text(action.name.isEmpty ? kindLabel(action.kind) : action.name).font(.body.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                let summary = issues.first.map { "⚠ \($0)" } ?? readableActionSummary(action, image: image?.name)
                // Sin repetir lo que ya dice el nombre ni el tiempo de una espera.
                let redundant = issues.isEmpty && (action.kind == .WAIT || summary.replacingOccurrences(of: "Toca ", with: "") == action.name.replacingOccurrences(of: "Tocar ", with: ""))
                if !redundant {
                    Text(summary).font(.caption).foregroundStyle(issues.isEmpty ? Color.secondary : Color.orange).lineLimit(2)
                }
                // Una sola línea pequeña: esperas, cuánto tarda y cuánto lleva la macro hasta aquí.
                let time = action.enabled && action.kind != .WHEN_IMAGE && action.kind != .STOP
                    ? "⌛ \(shortSpan(stepTime(action, project)))" + (elapsedUntil(action.id, project).map { " · llevas \(shortSpan($0))" } ?? "") : nil
                let extra = [delays.isEmpty ? nil : delays, time].compactMap { $0 }
                if !extra.isEmpty { Text(extra.joined(separator: "   ")).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 0)
            if [.CLICK_IMAGE, .WAIT_IMAGE, .IF_IMAGE, .WHEN_IMAGE].contains(action.kind) || !action.templateId.isEmpty {
                TemplateThumbnail(saved: image, encoded: image.flatMap { project.imagesBase64[$0.template.file] }, size: CGSize(width: 58, height: 40))
            }
            if action.kind == .IF_COLOR {
                RoundedRectangle(cornerRadius: 6).fill(colorFromInt(action.targetColor)).frame(width: 28, height: 28).overlay(RoundedRectangle(cornerRadius: 6).stroke(.secondary))
            }
            if !action.enabled { Text("OFF").font(.caption2.bold()).foregroundStyle(.orange) }
        }
        .opacity(action.enabled ? 1 : 0.45)
        .padding(.vertical, 2)
        .padding(.leading, indent(depth))
        .overlay(alignment: .leading) { DepthBars(depth: depth) }
    }
}

/// Editar un paso (esté donde esté), en una hoja que sube desde abajo.
struct StepSheet: View {
    let projectID: String
    let stepID: String
    /// Paso recién añadido: si necesita imagen, se abre ya la elección (y al elegirla, listo).
    var fresh = false
    let onAddNext: (AddTarget) -> Void
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @StateObject private var photos: PhotoFlow
    @State private var more = false
    /// El paso como estaba al abrir la hoja: al cerrarla, los tiempos cambiados se cambian también en el texto.
    @State private var original: MacroAction?

    init(projectID: String, stepID: String, fresh: Bool = false, onAddNext: @escaping (AddTarget) -> Void) {
        self.projectID = projectID; self.stepID = stepID; self.fresh = fresh; self.onAddNext = onAddNext
        _photos = StateObject(wrappedValue: PhotoFlow(projectID: projectID))
    }

    private var project: Binding<MacroProject> { library.binding(projectID) }
    private var action: Binding<MacroAction> {
        Binding(get: { findAction(project.wrappedValue.actions, stepID) ?? MacroAction(id: stepID) },
                set: { new in project.wrappedValue.actions = updateAction(project.wrappedValue.actions, stepID) { _ in new } })
    }

    var body: some View {
        NavigationStack {
            Group {
                if findAction(project.wrappedValue.actions, stepID) != nil {
                    let p = project.wrappedValue
                    let a = action.wrappedValue
                    let issues = actionIssues(a, in: p)
                    let pos = siblingPosition(p.actions, stepID)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            if !issues.isEmpty {
                                Text("⚠ " + issues.joined(separator: " · ")).font(.subheadline).foregroundStyle(.orange)
                            }
                            ActionKindEditor(action: action, project: p, knownApps: knownApps(library.projects))
                            // «La orden»: qué hace cuando ve la imagen, sin perder la imagen ni el sitio del toque.
                            if let current = orderOf(a) {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("¿Qué hace cuando ve la imagen?").font(.headline)
                                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8)], spacing: 8) {
                                        ForEach(possibleOrders(a)) { o in
                                            Button { changeOrderHere(o) } label: { Text(o.label).font(.subheadline).frame(maxWidth: .infinity) }
                                                .buttonStyle(.bordered).tint(o == current ? .accentColor : .secondary)
                                        }
                                    }
                                    if hasInnerSteps(a) { Text("Tiene pasos dentro: para «Tocarla» o «Esperar», saca antes esos pasos.").font(.caption).foregroundStyle(.secondary) }
                                }
                            }
                            // Lo que casi nunca hace falta, plegado.
                            DisclosureGroup(isExpanded: $more) {
                                VStack(alignment: .leading, spacing: 14) {
                                    LabeledField(title: "Nombre del paso") { TextField("Nombre", text: action.name).textFieldStyle(.roundedBorder) }
                                    if !TimeInText.find(a.name + " " + a.description).isEmpty {
                                        Text("⏱ Si cambias el tiempo, el nombre y la nota se cambian solos al cerrar.").font(.caption).foregroundStyle(.secondary)
                                    }
                                    Toggle("Activo", isOn: action.enabled)
                                    ActionKindEditor(action: action, project: p, knownApps: knownApps(library.projects), advanced: true)
                                    if a.kind != .STOP && a.kind != .WHEN_IMAGE && a.kind != .WAIT { StepDelaysEditor(action: action) }
                                    LabeledField(title: "Nota (opcional)") {
                                        TextField("Por ejemplo: «quita el anuncio»", text: action.description, axis: .vertical).textFieldStyle(.roundedBorder)
                                    }
                                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8)], spacing: 8) {
                                        SmallButton("⬆ Subir") { project.wrappedValue.actions = moveAction(p.actions, stepID, by: -1) }.disabled(pos?.first ?? true)
                                        SmallButton("⬇ Bajar") { project.wrappedValue.actions = moveAction(p.actions, stepID, by: 1) }.disabled(pos?.last ?? true)
                                        SmallButton("＋ Paso después") { onAddNext(AddTarget(nextTo: stepID, after: true)) }
                                        SmallButton("⧉ Duplicar") { project.wrappedValue.actions = duplicateAction(p.actions, stepID) }
                                    }
                                }
                                .padding(.top, 8)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("⚙ Más opciones").font(.headline)
                                    Text(moreSummary(a, p)).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Button(role: .destructive) { project.wrappedValue.actions = updateAction(p.actions, stepID) { _ in nil }; dismiss() } label: {
                                Label("Borrar este paso", systemImage: "trash").frame(maxWidth: .infinity)
                            }.buttonStyle(.bordered)
                        }
                        .padding()
                    }
                    .navigationTitle("\(kindEmoji(a.kind)) \(kindLabel(a.kind))")
                } else {
                    Text("Este paso ya no existe.").foregroundStyle(.secondary)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { library.undo(projectID) } label: { Image(systemName: "arrow.uturn.backward") }.disabled(!library.canUndo(projectID)).accessibilityLabel("Deshacer")
                }
                ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() }.bold() }
            }
        }
        .modifier(PhotoFlowHost(flow: photos))
        .onAppear { if original == nil { original = findAction(project.wrappedValue.actions, stepID) } }
        .onDisappear { applyRetime() }
        .task {
            // Paso nuevo de imagen: se abre ya la elección; al elegirla, el paso queda hecho (como en Android).
            // (también al abrir un paso que aún no la tiene, por ejemplo los de «⚡ Macro rápida»).
            guard let a = findAction(project.wrappedValue.actions, stepID), [.CLICK_IMAGE, .WAIT_IMAGE, .WHEN_IMAGE, .IF_IMAGE].contains(a.kind),
                  !project.wrappedValue.templates.contains(where: { $0.id == a.templateId }) else { return }
            try? await Task.sleep(nanoseconds: 450_000_000)
            photos.chooseSaved(selected: nil) { id, name in
                var updated = action.wrappedValue
                updated.templateId = id
                renameForImage(&updated, name)
                action.wrappedValue = updated
                if updated.kind != .IF_IMAGE { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { dismiss() } }
            }
        }
    }
}

extension StepSheet {
    /// Cambia lo que hace el paso (la imagen y el sitio del toque se quedan igual).
    func changeOrderHere(_ o: ImageOrder) {
        applyRetime() // antes, lo que se hubiera cambiado de tiempos
        guard let now = findAction(project.wrappedValue.actions, stepID) else { return }
        let p = project.wrappedValue
        let image = p.templates.first { $0.id == now.templateId }?.name ?? ""
        let changed = changeOrder(now, to: o, image: image, timeoutMs: p.timeoutMs)
        action.wrappedValue = changed
        original = changed
    }

    /// «Jugando (15 s)» → «Jugando (20 s)»: si ha cambiado un tiempo, también cambia en el nombre del paso,
    /// en su nota y en el nombre de la macro (como en Android al guardar).
    func applyRetime() {
        guard let before = original, let now = findAction(project.wrappedValue.actions, stepID) else { return }
        var p = project.wrappedValue
        let ch = timeChanges(before, now, timeoutMs: p.timeoutMs)
        var a = now
        if !ch.isEmpty {
            a.name = String(TimeInText.retime(a.name, ch).prefix(100))
            a.description = String(TimeInText.retime(a.description, ch).prefix(500))
            p.name = String(TimeInText.retime(p.name, ch).prefix(100))
            p.actions = updateAction(p.actions, stepID) { _ in a }
            if p != project.wrappedValue { project.wrappedValue = p }
        }
        original = a
    }
}

/// Pasos activos a los que aún les falta su imagen (o el juego, en «Abrir un juego»).
func stepsToFinish(_ p: MacroProject) -> [MacroAction] {
    let ids = Set(p.templates.map(\.id))
    func walk(_ list: [MacroAction]) -> [MacroAction] {
        list.filter(\.enabled).flatMap { a -> [MacroAction] in
            let missing = ([.CLICK_IMAGE, .WAIT_IMAGE, .IF_IMAGE, .WHEN_IMAGE].contains(a.kind) && !ids.contains(a.templateId)) || (a.kind == .OPEN_APP && (a.packageName == noAppPackage || a.packageName.isEmpty))
            return (missing ? [a] : []) + walk(a.children) + walk(a.otherwise)
        }
    }
    return Array(walk(p.actions).prefix(6))
}

/// Resumen de lo cambiado en «⚙ Más opciones» (o qué hay dentro, si no se ha cambiado nada).
func moreSummary(_ a: MacroAction, _ p: MacroProject) -> String {
    var parts: [String] = []
    if a.kind == .CLICK_IMAGE {
        if a.clickTarget == .OFFSET { parts.append("toca desplazado") }
        if a.clickTarget == .FIXED { parts.append("toca en otro punto") }
        if a.clickStyle == .DOUBLE { parts.append("doble toque") }
        if a.clickStyle == .LONG { parts.append("pulsación larga") }
        if a.skipIfMissing { parts.append("si no sale, sigue") }
        if a.waitMs > 0 && a.waitMs != p.timeoutMs { parts.append("la busca \(readableDuration(a.waitMs))") }
    }
    if a.kind == .WAIT_IMAGE && a.skipIfMissing && !a.untilGone { parts.append("si no sale, sigue") }
    if a.kind == .WHEN_IMAGE && a.afterRule != .CONTINUE { parts.append("después \(afterRuleLabel(a.afterRule))") }
    if a.delayBeforeMs > 0 { parts.append("espera \(readableDuration(a.delayBeforeMs)) antes") }
    if a.delayAfterMs > 0 { parts.append("espera \(readableDuration(a.delayAfterMs)) después") }
    if !a.enabled { parts.append("desactivado") }
    if !a.description.isEmpty { parts.append("con nota") }
    if parts.isEmpty { return (a.kind == .WAIT ? "Nombre y nota." : "Nombre, esperas y detalles.") + " No hace falta tocarlas." }
    return parts.joined(separator: " · ")
}

/// Reglas «⚡» con imagen pero sin nada dentro: les pone el toque a su imagen.
func fixEmptyRules(_ p: MacroProject) -> (actions: [MacroAction], count: Int) {
    var n = 0
    func walk(_ list: [MacroAction]) -> [MacroAction] {
        list.map { a in
            var a = a
            if a.kind == .WHEN_IMAGE, !a.children.contains(where: \.enabled), let image = p.templates.first(where: { $0.id == a.templateId }) {
                a.children.append(ruleTap(image.id, image.name)); n += 1
            }
            a.children = walk(a.children); a.otherwise = walk(a.otherwise)
            return a
        }
    }
    let fixed = walk(p.actions)
    return (fixed, n)
}

/// Apps que ya se abren en alguna macro (para no tener que escribir el paquete).
func knownApps(_ projects: [MacroProject]) -> [String] {
    var seen = Set<String>(["com.wildlife.games.battle.royale.free.zooba"])
    func walk(_ list: [MacroAction]) { for a in list { if a.kind == .OPEN_APP, a.packageName.contains(".") { seen.insert(a.packageName) }; walk(a.children); walk(a.otherwise) } }
    projects.forEach { walk($0.actions) }
    seen.remove("es.macrostudio")
    return seen.sorted { friendlyApp($0).lowercased() < friendlyApp($1).lowercased() }
}

/// ¿Qué debe hacer este paso? Con una explicación de cada tipo.
struct AddStepSheet: View {
    let onChoose: (MacroKind) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var more = false

    private func row(_ kind: MacroKind) -> some View {
        Button { onChoose(kind); dismiss() } label: {
            HStack(spacing: 12) {
                Text(kindEmoji(kind)).font(.title2).frame(width: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(kindLabel(kind)).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    Text(kindExplanation(kind)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                // Arriba, los pasos de siempre. El resto, plegado (como en Android).
                Section { ForEach(basicKinds, id: \.self) { row($0) } }
                Section {
                    Button(more ? "▲ Menos tipos de paso" : "▼ Más tipos de paso (si se ve…, repetir, color, botones de Android)") { withAnimation { more.toggle() } }
                }
                if more {
                    ForEach(kindGroups, id: \.0) { group in
                        Section(group.0) { ForEach(group.1, id: \.self) { row($0) } }
                    }
                }
            }
            .navigationTitle("¿Qué hace este paso?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Ajustes de la macro: cómo empieza, cuántas veces se repite y cómo busca las imágenes (como en Android).
struct MacroSettingsSheet: View {
    let projectID: String
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @StateObject private var photos: PhotoFlow
    @State private var more = false

    init(projectID: String) {
        self.projectID = projectID
        _photos = StateObject(wrappedValue: PhotoFlow(projectID: projectID))
    }

    private var project: Binding<MacroProject> { library.binding(projectID) }

    var body: some View {
        let p = project.wrappedValue
        let start = p.templates.first { $0.template.file == p.template?.file }
        NavigationStack {
            Form {
                Section {
                    Picker("Repetición", selection: Binding(get: { p.repeatCount == 0 ? 0 : (p.repeatCount == 1 ? 1 : 2) }, set: { v in
                        // «Sin fin» es sin fin de verdad: quita la parada de seguridad de serie (30 min).
                        if v == 0 && p.repeatCount != 0 && p.maxMinutes == 30 { project.wrappedValue.maxMinutes = 0 }
                        project.wrappedValue.repeatCount = v == 0 ? 0 : (v == 1 ? 1 : max(p.repeatCount, 5))
                    })) { Text("Una vez").tag(1); Text("Varias veces").tag(2); Text("Sin fin").tag(0) }
                        .pickerStyle(.segmented)
                    if p.repeatCount > 1 { Stepper("Vueltas: \(p.repeatCount)", value: project.repeatCount, in: 2...1000) }
                } header: { Text("🔁 Cuántas veces") }

                Section {
                    Picker("Cuándo empieza", selection: project.startOnImage) { Text("Al pulsar ▶").tag(false); Text("Cuando se vea una imagen").tag(true) }
                        .pickerStyle(.segmented)
                    if p.startOnImage {
                        HStack(spacing: 12) {
                            TemplateThumbnail(saved: start, encoded: start.flatMap { p.imagesBase64[$0.template.file] }, size: CGSize(width: 90, height: 62))
                            Text(start?.name ?? "Sin imagen elegida").font(.body.weight(.semibold)).foregroundStyle(start == nil ? .orange : .primary)
                        }
                        SmallButton("🖼 Elegir imagen", prominent: start == nil) { photos.chooseSaved(selected: start?.id) { id, _ in setStart(id) } }
                    }
                } header: { Text("🚩 Cuándo empieza") }

                Section {
                    Button(more ? "▲ Menos ajustes" : "▼ Más ajustes (fallos, pausas, búsqueda de imágenes)") { withAnimation { more.toggle() } }
                }
                if more {
                    Section {
                        if p.repeatCount == 0 {
                            Picker("Si un paso falla", selection: project.keepGoing) { Text("Volver a empezar").tag(true); Text("Parar").tag(false) }
                        }
                        if p.repeatCount != 1 {
                            SecondsField(title: "Pausa entre vueltas (segundos)", value: project.intervalMs, minimum: 0, maximum: 3_600_000)
                            QuickChoices(values: [0, 5_000, 60_000, 300_000], selected: p.intervalMs) { project.wrappedValue.intervalMs = $0 }
                        }
                        SecondsField(title: "Espera antes de empezar (segundos)", value: project.delayMs, minimum: 0, maximum: 600_000)
                        Stepper(p.maxMinutes == 0 ? "Nunca se para sola" : "Se para sola a los \(p.maxMinutes) min", value: project.maxMinutes, in: 0...1440, step: 5)
                    } header: { Text("Vueltas") }
                    Section {
                        Picker("Dónde buscar", selection: project.mode) { Text("En toda la pantalla").tag(MacroSearchMode.ANYWHERE); Text("Solo en su sitio").tag(MacroSearchMode.EXACT) }
                            .pickerStyle(.segmented)
                        VStack(alignment: .leading) {
                            Text("Parecido exigido: \(Int((p.threshold * 100).rounded())) % (recomendado 93–95 %)").font(.body.weight(.semibold))
                            Slider(value: Binding(get: { p.threshold }, set: { project.wrappedValue.threshold = ($0 * 100).rounded() / 100 }), in: 0.7...1.0)
                        }
                        SecondsField(title: "Tiempo máximo buscando una imagen (segundos)", value: project.timeoutMs, minimum: 1_000, maximum: 3_600_000)
                    } header: { Text("🔍 Búsqueda de imágenes") } footer: {
                        if p.threshold < 0.92 { Text("⚠ Por debajo de 92 % puede confundir imágenes parecidas.") }
                    }
                }
            }
            .navigationTitle("Ajustes de la macro")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() }.bold() } }
        }
        .modifier(PhotoFlowHost(flow: photos))
    }

    private func setStart(_ id: String) {
        guard let saved = library.project(projectID)?.templates.first(where: { $0.id == id }) else { return }
        project.wrappedValue.template = saved.template
        project.wrappedValue.startOnImage = true
    }
}

/// Las imágenes de una macro: añadir, renombrar, recortar de nuevo, probar, borrar.
struct ImagesView: View {
    let projectID: String
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var shots: ShotLibrary
    @State private var moving = false
    @StateObject private var photos: PhotoFlow
    @State private var deleting: SavedMacroTemplate?
    @State private var selecting = false
    @State private var marked: Set<String> = []
    @State private var confirmBulk = false

    init(projectID: String) {
        self.projectID = projectID
        _photos = StateObject(wrappedValue: PhotoFlow(projectID: projectID))
    }

    private var project: Binding<MacroProject> { library.binding(projectID) }

    var body: some View {
        let p = project.wrappedValue
        List {
            Section {
                Button { photos.newCapture() } label: { Label("Recortar de 📸 Mis capturas", systemImage: "rectangle.dashed.badge.record").font(.headline) }
                Button { photos.newCaptureFromPhotos() } label: { Label("Añadir desde Fotos", systemImage: "photo.badge.plus") }
                Button { photos.newCaptureFromFiles() } label: { Label("Añadir desde Archivos", systemImage: "folder.badge.plus") }
            } footer: {
                Text("«Mis capturas» son las capturas enteras del juego guardadas en tu cuenta: las haces en Android con «📸 Varias capturas» (o las añades aquí desde Fotos) y están en todos tus aparatos.")
            }
            Section {
                FolderChips(folders: allFolders(p), count: { f in p.templates.filter { inFolder($0.folder, f) }.count }, selected: $shots.openFolder,
                            onCreate: { n in let c = shots.createFolder(n); if !c.isEmpty { shots.openFolder = c } },
                            onRename: { old, new in
                                let n = ShotLibrary.clean(new); shots.renameFolder(old, to: n)
                                for i in project.wrappedValue.templates.indices where project.wrappedValue.templates[i].folder == old { project.wrappedValue.templates[i].folder = n }
                            },
                            onDelete: { f in
                                shots.deleteFolder(f)
                                for i in project.wrappedValue.templates.indices where project.wrappedValue.templates[i].folder == f { project.wrappedValue.templates[i].folder = nil }
                            })
                if let f = shots.openFolder, !f.isEmpty { Text("📁 Estás en «\(f)»: los recortes nuevos se guardan aquí.").font(.caption).foregroundStyle(.secondary) }
            }
            Section("Imágenes · \(p.templates.filter { inFolder($0.folder, shots.openFolder) }.count)") {
                if p.templates.isEmpty { Text("Todavía no hay imágenes. Sirven para que la macro reconozca botones o pantallas.").foregroundStyle(.secondary) }
                ForEach(Array(p.templates.indices).filter { inFolder(p.templates[$0].folder, shots.openFolder) }, id: \.self) { i in
                    let saved = p.templates[i]
                    HStack(spacing: 12) {
                        if selecting {
                            Button { if marked.contains(saved.id) { marked.remove(saved.id) } else { marked.insert(saved.id) } } label: {
                                Image(systemName: marked.contains(saved.id) ? "checkmark.circle.fill" : "circle").font(.title2)
                                    .foregroundStyle(marked.contains(saved.id) ? Color.red : Color.secondary)
                            }.buttonStyle(.borderless)
                        }
                        TemplateThumbnail(saved: saved, encoded: p.imagesBase64[saved.template.file], size: CGSize(width: 96, height: 64))
                        VStack(alignment: .leading, spacing: 4) {
                            TextField("Nombre", text: project.templates[i].name).font(.body.weight(.semibold))
                            Text(usage(saved, in: p) + (shots.openFolder == nil && !(saved.folder ?? "").isEmpty ? " · 📁 \(saved.folder ?? "")" : "")).font(.caption).foregroundStyle(.secondary)
                        }
                        Menu {
                            Menu("📁 Mover a carpeta") {
                                ForEach(allFolders(p), id: \.self) { f in Button(f) { setFolder([saved.id], f) } }
                                Button("Sin carpeta") { setFolder([saved.id], "") }
                            }
                            Button { photos.test(saved) } label: { Label("¿La encuentra en una captura?", systemImage: "magnifyingglass") }
                            Button { recrop(saved, p) } label: { Label("Recortar otra vez", systemImage: "crop") }
                            Button { project.wrappedValue.template = saved.template; project.wrappedValue.startOnImage = true } label: { Label("Usar como imagen de inicio", systemImage: "flag") }
                                .disabled(p.startOnImage && p.template?.file == saved.template.file)
                            Divider()
                            Button(role: .destructive) { deleting = saved } label: { Label("Borrar", systemImage: "trash") }
                        } label: { Image(systemName: "ellipsis.circle").font(.title2) }
                    }
                    .swipeActions { Button(role: .destructive) { deleting = saved } label: { Label("Borrar", systemImage: "trash") } }
                }
            }
        }
        .navigationTitle("Imágenes")
        .toolbar {
            if !p.templates.isEmpty {
                Button(selecting ? "Listo" : "Seleccionar") { selecting.toggle(); marked = [] }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting {
                HStack {
                    Button(marked.count == p.templates.count ? "☐ Ninguno" : "☑ Todos") { marked = marked.count == p.templates.count ? [] : Set(p.templates.map(\.id)) }.buttonStyle(.bordered)
                    Button("📁 Mover (\(marked.count))") { moving = true }.buttonStyle(.bordered).disabled(marked.isEmpty)
                    Button("🗑 Borrar (\(marked.count))", role: .destructive) { confirmBulk = true }.buttonStyle(.borderedProminent).tint(.red).disabled(marked.isEmpty)
                }
                .padding().frame(maxWidth: .infinity).background(.bar)
            }
        }
        .confirmationDialog("¿Borrar \(marked.count) recorte(s)?", isPresented: $confirmBulk, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { library.removeCaptures(marked, from: projectID); marked = []; selecting = false }
        } message: { Text("Los pasos que los usan se quedarán sin imagen. Las capturas enteras se borran en «📸 Mis capturas».") }
        .modifier(MoveToFolder(isPresented: $moving, folders: allFolders(p)) { f in setFolder(marked, f); marked = []; selecting = false })
        .modifier(PhotoFlowHost(flow: photos))
        .confirmationDialog("¿Borrar «\(deleting?.name ?? "")»?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { if let d = deleting { library.removeCapture(d.id, from: projectID) }; deleting = nil }
        } message: { Text("Los pasos que la usan se quedarán sin imagen.") }
    }

    /// Todas las carpetas: las de las capturas de este iPhone y las de los recortes de la macro.
    private func allFolders(_ p: MacroProject) -> [String] {
        Array(Set(shots.folders + p.templates.compactMap { $0.folder }.filter { !$0.isEmpty })).sorted { $0.lowercased() < $1.lowercased() }
    }

    private func setFolder(_ ids: Set<String>, _ folder: String) {
        if !folder.isEmpty { shots.createFolder(folder) }
        for i in project.wrappedValue.templates.indices where ids.contains(project.wrappedValue.templates[i].id) {
            project.wrappedValue.templates[i].folder = folder.isEmpty ? nil : folder
        }
    }

    private func recrop(_ saved: SavedMacroTemplate, _ p: MacroProject) {
        guard let encoded = p.imagesBase64[saved.template.file], let data = Data(base64Encoded: encoded), let image = UIImage(data: data) else { return }
        photos.recrop(saved, image: image)
    }

    private func usage(_ saved: SavedMacroTemplate, in p: MacroProject) -> String {
        func count(_ list: [MacroAction]) -> Int { list.reduce(0) { $0 + ($1.templateId == saved.id ? 1 : 0) + count($1.children) + count($1.otherwise) } }
        let n = count(p.actions)
        let start = p.startOnImage && p.template?.file == saved.template.file ? " · 🚩 imagen de inicio" : ""
        return "\(saved.template.width)×\(saved.template.height) px · " + (n == 0 ? "no la usa ningún paso" : "la usa\(n == 1 ? "" : "n") \(plural(n, "paso", "pasos"))") + start
    }
}
