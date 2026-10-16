import Accelerate
@preconcurrency import AVFoundation
import SwiftUI

// Prototype for live voice Coach. Only development installs show it (see
// `isAvailable`), so its text is not localized yet.

/// Opens listening: talk, pause, and the orb thinks, then says a sample Coach line
/// out loud. Tapping the orb pauses or resumes. No AI is involved yet.
struct LiveOrbPreviewScreen: View {
    /// True for development installs, which carry a provisioning profile; App Store
    /// and TestFlight builds don't.
    static var isAvailable: Bool {
        Bundle.main.path(forResource: "embedded", ofType: "mobileprovision") != nil
    }

    @Environment(\.dismiss) private var dismiss
    @State private var demo = LiveOrbDemo()

    var body: some View {
        ZStack {
            Color(red: 0.006, green: 0.008, blue: 0.014).ignoresSafeArea()

            LiveOrbView(mode: demo.mode, level: demo.level, tint: AppColors.calorie)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { demo.toggle() }

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.8))
                            .frame(width: 44, height: 44)
                            .background(.white.opacity(0.08), in: Circle())
                    }
                    .accessibilityLabel(Text(verbatim: "Close"))
                }
                .padding(.horizontal, 16)

                Spacer()

                Text(verbatim: demo.caption)
                    .font(.system(size: 17, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.75))
                    .multilineTextAlignment(.center)
                    .contentTransition(.opacity)
                    .animation(.easeInOut(duration: 0.25), value: demo.caption)
                    .padding(.horizontal, 28)
                    .padding(.bottom, 40)
            }
        }
        .statusBarHidden()
        .onAppear { demo.start() }
        .onDisappear { demo.stop() }
    }
}


/// Runs a pretend conversation: listens until the user talks and pauses,
/// "thinks", then speaks a sample Coach line with the system voice.
@Observable
final class LiveOrbDemo {
    private(set) var mode: LiveOrbMode = .idle
    private(set) var level: Double = 0
    private(set) var reply = ""
    private(set) var micUnavailable = false

    /// Loudness above which the user counts as talking.
    static let speechThreshold = 0.32
    /// How long the user must pause before the orb answers.
    static let pauseBeforeReply: TimeInterval = 0.9
    static let minimumThinking: TimeInterval = 0.8

    static let sampleReplies = [
        "Nice work today. You have about seven hundred calories left, so a protein heavy dinner would fit perfectly.",
        "Your weight is trending down about half a pound a week. That is right on pace for your goal.",
        "You hit your protein target four days in a row. Keep that streak going!",
        "Grilled chicken with rice and veggies would land you right on your macros tonight.",
    ]

    @ObservationIgnored private let audio = LiveOrbAudio()
    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var replyIndex = 0
    @ObservationIgnored private var replyFinished = false

    var caption: String {
        if micUnavailable { return "Microphone unavailable" }
        switch mode {
        case .idle: return "Paused. Tap the orb to talk"
        case .listening: return "Listening…"
        case .thinking: return "Thinking…"
        case .speaking: return reply
        }
    }

    func toggle() {
        if mode == .idle { start() } else { stop() }
    }

    func start() {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else {
                    self.micUnavailable = true
                    return
                }
                self.begin()
            }
        }
    }

    func stop() {
        loop?.cancel()
        loop = nil
        synthesizer.stopSpeaking(at: .immediate)
        audio.stop()
        mode = .idle
        level = 0
        reply = ""
    }

    private func begin() {
        guard loop == nil else { return }
        do {
            try audio.start()
        } catch {
            audio.stop()
            micUnavailable = true
            return
        }
        micUnavailable = false
        loop = Task { [weak self] in
            await self?.converse()
        }
    }

    private func converse() async {
        while !Task.isCancelled {
            mode = .listening
            var heardSpeech = false
            var quietSince = Date()
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                level = audio.microphoneLevel
                if level > Self.speechThreshold {
                    heardSpeech = true
                    quietSince = Date()
                }
                if heardSpeech, Date().timeIntervalSince(quietSince) > Self.pauseBeforeReply { break }
            }
            guard !Task.isCancelled else { return }

            mode = .thinking
            level = 0
            let thinkingStarted = Date()
            let line = Self.sampleReplies[replyIndex % Self.sampleReplies.count]
            replyIndex += 1
            let speech = await synthesize(line)
            let remaining = Self.minimumThinking - Date().timeIntervalSince(thinkingStarted)
            if remaining > 0 { try? await Task.sleep(for: .seconds(remaining)) }
            guard !Task.isCancelled, let speech else { continue }

            reply = line
            mode = .speaking
            replyFinished = false
            Task { [audio] in
                await audio.play(speech)
                self.replyFinished = true
            }
            while !Task.isCancelled, !replyFinished {
                level = audio.playbackLevel
                try? await Task.sleep(for: .milliseconds(16))
            }
            level = 0
        }
    }

    /// Renders `text` with the best installed English voice, without playing it.
    private func synthesize(_ text: String) async -> AVAudioPCMBuffer? {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = Self.bestVoice()
        let collector = LiveOrbSpeechCollector()
        return await withCheckedContinuation { continuation in
            collector.onFinish = { continuation.resume(returning: $0) }
            synthesizer.write(utterance) { @Sendable buffer in
                collector.receive(buffer)
            }
        }
    }

    private static func bestVoice() -> AVSpeechSynthesisVoice? {
        let english = AVSpeechSynthesisVoice.speechVoices().filter { $0.language == "en-US" }
        return english.first { $0.quality == .premium }
            ?? english.first { $0.quality == .enhanced }
            ?? AVSpeechSynthesisVoice(language: "en-US")
    }
}

