import SwiftUI

/// What the live Coach orb is doing.
enum LiveOrbMode: Equatable {
    case idle
    case listening
    case thinking
    case speaking
}

/// A holographic, voice-reactive orb for live voice Coach, drawn by the `liveOrb`
/// Metal shader. `level` is the loudness of whoever is talking, 0...1.
struct LiveOrbView: View {
    var mode: LiveOrbMode
    var level: Double
    /// The app theme colour (`AppColors.calorie`).
    var tint: Color

    @State private var motion = LiveOrbMotion()

    var body: some View {
        GeometryReader { proxy in
            TimelineView(.animation) { context in
                let frame = motion.advance(to: context.date, mode: mode, level: level)
                Rectangle()
                    .colorEffect(ShaderLibrary.liveOrb(
                        .float2(proxy.size.width, proxy.size.height),
                        .float(frame.time),
                        .float(frame.spin),
                        .float(frame.flow),
                        .float(frame.level),
                        .float(frame.active),
                        .float(frame.thinking),
                        .float(frame.speaking),
                        .color(tint)
                    ))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Eases the orb between moods, and keeps its rotation continuous when its speed
/// changes (the shader only sees phases, never speeds).
final class LiveOrbMotion {
    struct Frame {
        var time: Float = 0
        var spin: Float = 0
        var flow: Float = 0
        var level: Float = 0
        var active: Float = 0
        var thinking: Float = 0
        var speaking: Float = 0
    }

    private let start = Date()
    private var last: Date?
    private var frame = Frame()

    func advance(to date: Date, mode: LiveOrbMode, level: Double) -> Frame {
        let dt = Float(min(max(date.timeIntervalSince(last ?? date), 0), 0.1))
        last = date

        func approach(_ value: inout Float, _ target: Float, rate: Float) {
            value += (target - value) * (1 - exp(-rate * dt))
        }
        let loudness = Float(min(max(level, 0), 1))
        // Rise fast with the voice, fall back more slowly, like a VU meter.
        approach(&frame.level, loudness, rate: loudness > frame.level ? 24 : 7)
        approach(&frame.active, mode == .idle ? 0 : 1, rate: 3)
        approach(&frame.thinking, mode == .thinking ? 1 : 0, rate: 4)
        approach(&frame.speaking, mode == .speaking ? 1 : 0, rate: 4)

        frame.spin += dt * (0.22 + 0.18 * frame.active + 1.5 * frame.thinking + 0.6 * frame.level)
        frame.flow += dt * (0.25 + 0.3 * frame.active + 0.5 * frame.thinking + 2.4 * frame.level)
        frame.time = Float(date.timeIntervalSince(start))
        return frame
    }
}
