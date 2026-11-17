import SwiftUI
import UIKit

struct CroppedTemplateImage {
    var name: String = ""
    let png: Data
    let x: Int
    let y: Int
    let width: Int
    let height: Int
    let screenWidth: Int
    let screenHeight: Int
}

/// Recorta la parte de una captura que Android tiene que buscar.
/// Arrastra para dibujar el recuadro; tira de sus bordes o esquinas para ajustarlo y
/// arrastra dentro de él para moverlo. Pellizca o usa ＋/－ para ampliar.
struct ImageCropView: View {
    let image: UIImage
    let onSave: (CroppedTemplateImage) -> Void

    private enum DragMode { case new, move, resize(Int), pan }

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    /// Recuadro en píxeles de la captura.
    @State private var selection: CGRect?
    @State private var dragMode: DragMode?
    @State private var dragStartSelection: CGRect?
    @State private var zoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var panStart: CGSize?
    @State private var pinchStart: CGFloat?
    @State private var movingImage = false
    @State private var viewSize: CGSize = .zero
    @State private var screenWidth: String
    @State private var screenHeight: String
    private let originX: Int
    private let originY: Int
    private let normalized: UIImage

    init(image: UIImage, name: String = "", screenWidth: Int? = nil, screenHeight: Int? = nil, originX: Int = 0, originY: Int = 0, onSave: @escaping (CroppedTemplateImage) -> Void) {
        let up = ImageCropView.normalize(image)
        self.image = image
        self.normalized = up
        self.onSave = onSave
        self.originX = originX
        self.originY = originY
        let width = up.cgImage?.width ?? Int(up.size.width)
        let height = up.cgImage?.height ?? Int(up.size.height)
        _name = State(initialValue: name)
        _screenWidth = State(initialValue: String(screenWidth ?? width))
        _screenHeight = State(initialValue: String(screenHeight ?? height))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 10) {
                TextField("Nombre (por ejemplo «Botón Jugar»)", text: $name)
                    .textFieldStyle(.roundedBorder)
                Text(movingImage ? "✋ Arrastra para mover la imagen ampliada. Pulsa «▭ Recuadro» para volver a marcar."
                     : selection == nil ? "👆 Arrastra el dedo sobre lo que hay que buscar (un botón, un icono…). Pellizca o pulsa ＋ para ampliar."
                     : "Bien. Tira de los bordes o esquinas para ajustarlo, arrastra dentro para moverlo o fuera para dibujarlo de nuevo.")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 8) {
                    Button { zoomBy(1 / 1.6) } label: { Image(systemName: "minus.magnifyingglass") }.buttonStyle(.bordered)
                    Button { zoomBy(1.6) } label: { Image(systemName: "plus.magnifyingglass") }.buttonStyle(.bordered)
                    Button("Ver toda") { zoom = 1; pan = .zero }.buttonStyle(.bordered).disabled(zoom <= 1.01)
                    if zoom > 1.01 { Text("×" + String(format: "%.1f", zoom)).font(.caption).foregroundStyle(.secondary) }
                    Spacer()
                }
                .font(.subheadline)
                // Con la imagen ampliada: el dedo marca el recuadro o mueve la imagen.
                if zoom > 1.01 || movingImage {
                    Picker("", selection: $movingImage) {
                        Text("▭ Marcar recuadro").tag(false)
                        Text("✋ Mover imagen").tag(true)
                    }
                    .pickerStyle(.segmented)
                }

