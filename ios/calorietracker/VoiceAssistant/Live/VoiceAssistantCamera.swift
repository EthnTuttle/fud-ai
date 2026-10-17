@preconcurrency import AVFoundation
import CoreImage
import SwiftUI
import UIKit
import os

nonisolated private let logger = Logger(subsystem: "com.apoorvdarshan.calorietracker", category: "VoiceAssistantCamera")

nonisolated protocol VoiceAssistantCameraIO: AnyObject, Sendable {
    var session: AVCaptureSession { get }
    var onBarcode: (@Sendable (String) -> Void)? { get set }
    func start(mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult
    func setMode(_ mode: VoiceAssistantCamera.Mode)
    func stop()
    func latestJPEG() -> Data?
    func captureStill(timeout: TimeInterval) async -> UIImage?
}

/// The back camera inside the voice assistant. It shows a live preview, keeps the
/// latest frame as a small JPEG for the model, takes full-size stills for food
/// analysis, and reads barcodes in barcode mode. Nothing is recorded; a still is
/// kept only when the user logs the food it shows.
nonisolated final class VoiceAssistantCamera: NSObject, VoiceAssistantCameraIO, AVCaptureVideoDataOutputSampleBufferDelegate,
    AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    enum Mode: String, Sendable {
        case food
        case barcode
    }

    enum StartResult: Sendable {
        case started
        case denied
        case unavailable
    }

    /// Frames for the model: at most this long on the longer side (contract: ≤ 768 px).
    static let frameMaximumSide: CGFloat = 768
    /// Model frames are encoded at most this often (seconds); they are sent less often.
    static let frameEncodeInterval: TimeInterval = 0.5

    static let barcodeTypes: [AVMetadataObject.ObjectType] = [
        .ean13, .ean8, .upce, .code128, .code39, .code93, .itf14, .interleaved2of5,
    ]

    let session = AVCaptureSession()

    /// Called on the main queue with each new barcode while in barcode mode.
    var onBarcode: (@Sendable (String) -> Void)?

    private let queue = DispatchQueue(label: "com.apoorvdarshan.calorietracker.voiceassistant.camera")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    private let context = CIContext()
    private let lock = NSLock()
    private var latestFrame: Data?
    private var lastEncodedAt: TimeInterval = 0
    private var stillWaiters: [UUID: CheckedContinuation<CGImage?, Never>] = [:]
    private var mode: Mode = .food
    private var isConfigured = false
    private var lastBarcode: (code: String, at: TimeInterval)?
    private var generation = 0

    static var isDenied: Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        return status == .denied || status == .restricted
    }

    /// Asks for access when needed, then starts the camera in `mode`.
    func start(mode: Mode) async -> StartResult {
        let number = lock.withLock {
            generation += 1
            return generation
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            break
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .video) else { return .denied }
        default:
            return .denied
        }
        let device = await MainActor.run { BarcodeScannerCameraSelection.preferredBackVideoCaptureDevice() }
        return await withCheckedContinuation { continuation in
            queue.async { [self] in
                guard lock.withLock({ number == generation }) else {
                    continuation.resume(returning: .unavailable)
                    return
                }
                lock.withLock { self.mode = mode }
                if !isConfigured {
                    configure(device: device)
                }
                guard isConfigured else {
                    continuation.resume(returning: .unavailable)
                    return
                }
                applyMode(mode)
                if !session.isRunning {
                    session.startRunning()
                    logger.info("[Camera] Started")
                }
                continuation.resume(returning: session.isRunning ? .started : .unavailable)
            }
        }
    }

    func setMode(_ mode: Mode) {
        queue.async { [self] in
            lock.withLock {
                self.mode = mode
                lastBarcode = nil
            }
            guard isConfigured else { return }
            applyMode(mode)
        }
    }

    func stop() {
        lock.withLock { generation += 1 }
        queue.async { [self] in
            if session.isRunning {
                session.stopRunning()
                logger.info("[Camera] Stopped")
            }
            let waiters: [CheckedContinuation<CGImage?, Never>] = lock.withLock {
                latestFrame = nil
                lastBarcode = nil
                defer { stillWaiters.removeAll() }
                return Array(stillWaiters.values)
            }
            for waiter in waiters { waiter.resume(returning: nil) }
        }
    }

    /// The most recent frame for the model as JPEG, or nil if none arrived yet.
    func latestJPEG() -> Data? {
        lock.withLock { latestFrame }
    }

    /// A full-size still from the next frame, or nil if none arrives in time.
    func captureStill(timeout: TimeInterval = 3) async -> UIImage? {
        let token = UUID()
        let image: CGImage? = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                let cancelled = lock.withLock {
                    // Cancellation can arrive before this waiter is installed.
                    guard !Task.isCancelled else { return true }
                    stillWaiters[token] = continuation
                    return false
                }
                if cancelled {
                    continuation.resume(returning: nil)
                    return
                }
                queue.asyncAfter(deadline: .now() + timeout) { [self] in
                    let waiter = lock.withLock { stillWaiters.removeValue(forKey: token) }
                    waiter?.resume(returning: nil)
                }
            }
        } onCancel: { [self] in
            let waiter = lock.withLock { stillWaiters.removeValue(forKey: token) }
            waiter?.resume(returning: nil)
        }
        return Task.isCancelled ? nil : image.map { UIImage(cgImage: $0) }
    }

    // MARK: - Configuration (camera queue)

    private func configure(device: AVCaptureDevice?) {
        // The live conversation owns the audio session; the camera must not change it.
        session.automaticallyConfiguresApplicationAudioSession = false
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.hd1920x1080) {
            session.sessionPreset = .hd1920x1080
        } else {
            session.sessionPreset = .high
        }

        guard let device,
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            logger.error("[Camera] The back camera isn't available")
            return
        }
        session.addInput(input)
        configureFocus(device)

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(videoOutput) else {
            logger.error("[Camera] Can't add the video output")
            return
        }
        session.addOutput(videoOutput)
        if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            // Portrait, as the user holds the phone.
            connection.videoRotationAngle = 90
        }

        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: queue)
        }
        isConfigured = true
    }

    private func applyMode(_ mode: Mode) {
        guard session.outputs.contains(metadataOutput) else { return }
        let available = Self.barcodeTypes.filter { metadataOutput.availableMetadataObjectTypes.contains($0) }
        metadataOutput.metadataObjectTypes = mode == .barcode ? available : []
    }

    /// Close focus helps barcodes and plates alike; the same tuning as the barcode scanner.
    private func configureFocus(_ camera: AVCaptureDevice) {
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            if camera.isFocusModeSupported(.continuousAutoFocus) {
                camera.focusMode = .continuousAutoFocus
            }
            if camera.isAutoFocusRangeRestrictionSupported {
                camera.autoFocusRangeRestriction = .near
            }
            if camera.isSmoothAutoFocusSupported {
                camera.isSmoothAutoFocusEnabled = true
            }
            if camera.isExposureModeSupported(.continuousAutoExposure) {
                camera.exposureMode = .continuousAutoExposure
            }
        } catch {
            // The camera still works without the tuning.
        }
    }

    // MARK: - Delegates (camera queue)

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let image = CIImage(cvPixelBuffer: pixelBuffer)

        let waiters: [CheckedContinuation<CGImage?, Never>] = lock.withLock {
            defer { stillWaiters.removeAll() }
            return Array(stillWaiters.values)
        }
        if !waiters.isEmpty {
            let still = context.createCGImage(image, from: image.extent)
            for waiter in waiters { waiter.resume(returning: still) }
        }

        let now = CACurrentMediaTime()
        guard now - lastEncodedAt >= Self.frameEncodeInterval else { return }
        lastEncodedAt = now
        var frame = image
        let scale = min(1, Self.frameMaximumSide / max(frame.extent.width, frame.extent.height))
        if scale < 1 {
            frame = frame.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }
        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let jpeg = context.jpegRepresentation(
                of: frame,
                colorSpace: colorSpace,
                options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.7]
              ) else { return }
        lock.withLock { latestFrame = jpeg }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !code.isEmpty else { return }
        let now = CACurrentMediaTime()
        let isNew: Bool = lock.withLock {
            guard mode == .barcode else { return false }
            // The same code stays in view for a while; report it once.
            if let lastBarcode, lastBarcode.code == code, now - lastBarcode.at < 5 { return false }
            lastBarcode = (code, now)
            return true
        }
        guard isNew, let onBarcode else { return }
        DispatchQueue.main.async { onBarcode(code) }
    }
}

// MARK: - Preview

/// The live preview of the assistant's camera.
struct VoiceAssistantCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        var previewLayer: AVCaptureVideoPreviewLayer {
            // The layer class above guarantees the type.
            layer as! AVCaptureVideoPreviewLayer
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
        }
    }
}
