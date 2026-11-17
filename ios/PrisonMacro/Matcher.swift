import UIKit

/// La misma búsqueda de imágenes que hace Android (vision/Matcher.kt y BitmapDetector.kt),
/// para comprobar en el iPhone, sobre una captura, si Android encontraría la imagen.
/// Parecido = 1 − (diferencia media de color / 765).
enum TemplateMatcher {
    struct Pixels {
        let width: Int
        let height: Int
        let colors: [UInt32]   // 0x00RRGGBB
    }
    struct Match { let x: Int; let y: Int; let confidence: Double }

    /// Píxeles de una imagen, redimensionada a `size` si se pide.
    static func pixels(_ image: UIImage, size: CGSize? = nil) -> Pixels? {
        guard let cg = ImageCropView.normalize(image).cgImage else { return nil }
        let w = Int(size?.width ?? CGFloat(cg.width)), h = Int(size?.height ?? CGFloat(cg.height))
        guard w > 0, h > 0 else { return nil }
        var raw = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &raw, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var colors = [UInt32](repeating: 0, count: w * h)
        for i in 0..<(w * h) { colors[i] = UInt32(raw[i * 4]) << 16 | UInt32(raw[i * 4 + 1]) << 8 | UInt32(raw[i * 4 + 2]) }
        return Pixels(width: w, height: h, colors: colors)
    }

    @inline(__always) private static func distance(_ a: UInt32, _ b: UInt32) -> Int {
        abs(Int((a >> 16) & 255) - Int((b >> 16) & 255)) + abs(Int((a >> 8) & 255) - Int((b >> 8) & 255)) + abs(Int(a & 255) - Int(b & 255))
    }

    static func isDistinctive(_ image: Pixels) -> Bool {
        var sum = [Double](repeating: 0, count: 3), squares = [Double](repeating: 0, count: 3), count = 0.0
        let stride = max(1, image.colors.count / 4096)
        for i in Swift.stride(from: 0, to: image.colors.count, by: stride) {
            count += 1
            for c in 0..<3 { let v = Double((image.colors[i] >> UInt32(c * 8)) & 255); sum[c] += v; squares[c] += v * v }
        }
        return (0..<3).contains { squares[$0] / count - (sum[$0] / count) * (sum[$0] / count) >= 100 }
    }

    static func score(_ screen: Pixels, _ template: Pixels, _ x: Int, _ y: Int, stride: Int = 1) -> Double {
        if x < 0 || y < 0 || x + template.width > screen.width || y + template.height > screen.height { return 0 }
        var error = 0, count = 0
        for ty in Swift.stride(from: 0, to: template.height, by: stride) {
            for tx in Swift.stride(from: 0, to: template.width, by: stride) {
                error += distance(screen.colors[(y + ty) * screen.width + x + tx], template.colors[ty * template.width + tx]); count += 1
            }
        }
        return 1 - Double(error) / (Double(count) * 765)
    }

    static func find(_ screen: Pixels, _ template: Pixels) -> Match? {
        if template.width > screen.width || template.height > screen.height { return nil }
        var candidates: [Match] = []
        for y in 0...(screen.height - template.height) {
            for x in 0...(screen.width - template.width) {
                var error = 0
                for gy in 0...4 { for gx in 0...4 {
                    let tx = gx * (template.width - 1) / 4, ty = gy * (template.height - 1) / 4
                    error += distance(screen.colors[(y + ty) * screen.width + x + tx], template.colors[ty * template.width + tx])
                } }
                let confidence = 1 - Double(error) / 19125
                if candidates.count < 24 || confidence > candidates.last!.confidence {
                    candidates.append(Match(x: x, y: y, confidence: confidence))
                    candidates.sort { $0.confidence > $1.confidence }
                    if candidates.count > 24 { candidates.removeLast() }
                }
            }
        }
        return candidates.map { Match(x: $0.x, y: $0.y, confidence: score(screen, template, $0.x, $0.y)) }.max { $0.confidence < $1.confidence }
    }

    enum Outcome {
        case found(Match, needed: Double)
        case notFound(Match?, needed: Double)
        case problem(String)
    }

    /// Busca `template` en `screenshot` como lo haría Android con los ajustes de la macro.
    static func test(screenshot: UIImage, template saved: MacroTemplate, templateImage: UIImage, mode: MacroSearchMode, threshold: Double) -> (Outcome, screenSize: CGSize) {
        let target = CGSize(width: saved.screenWidth, height: saved.screenHeight)
        let shot = ImageCropView.normalize(screenshot)
        let sw = CGFloat(shot.cgImage?.width ?? 0), sh = CGFloat(shot.cgImage?.height ?? 0)
        guard sw > 0, sh > 0 else { return (.problem("No se pudo leer la captura."), target) }
        // Si la captura es de la misma pantalla pero a otro tamaño (misma forma), se ajusta.
        if abs(sw / sh - target.width / target.height) > 0.02 {
            return (.problem("Esta captura tiene otra forma (\(Int(sw))×\(Int(sh))). La imagen se recortó de una pantalla de \(saved.screenWidth)×\(saved.screenHeight): usa una captura de la pantalla entera de ese mismo aparato."), target)
        }
        guard let full = pixels(shot, size: target), let tpl = pixels(templateImage) else { return (.problem("No se pudo leer la imagen."), target) }
        guard tpl.width == saved.width, tpl.height == saved.height else { return (.problem("La imagen guardada no coincide con su tamaño."), target) }
        guard isDistinctive(tpl) else { return (.problem("La imagen es casi de un solo color: Android no la acepta. Recórtala con algo de texto, bordes o detalles."), target) }
        let result: Match?
        if mode == .EXACT {
            result = Match(x: saved.x, y: saved.y, confidence: score(full, tpl, saved.x, saved.y))
        } else {
            let scale = min(1.0, 480.0 / Double(max(full.width, full.height)))
            let tw = Int((Double(tpl.width) * scale).rounded()), th = Int((Double(tpl.height) * scale).rounded())
            guard tw >= 3, th >= 3 else { return (.problem("La imagen es demasiado pequeña: recórtala un poco más grande."), target) }
            guard let small = pixels(shot, size: CGSize(width: (Double(full.width) * scale).rounded(), height: (Double(full.height) * scale).rounded())),
                  let smallT = pixels(templateImage, size: CGSize(width: tw, height: th)),
                  let coarse = find(small, smallT) else { return (.notFound(nil, needed: threshold), target) }
            let cx = Int((Double(coarse.x) / scale).rounded()), cy = Int((Double(coarse.y) / scale).rounded()), radius = Int(ceil(2 / scale))
            let stride = max(1, min(tpl.width, tpl.height) / 40)
            var best: [Match] = []
            for y in max(0, cy - radius)...max(0, min(full.height - tpl.height, cy + radius)) {
                for x in max(0, cx - radius)...max(0, min(full.width - tpl.width, cx + radius)) {
                    let s = score(full, tpl, x, y, stride: stride)
                    if best.count < 3 || s > best.last!.confidence { best.append(Match(x: x, y: y, confidence: s)); best.sort { $0.confidence > $1.confidence }; if best.count > 3 { best.removeLast() } }
                }
            }
            result = best.map { Match(x: $0.x, y: $0.y, confidence: score(full, tpl, $0.x, $0.y)) }.max { $0.confidence < $1.confidence }
        }
        guard let result else { return (.notFound(nil, needed: threshold), target) }
        return (result.confidence >= threshold ? .found(result, needed: threshold) : .notFound(result, needed: threshold), target)
    }
}