                GeometryReader { proxy in
                    let imageRect = displayRect(in: proxy.size)
                    ZStack {
                        Color.black
                        Image(uiImage: normalized)
                            .resizable()
                            .interpolation(zoom > 3 ? .none : .medium)
                            .frame(width: imageRect.width, height: imageRect.height)
                            .position(x: imageRect.midX, y: imageRect.midY)
                        if let sel = selection.map({ toView($0, in: imageRect) }) {
                            Rectangle().fill(Color.accentColor.opacity(0.22))
                                .frame(width: sel.width, height: sel.height)
                                .position(x: sel.midX, y: sel.midY)
                            Rectangle().stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [7, 4]))
                                .frame(width: sel.width, height: sel.height)
                                .position(x: sel.midX, y: sel.midY)
                            // Asas en las esquinas: se ve que se puede tirar de ellas.
                            ForEach(0..<4, id: \.self) { i in
                                Rectangle().fill(Color.accentColor).frame(width: 12, height: 12)
                                    .position(x: i % 2 == 0 ? sel.minX : sel.maxX, y: i < 2 ? sel.minY : sel.maxY)
                            }
                        }
                    }
                    .onAppear { viewSize = proxy.size }
                    .onChange(of: proxy.size) { size in viewSize = size; pan = clampedPan(pan, in: size) }
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .local)
                            .onChanged { value in dragChanged(value, imageRect: imageRect, size: proxy.size) }
                            .onEnded { _ in dragMode = nil; dragStartSelection = nil; panStart = nil }
                    )
                    .simultaneousGesture(
                        MagnificationGesture()
                            .onChanged { value in
                                // Al pellizcar, lo que el primer dedo empezaba a dibujar se deshace.
                                if pinchStart == nil {
                                    pinchStart = zoom
                                    if let start = dragStartSelection { selection = start }
                                    dragMode = .pan
                                }
                                setZoom((pinchStart ?? 1) * value)
                            }
                            .onEnded { _ in pinchStart = nil }
                    )
                }
                .frame(minHeight: 260)

                HStack(spacing: 10) {
                    if let preview = selection.flatMap({ crop($0) }), let ui = UIImage(data: preview.data) {
                        Image(uiImage: ui).resizable().scaledToFit().frame(maxWidth: 120, maxHeight: 60)
                            .background(Color.black.opacity(0.2)).clipShape(RoundedRectangle(cornerRadius: 6))
                        Text("Esto es lo que buscará Android (\(preview.width)×\(preview.height)).").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text("Aquí verás el recorte.").font(.caption).foregroundStyle(.tertiary)
                    }
                    Spacer()
                }
                .frame(height: 60)

                DisclosureGroup("Avanzado") {
                    HStack {
                        TextField("Ancho", text: $screenWidth).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                        Text("×")
                        TextField("Alto", text: $screenHeight).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                    }
                    Text("Tamaño de la pantalla donde se hizo la captura. Déjalo como está si la captura es de la pantalla entera de BlueStacks o del móvil Android.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .font(.subheadline)
            }
            .padding()
            .interactiveDismissDisabled()
            .navigationTitle("Recortar captura")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Guardar") { save() }.bold().disabled(selection.flatMap { crop($0) } == nil) }
            }
        }
    }

    static func normalize(_ image: UIImage) -> UIImage {
        guard image.imageOrientation != .up else { return image }
        let format = UIGraphicsImageRendererFormat(); format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: image.size)) }
    }

    private var pixelSize: CGSize {
        CGSize(width: CGFloat(normalized.cgImage?.width ?? Int(normalized.size.width)), height: CGFloat(normalized.cgImage?.height ?? Int(normalized.size.height)))
    }

    private func fittedRect(in available: CGSize) -> CGRect {
        let px = pixelSize
        let scale = min(available.width / max(px.width, 1), available.height / max(px.height, 1))
        let size = CGSize(width: px.width * scale, height: px.height * scale)
        return CGRect(x: (available.width - size.width) / 2, y: (available.height - size.height) / 2, width: size.width, height: size.height)
    }

    /// Dónde se dibuja la captura entera con el zoom y el desplazamiento actuales.
    private func displayRect(in size: CGSize, zoom z: CGFloat? = nil, pan p: CGSize? = nil) -> CGRect {
        let base = fittedRect(in: size)
        let zz = z ?? zoom
        let w = base.width * zz, h = base.height * zz
        let c = clampedPan(p ?? pan, in: size, zoom: zz)
        return CGRect(x: size.width / 2 + c.width - w / 2, y: size.height / 2 + c.height - h / 2, width: w, height: h)
    }

    /// Que la imagen ampliada no deje huecos negros: no se puede mover más allá de sus bordes.
    private func clampedPan(_ p: CGSize, in size: CGSize, zoom z: CGFloat? = nil) -> CGSize {
        let base = fittedRect(in: size)
        let zz = z ?? zoom
        let maxX = max(0, (base.width * zz - size.width) / 2), maxY = max(0, (base.height * zz - size.height) / 2)
        return CGSize(width: min(max(p.width, -maxX), maxX), height: min(max(p.height, -maxY), maxY))
    }

    private func setZoom(_ value: CGFloat) {
        let size = viewSize
        let maxZoom = max(2, 16 / max(fittedRect(in: size).width / max(pixelSize.width, 1), 0.01))
        let newZoom = min(max(value, 1), maxZoom)
        // Mantiene quieto lo marcado (o el centro) mientras se amplía.
        let old = displayRect(in: size)
        let anchorPx: CGPoint = selection.map { CGPoint(x: $0.midX, y: $0.midY) } ?? CGPoint(x: pixelSize.width / 2, y: pixelSize.height / 2)
        let anchorView = CGPoint(x: old.minX + anchorPx.x / pixelSize.width * old.width, y: old.minY + anchorPx.y / pixelSize.height * old.height)
        let base = fittedRect(in: size)
        let w = base.width * newZoom, h = base.height * newZoom
        // Posición del centro de la imagen para que el ancla quede en el mismo sitio.
        let cx = anchorView.x - (anchorPx.x / pixelSize.width - 0.5) * w
        let cy = anchorView.y - (anchorPx.y / pixelSize.height - 0.5) * h
        zoom = newZoom
        pan = clampedPan(CGSize(width: cx - size.width / 2, height: cy - size.height / 2), in: size, zoom: newZoom)
    }

    private func zoomBy(_ factor: CGFloat) {
        setZoom(zoom * factor)
        // Al ampliar con el botón, lo marcado se pone en el centro.
        if factor > 1, let sel = selection {
            let size = viewSize, r = displayRect(in: size)
            let v = CGPoint(x: r.minX + sel.midX / pixelSize.width * r.width, y: r.minY + sel.midY / pixelSize.height * r.height)
            pan = clampedPan(CGSize(width: pan.width + size.width / 2 - v.x, height: pan.height + size.height / 2 - v.y), in: size)
        }
        if zoom <= 1.01 { movingImage = false }
    }

    private func toPx(_ p: CGPoint, in r: CGRect) -> CGPoint {
        let px = pixelSize
        return CGPoint(x: min(max((p.x - r.minX) / r.width * px.width, 0), px.width), y: min(max((p.y - r.minY) / r.height * px.height, 0), px.height))
    }

    private func toView(_ s: CGRect, in r: CGRect) -> CGRect {
        let px = pixelSize
        return CGRect(x: r.minX + s.minX / px.width * r.width, y: r.minY + s.minY / px.height * r.height, width: s.width / px.width * r.width, height: s.height / px.height * r.height)
    }

    /// Bordes que hay cerca del dedo: 1 izquierda, 2 arriba, 4 derecha, 8 abajo.
    private func edges(at p: CGPoint, of v: CGRect) -> Int {
        let grab: CGFloat = 22
        guard p.x > v.minX - grab, p.x < v.maxX + grab, p.y > v.minY - grab, p.y < v.maxY + grab else { return 0 }
        var e = 0
        let dl = abs(p.x - v.minX), dr = abs(p.x - v.maxX), dt = abs(p.y - v.minY), db = abs(p.y - v.maxY)
        if min(dl, dr) < grab { e |= dl <= dr ? 1 : 4 }
        if min(dt, db) < grab { e |= dt <= db ? 2 : 8 }
        return e
    }

    private func dragChanged(_ value: DragGesture.Value, imageRect: CGRect, size: CGSize) {
        if pinchStart != nil { return }
        if dragMode == nil {
            dragStartSelection = selection
            if movingImage { dragMode = .pan; panStart = pan }
            else if let sel = selection.map({ toView($0, in: imageRect) }) {
                let e = edges(at: value.startLocation, of: sel)
                dragMode = e != 0 ? .resize(e) : sel.contains(value.startLocation) ? .move : .new
            } else { dragMode = .new }
        }
        let px = pixelSize
        switch dragMode {
        case .pan:
            guard let start = panStart else { return }
            pan = clampedPan(CGSize(width: start.width + value.translation.width, height: start.height + value.translation.height), in: size)
        case .new:
            let a = toPx(value.startLocation, in: imageRect), b = toPx(value.location, in: imageRect)
            selection = CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(b.x - a.x), height: abs(b.y - a.y))
        case .move:
            guard let start = dragStartSelection else { return }
            let k = px.width / imageRect.width
            var moved = start.offsetBy(dx: value.translation.width * k, dy: value.translation.height * k)
            moved.origin.x = min(max(moved.minX, 0), px.width - moved.width)
            moved.origin.y = min(max(moved.minY, 0), px.height - moved.height)
            selection = moved
        case .resize(let e):
            guard let start = dragStartSelection else { return }
            let p = toPx(value.location, in: imageRect)
            var l = start.minX, t = start.minY, r = start.maxX, b = start.maxY
            if e & 1 != 0 { l = p.x }; if e & 4 != 0 { r = p.x }
            if e & 2 != 0 { t = p.y }; if e & 8 != 0 { b = p.y }
            selection = CGRect(x: min(l, r), y: min(t, b), width: abs(r - l), height: abs(b - t))
        case .none: break
        }
    }

    private func save() {
        guard let selection, let cropped = crop(selection) else { return }
        let px = pixelSize
        let screenW = Int(screenWidth.trimmingCharacters(in: .whitespacesAndNewlines)) ?? Int(px.width)
        let screenH = Int(screenHeight.trimmingCharacters(in: .whitespacesAndNewlines)) ?? Int(px.height)
        guard screenW > 0, screenH > 0 else { return }
        onSave(CroppedTemplateImage(name: name.trimmingCharacters(in: .whitespacesAndNewlines), png: cropped.data, x: originX + cropped.x, y: originY + cropped.y, width: cropped.width, height: cropped.height, screenWidth: screenW, screenHeight: screenH))
        dismiss()
    }

    /// Recorta [pixels] (en píxeles de la captura).
    private func crop(_ pixels: CGRect) -> (data: Data, x: Int, y: Int, width: Int, height: Int)? {
        let px = pixelSize
        let rect = pixels.integral.intersection(CGRect(origin: .zero, size: px))
        guard rect.width >= 8, rect.height >= 8, let cg = normalized.cgImage?.cropping(to: rect) else { return nil }
        guard let data = UIImage(cgImage: cg, scale: 1, orientation: .up).pngData() else { return nil }
        return (data, Int(rect.minX), Int(rect.minY), cg.width, cg.height)
    }
}

