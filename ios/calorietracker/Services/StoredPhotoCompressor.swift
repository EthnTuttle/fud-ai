import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Keeps stored meal and exercise photos at the size the app can actually show.
///
/// The full-screen viewer never decodes more than `FoodImageStore.viewerMaxDimension`, so
/// pixels beyond it only cost storage and backup space. New photos are downscaled as they are
/// stored; existing ones only when the user confirms Compress Photos in Settings. Backup
/// restores and images already within the cap keep their original bytes.
nonisolated enum StoredPhotoCompressor {
    /// Longest side kept for stored photos (matches `FoodImageStore.viewerMaxDimension`).
    static let maxPixelSize = 2048
    static let jpegQuality = 0.8

    struct Result: Equatable, Sendable {
        var compressedCount = 0
        var unreadableCount = 0
        var bytesSaved: Int64 = 0
    }

    /// An upright JPEG capped at `maxPixelSize` on the longest side, or nil when the image is
    /// already within the cap or can't be decoded — callers then keep the original bytes.
    static func downscaledJPEGData(_ data: Data, maxPixelSize: Int = maxPixelSize) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            return nil
        }
        return downscaledJPEGData(from: source, maxPixelSize: maxPixelSize)
    }

    /// Longest side in pixels as stored (before orientation), or nil when unreadable.
    static func longestSide(of data: Data) -> Int? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            return nil
        }
        return longestSide(of: source)
    }

    /// Downscales photos in `folder` that exceed `maxPixelSize`, one file at a time inside an
    /// autorelease pool so only one bounded image is in memory. Each result is staged in a
    /// replacement directory and swapped in only when it is smaller and the original is
    /// unchanged; files that can't be decoded are left as they are. Filenames never change,
    /// and thumbnails stay valid because both versions decode upright.
    static func compressPhotos(
        in folder: URL,
        maxPixelSize: Int = maxPixelSize,
        progress: (_ done: Int, _ total: Int) -> Void = { _, _ in }
    ) -> Result {
        let files = ((try? FileManager.default.contentsOfDirectory(
            at: folder,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        )) ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }

        var result = Result()
        for (index, file) in files.enumerated() {
            let outcome = autoreleasepool { compressPhoto(at: file, maxPixelSize: maxPixelSize) }
            switch outcome {
            case .compressed(let saved):
                result.compressedCount += 1
                result.bytesSaved += saved
            case .unchanged:
                break
            case .unreadable:
                result.unreadableCount += 1
            }
            progress(index + 1, files.count)
        }
        return result
    }

    // MARK: - Private

    private enum Outcome {
        case compressed(Int64)
        case unchanged
        case unreadable
    }

    private struct FileSignature: Equatable {
        let size: Int64
        let modified: Date
    }

    private static func compressPhoto(at url: URL, maxPixelSize: Int) -> Outcome {
        let fileManager = FileManager.default
        guard let before = signature(of: url) else { return .unchanged }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let longest = longestSide(of: source) else {
            return .unreadable
        }
        guard longest > maxPixelSize else { return .unchanged }
        guard let data = downscaledJPEGData(from: source, maxPixelSize: maxPixelSize) else { return .unreadable }
        guard Int64(data.count) < before.size else { return .unchanged }

        do {
            let staging = try fileManager.url(
                for: .itemReplacementDirectory,
                in: .userDomainMask,
                appropriateFor: url,
                create: true
            )
            defer { try? fileManager.removeItem(at: staging) }
            let staged = staging.appendingPathComponent(url.lastPathComponent)
            try data.write(to: staged)
            // Skip if the photo was deleted, restored or rewritten while it was being encoded.
            guard signature(of: url) == before else { return .unchanged }
            _ = try fileManager.replaceItemAt(url, withItemAt: staged)
            return .compressed(before.size - Int64(data.count))
        } catch {
            return .unchanged
        }
    }

    private static func downscaledJPEGData(from source: CGImageSource, maxPixelSize: Int) -> Data? {
        guard let longest = longestSide(of: source), longest > maxPixelSize else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            // Bakes EXIF orientation into the pixels; the JPEG below carries no orientation tag.
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else {
            return nil
        }
        CGImageDestinationAddImage(
            destination,
            image,
            [kCGImageDestinationLossyCompressionQuality: jpegQuality] as CFDictionary
        )
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    private static func longestSide(of source: CGImageSource) -> Int? {
        guard CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              width > 0, height > 0 else {
            return nil
        }
        return max(width, height)
    }

    private static func signature(of url: URL) -> FileSignature? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = (attributes[.size] as? NSNumber)?.int64Value,
              let modified = attributes[.modificationDate] as? Date else {
            return nil
        }
        return FileSignature(size: size, modified: modified)
    }
}
