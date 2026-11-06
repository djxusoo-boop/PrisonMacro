import SwiftUI
import UIKit

struct CroppedTemplateImage {
    let png: Data
    let x: Int
    let y: Int
    let width: Int
    let height: Int
    let screenWidth: Int
    let screenHeight: Int
}

/// A small, explicit cropper for screenshots imported on the iPhone.
/// The iPhone cannot capture BlueStacks directly, so the source is selected
/// from Photos/Files and the resulting PNG is stored in the portable schema.
struct ImageCropView: View {
    let image: UIImage
    let onSave: (CroppedTemplateImage) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selection: CGRect?
    @State private var dragStart: CGPoint?
    @State private var imageDisplayRect: CGRect = .zero
    @State private var screenWidth: String
    @State private var screenHeight: String
    private let originX: Int
    private let originY: Int

    init(image: UIImage, screenWidth: Int? = nil, screenHeight: Int? = nil, originX: Int = 0, originY: Int = 0, onSave: @escaping (CroppedTemplateImage) -> Void) {
        self.image = image
        self.onSave = onSave
        self.originX = originX
        self.originY = originY
        let width = image.cgImage?.width ?? Int(image.size.width)
        let height = image.cgImage?.height ?? Int(image.size.height)
        _screenWidth = State(initialValue: String(screenWidth ?? width))
        _screenHeight = State(initialValue: String(screenHeight ?? height))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text("Arrastra sobre la captura para elegir la imagen que detectará Android.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                GeometryReader { proxy in
                    let imageRect = fittedRect(in: proxy.size)
                    ZStack {
                        Color.black
                        Image(uiImage: normalizedImage)
                            .resizable()
                            .frame(width: imageRect.width, height: imageRect.height)
                            .position(x: imageRect.midX, y: imageRect.midY)

                        if let selection {
                            Rectangle()
                                .fill(Color.accentColor.opacity(0.22))
                                .frame(width: selection.width, height: selection.height)
                                .position(x: selection.midX, y: selection.midY)
                            Rectangle()
                                .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [7, 4]))
                                .frame(width: selection.width, height: selection.height)
                                .position(x: selection.midX, y: selection.midY)
                        }
                    }
                    .onAppear { imageDisplayRect = imageRect }
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 1)
                            .onChanged { value in
                                imageDisplayRect = imageRect
                                let start = dragStart ?? value.startLocation
                                if dragStart == nil { dragStart = start }
                                selection = makeSelection(from: start, to: value.location, inside: imageRect)
                            }
                            .onEnded { _ in dragStart = nil }
                    )
                }
                .frame(minHeight: 300)

                HStack {
                    TextField("Ancho de pantalla", text: $screenWidth)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                    Text("×")
                    TextField("Alto de pantalla", text: $screenHeight)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                }
                Text("Usa las medidas de la pantalla donde se ejecutará la macro, no las del recorte.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding()
            .navigationTitle("Recortar captura")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") { save() }
                        .disabled(selection == nil)
                }
            }
        }
    }

    private var normalizedImage: UIImage {
        guard image.imageOrientation != .up else { return image }
        UIGraphicsBeginImageContextWithOptions(image.size, false, image.scale)
        image.draw(in: CGRect(origin: .zero, size: image.size))
        let result = UIGraphicsGetImageFromCurrentImageContext() ?? image
        UIGraphicsEndImageContext()
        return result
    }

    private func fittedRect(in available: CGSize) -> CGRect {
        let width = CGFloat(normalizedImage.cgImage?.width ?? Int(normalizedImage.size.width))
        let height = CGFloat(normalizedImage.cgImage?.height ?? Int(normalizedImage.size.height))
        let scale = min(available.width / max(width, 1), available.height / max(height, 1))
        let size = CGSize(width: width * scale, height: height * scale)
        return CGRect(x: (available.width - size.width) / 2, y: (available.height - size.height) / 2, width: size.width, height: size.height)
    }

    private func makeSelection(from start: CGPoint, to end: CGPoint, inside bounds: CGRect) -> CGRect {
        let first = CGPoint(x: min(max(start.x, bounds.minX), bounds.maxX), y: min(max(start.y, bounds.minY), bounds.maxY))
        let last = CGPoint(x: min(max(end.x, bounds.minX), bounds.maxX), y: min(max(end.y, bounds.minY), bounds.maxY))
        return CGRect(x: min(first.x, last.x), y: min(first.y, last.y), width: abs(last.x - first.x), height: abs(last.y - first.y))
    }

    private func save() {
        guard let selection, selection.width >= 2, selection.height >= 2 else { return }
        let sourceWidth = CGFloat(normalizedImage.cgImage?.width ?? Int(normalizedImage.size.width))
        let sourceHeight = CGFloat(normalizedImage.cgImage?.height ?? Int(normalizedImage.size.height))
        guard imageDisplayRect.width > 0, imageDisplayRect.height > 0,
              let cropped = crop(selection, inside: imageDisplayRect) else { return }
        let screenW = Int(screenWidth.trimmingCharacters(in: .whitespacesAndNewlines)) ?? Int(sourceWidth)
        let screenH = Int(screenHeight.trimmingCharacters(in: .whitespacesAndNewlines)) ?? Int(sourceHeight)
        guard screenW > 0, screenH > 0 else { return }
        onSave(CroppedTemplateImage(png: cropped.data, x: originX + cropped.x, y: originY + cropped.y, width: cropped.width, height: cropped.height, screenWidth: screenW, screenHeight: screenH))
        dismiss()
    }

    private func crop(_ displaySelection: CGRect, inside fitted: CGRect) -> (data: Data, x: Int, y: Int, width: Int, height: Int)? {
        // Convert the visible rectangle to source pixels. The screen dimensions
        // are metadata; the PNG itself contains only the selected pixels.
        guard fitted.width > 0, fitted.height > 0 else { return nil }
        let pixelWidth = CGFloat(normalizedImage.cgImage?.width ?? Int(normalizedImage.size.width))
        let pixelHeight = CGFloat(normalizedImage.cgImage?.height ?? Int(normalizedImage.size.height))
        let rect = CGRect(
            x: (displaySelection.minX - fitted.minX) / fitted.width * pixelWidth,
            y: (displaySelection.minY - fitted.minY) / fitted.height * pixelHeight,
            width: displaySelection.width / fitted.width * pixelWidth,
            height: displaySelection.height / fitted.height * pixelHeight
        ).integral
        guard rect.minX >= 0, rect.minY >= 0, rect.maxX <= pixelWidth, rect.maxY <= pixelHeight, rect.width >= 2, rect.height >= 2,
              let cgImage = normalizedImage.cgImage?.cropping(to: rect) else { return nil }
        let result = UIImage(cgImage: cgImage, scale: 1, orientation: .up)
        guard let data = result.pngData() else { return nil }
        return (data, Int(rect.minX), Int(rect.minY), cgImage.width, cgImage.height)
    }
}