/// Toca una captura de la pantalla entera para marcar dónde tocar (o de dónde a dónde deslizar)
/// y, de paso, qué color hay en ese punto.
struct PointPickerView: View {
    let image: UIImage
    /// 1 = un toque; 2 = deslizar (desde, hasta).
    let count: Int
    let onDone: ([MacroPoint], Int32) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var points: [CGPoint] = []   // en proporción 0…1
    private let normalized: UIImage

    init(image: UIImage, count: Int, onDone: @escaping ([MacroPoint], Int32) -> Void) {
        self.image = image; self.count = count; self.onDone = onDone
        self.normalized = ImageCropView.normalize(image)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 10) {
                Text(instruction).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                GeometryReader { proxy in
                    let rect = fitted(in: proxy.size)
                    ZStack {
                        Color.black
                        Image(uiImage: normalized).resizable().frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                        if points.count == 2 {
                            Path { p in
                                p.move(to: CGPoint(x: rect.minX + points[0].x * rect.width, y: rect.minY + points[0].y * rect.height))
                                p.addLine(to: CGPoint(x: rect.minX + points[1].x * rect.width, y: rect.minY + points[1].y * rect.height))
                            }.stroke(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [8, 5]))
                        }
                        ForEach(Array(points.enumerated()), id: \.offset) { index, point in
                            ZStack {
                                Circle().stroke(Color.white, lineWidth: 3).frame(width: 30, height: 30)
                                Circle().fill(Color.accentColor).frame(width: 12, height: 12)
                                if count == 2 { Text(index == 0 ? "1" : "2").font(.caption2.bold()).foregroundStyle(.white).offset(y: -24) }
                            }
                            .position(x: rect.minX + point.x * rect.width, y: rect.minY + point.y * rect.height)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .onTapGesture { location in
                        guard rect.contains(location) else { return }
                        let p = CGPoint(x: (location.x - rect.minX) / rect.width, y: (location.y - rect.minY) / rect.height)
                        if points.count >= count { points = [p] } else { points.append(p) }
                    }
                }
                if let first = points.first {
                    HStack(spacing: 8) {
                        Circle().fill(colorFromInt(color(at: first))).frame(width: 22, height: 22).overlay(Circle().stroke(.secondary))
                        Text("Punto: \(Int(first.x * 100)) % · \(Int(first.y * 100)) %").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                    }
                }
            }
            .padding()
            .navigationTitle(count == 2 ? "Marcar deslizamiento" : "Marcar punto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Listo") {
                        onDone(points.map { MacroPoint(x: Float($0.x), y: Float($0.y)) }, color(at: points[0])); dismiss()
                    }.bold().disabled(points.count < count)
                }
            }
        }
    }

    private var instruction: String {
        if count == 1 { return points.isEmpty ? "👆 Toca en la captura el sitio donde la macro tiene que tocar." : "Bien. Pulsa «Listo» o toca otro sitio para cambiarlo." }
        switch points.count {
        case 0: return "👆 Toca dónde EMPIEZA a deslizar el dedo."
        case 1: return "👆 Ahora toca dónde TERMINA."
        default: return "Bien. Pulsa «Listo» o toca de nuevo para empezar otra vez."
        }
    }

    private func fitted(in available: CGSize) -> CGRect {
        let w = CGFloat(normalized.cgImage?.width ?? 1), h = CGFloat(normalized.cgImage?.height ?? 1)
        let s = min(available.width / w, available.height / h)
        return CGRect(x: (available.width - w * s) / 2, y: (available.height - h * s) / 2, width: w * s, height: h * s)
    }

    /// Color del píxel en ese punto, como entero ARGB de Android.
    private func color(at point: CGPoint) -> Int32 {
        guard let cg = normalized.cgImage else { return -65536 }
        let x = min(max(Int(point.x * CGFloat(cg.width)), 0), cg.width - 1)
        let y = min(max(Int(point.y * CGFloat(cg.height)), 0), cg.height - 1)
        var pixel = [UInt8](repeating: 0, count: 4)
        guard let ctx = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return -65536 }
        ctx.draw(cg, in: CGRect(x: -x, y: y - cg.height + 1, width: cg.width, height: cg.height))
        return Int32(bitPattern: 0xFF00_0000 | UInt32(pixel[0]) << 16 | UInt32(pixel[1]) << 8 | UInt32(pixel[2]))
    }
}

func colorFromInt(_ value: Int32) -> Color {
    let number = UInt32(bitPattern: value)
    return Color(red: Double((number >> 16) & 0xFF) / 255, green: Double((number >> 8) & 0xFF) / 255, blue: Double(number & 0xFF) / 255)
}
