import SwiftUI

/// The orb on Home that opens the voice assistant. It animates gently while Home
/// is on screen and stops drawing otherwise.
struct VoiceAssistantHomeButton: View {
    let controller: VoiceAssistantController
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isVisible = false

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            controller.start()
        } label: {
            LiveOrbView(
                mode: .idle,
                tint: AppColors.calorie,
                isPaused: !isVisible || scenePhase != .active || reduceMotion,
                minimumFrameInterval: 1.0 / 30
            )
            .frame(width: 60, height: 60)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(AppColors.calorie.opacity(0.45), lineWidth: 1))
            .shadow(color: AppColors.calorie.opacity(0.35), radius: 10, y: 3)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Talk to Fud AI"))
        .accessibilityHint(Text("Starts the voice assistant"))
        .accessibilityIdentifier("voiceAssistantButton")
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
    }
}
