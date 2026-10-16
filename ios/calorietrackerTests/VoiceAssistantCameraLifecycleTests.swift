import AVFoundation
import Testing
import UIKit
@testable import calorietracker

@MainActor
struct VoiceAssistantCameraLifecycleTests {
    @Test func minimizingStopsCaptureAndDiscardsFrames() {
        let camera = StubVoiceCamera()
        let controller = VoiceAssistantController(camera: camera)
        camera.frame = Data([1, 2, 3])
        controller.minimize()
        #expect(!controller.isExpanded)
        #expect(camera.stops == 1)
        #expect(camera.latestJPEG() == nil)

        // Navigation and any future direct collapse must keep the same invariant.
        controller.isExpanded = true
        camera.frame = Data([4, 5])
        controller.isExpanded = false
        #expect(camera.stops == 2)
        #expect(camera.latestJPEG() == nil)
    }

    @Test func staleBarcodeCancellationAndTimeoutCannotFinishReplacement() async {
        let scan = VoiceAssistantBarcodeRequest()
        let first = scan.begin()
        let oldWait = Task { await scan.wait(for: first) }
        await Task.yield()
        let second = scan.begin()
        let newWait = Task { await scan.wait(for: second) }
        await Task.yield()
        scan.finish(.cancelled, request: first)
        scan.finish(.timeout, request: first)
        scan.finish(.value("123456789"), request: second)
        if case .cancelled = await oldWait.value {} else { Issue.record("Old scan should be cancelled") }
        if case .value(let code) = await newWait.value {
            #expect(code == "123456789")
        } else { Issue.record("Old callbacks cancelled the new scan") }
    }

    @Test func barcodeCancellationBeforeContinuationInstallationIsRetained() async {
        let scan = VoiceAssistantBarcodeRequest()
        let request = scan.begin()
        scan.finish(.cancelled, request: request)
        if case .cancelled = await scan.wait(for: request) {} else { Issue.record("Lost cancellation") }
    }
}

nonisolated private final class StubVoiceCamera: VoiceAssistantCameraIO, @unchecked Sendable {
    let session = AVCaptureSession()
    var onBarcode: (@Sendable (String) -> Void)?
    var frame: Data?
    var stops = 0
    func start(mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult { .started }
    func setMode(_ mode: VoiceAssistantCamera.Mode) {}
    func stop() { stops += 1; frame = nil }
    func latestJPEG() -> Data? { frame }
    func captureStill(timeout: TimeInterval) async -> UIImage? { nil }
}
