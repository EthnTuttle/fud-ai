import CoreGraphics
import ImageIO
import UIKit
import Vision

/// Reads nutrition label text on this iPhone with Vision so the on-device
/// photo models can use the printed numbers (#486). The text never leaves the
/// device: only Apple Intelligence and Gemma prompts include it.
enum NutritionLabelTextRecognizer {
    /// Label rows for each photo that looks like a nutrition label, in photo
    /// order. Empty when no photo does or recognition fails.
    static func labelText(in images: [UIImage]) async -> [NutritionLabelPhotoText] {
        var photos: [NutritionLabelPhotoText] = []
        for (index, image) in images.prefix(NutritionLabelText.maxPhotos).enumerated() {
            guard !Task.isCancelled else { return [] }
            guard let upright = uprightImage(for: image) else { continue }
            let lines = await Task.detached(priority: .userInitiated) {
                NutritionLabelText.lines(from: recognizeText(in: upright.image, orientation: upright.orientation))
            }.value
            if NutritionLabelText.isLikelyNutritionLabel(lines) {
                photos.append(NutritionLabelPhotoText(photoNumber: index + 1, lines: lines))
            }
        }
        return photos
    }

    /// Blocks until Vision finishes, so callers keep it off the main thread.
    nonisolated private static func recognizeText(
        in image: CGImage,
        orientation: CGImagePropertyOrientation
    ) -> [NutritionLabelText.Fragment] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        // Language correction rewrites digits and units into dictionary words.
        request.usesLanguageCorrection = false
        request.automaticallyDetectsLanguage = true
        let handler = VNImageRequestHandler(cgImage: image, orientation: orientation, options: [:])
        guard (try? handler.perform([request])) != nil else { return [] }
        return (request.results ?? []).compactMap { observation in
            observation.topCandidates(1).first.map {
                NutritionLabelText.Fragment(text: $0.string, box: observation.boundingBox)
            }
        }
    }

    /// The bitmap plus its EXIF orientation, so Vision's boxes are relative to
    /// the photo as the user sees it.
    private static func uprightImage(for image: UIImage) -> (image: CGImage, orientation: CGImagePropertyOrientation)? {
        if let cgImage = image.cgImage {
            return (cgImage, CGImagePropertyOrientation(image.imageOrientation))
        }
        guard image.size.width > 0, image.size.height > 0,
              let rendered = UIGraphicsImageRenderer(size: image.size).image(actions: { _ in
                  image.draw(at: .zero)
              }).cgImage else {
            return nil
        }
        return (rendered, .up)
    }
}

private extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