/// Gathers the synthesizer's output into one buffer; a zero-length buffer ends it.
nonisolated final class LiveOrbSpeechCollector: @unchecked Sendable {
    var onFinish: ((AVAudioPCMBuffer?) -> Void)?
    private let lock = NSLock()
    private var parts: [AVAudioPCMBuffer] = []
    private var finished = false

    func receive(_ buffer: AVAudioBuffer) {
        let finish: ((AVAudioPCMBuffer?) -> Void)?
        let merged: AVAudioPCMBuffer?
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        if let pcm = buffer as? AVAudioPCMBuffer, pcm.frameLength > 0 {
            parts.append(pcm)
            lock.unlock()
            return
        }
        finished = true
        finish = onFinish
        merged = Self.merge(parts)
        lock.unlock()
        DispatchQueue.main.async { finish?(merged) }
    }

    private static func merge(_ parts: [AVAudioPCMBuffer]) -> AVAudioPCMBuffer? {
        guard let first = parts.first else { return nil }
        let total = parts.reduce(0) { $0 + Int($1.frameLength) }
        let bytesPerFrame = Int(first.format.streamDescription.pointee.mBytesPerFrame)
        guard let merged = AVAudioPCMBuffer(pcmFormat: first.format, frameCapacity: AVAudioFrameCount(total)),
              let destination = merged.mutableAudioBufferList.pointee.mBuffers.mData else { return nil }
        var offset = 0
        for part in parts where part.format == first.format {
            guard let source = part.audioBufferList.pointee.mBuffers.mData else { continue }
            let bytes = Int(part.frameLength) * bytesPerFrame
            memcpy(destination.advanced(by: offset), source, bytes)
            offset += bytes
        }
        merged.frameLength = AVAudioFrameCount(offset / max(bytesPerFrame, 1))
        return merged
    }
}

/// Microphone in, voice out, with echo cancellation, plus the loudness of each
/// (0 for silence, 1 for loud speech).
final class LiveOrbAudio {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let playbackFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24_000, channels: 1, interleaved: false)!
    private let micLevel = LiveOrbLevelBox()
    private let voiceLevel = LiveOrbLevelBox()

    var microphoneLevel: Double { micLevel.value }
    var playbackLevel: Double { voiceLevel.value }

    init() {
        engine.attach(player)
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        // Voice processing (echo cancellation) must be on before the engine is configured.
        try engine.inputNode.setVoiceProcessingEnabled(true)

        engine.connect(player, to: engine.mainMixerNode, format: playbackFormat)
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        let micLevel = micLevel
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { @Sendable buffer, _ in
            micLevel.value = Self.loudness(of: buffer)
        }
        player.removeTap(onBus: 0)
        let voiceLevel = voiceLevel
        player.installTap(onBus: 0, bufferSize: 1024, format: playbackFormat) { @Sendable buffer, _ in
            voiceLevel.value = Self.loudness(of: buffer)
        }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        player.stop()
        engine.inputNode.removeTap(onBus: 0)
        player.removeTap(onBus: 0)
        engine.stop()
        try? engine.inputNode.setVoiceProcessingEnabled(false)
        micLevel.value = 0
        voiceLevel.value = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Plays `speech` and returns once it has been heard, or playback stopped.
    func play(_ speech: AVAudioPCMBuffer) async {
        guard engine.isRunning, let buffer = convert(speech) else { return }
        player.play()
        _ = await player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack)
        voiceLevel.value = 0
    }

    private func convert(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        if buffer.format == playbackFormat { return buffer }
        guard let converter = AVAudioConverter(from: buffer.format, to: playbackFormat) else { return nil }
        let ratio = playbackFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 4_096
        guard let output = AVAudioPCMBuffer(pcmFormat: playbackFormat, frameCapacity: capacity) else { return nil }
        var supplied = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if supplied {
                status.pointee = .endOfStream
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        return error == nil ? output : nil
    }

    /// Maps -55 dBFS (room noise) ... -15 dBFS (talking close) onto 0...1.
    nonisolated static func loudness(of buffer: AVAudioPCMBuffer) -> Double {
        guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return 0 }
        var rms: Float = 0
        vDSP_rmsqv(samples, 1, &rms, vDSP_Length(buffer.frameLength))
        let decibels = 20 * log10(max(Double(rms), 1e-7))
        return min(max((decibels + 55) / 40, 0), 1)
    }
}

/// The latest level, written on an audio thread and read on the main one.
nonisolated final class LiveOrbLevelBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = 0.0

    var value: Double {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
