import Foundation
import UIKit
import UserNotifications

/// Owns the one rest countdown that runs after a logged strength set. It lives
/// for the whole app session, so switching tabs or days keeps the countdown.
///
/// While Fud AI is open the end is marked with a haptic; a local notification
/// (only when the app's notifications are on) covers a rest that ends while the
/// app is in the background. The foreground presentation filter in AppDelegate
/// keeps that notification silent while the app is open.
@Observable
final class WorkoutRestTimerController {
    static let shared = WorkoutRestTimerController()
    static let notificationID = "workout.rest.complete"

    private(set) var timer: WorkoutRestTimer?
    @ObservationIgnored private var completionTask: Task<Void, Never>?

    func start(exerciseID: UUID, setID: UUID, exerciseName: String, durationSeconds: Int, now: Date = .now) {
        guard let next = WorkoutRestTimer(
            exerciseID: exerciseID,
            setID: setID,
            exerciseName: exerciseName,
            durationSeconds: durationSeconds,
            now: now
        ) else { return }
        replace(with: next)
    }

    /// +15 s.
    func extend(now: Date = .now) {
        guard var current = timer else { return }
        current.extend(now: now)
        replace(with: current)
    }

    func skip() {
        completionTask?.cancel()
        completionTask = nil
        timer = nil
        PendingNotificationRequests.remove([Self.notificationID])
    }

    private func replace(with next: WorkoutRestTimer) {
        timer = next
        scheduleNotification(for: next)
        completionTask?.cancel()
        let endsAt = next.endsAt
        completionTask = Task { [weak self] in
            let delay = endsAt.timeIntervalSinceNow
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled else { return }
            self?.finish(expected: next)
        }
    }

    /// Runs when the countdown reaches zero, or as soon as a suspended app
    /// resumes after it did.
    private func finish(expected: WorkoutRestTimer, now: Date = .now) {
        guard timer == expected, expected.isFinished(at: now) else { return }
        if UIApplication.shared.applicationState == .active {
            // Ended in front of the user: the haptic is the alert.
            PendingNotificationRequests.remove([Self.notificationID])
            if expected.shouldAlertInApp(at: now) {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
        }
        timer = nil
        completionTask = nil
    }

    private func scheduleNotification(for timer: WorkoutRestTimer) {
        PendingNotificationRequests.remove([Self.notificationID])
        guard UserDefaults.standard.bool(forKey: "notificationsEnabled") else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Rest Complete", comment: "Title of the notification sent when a workout rest countdown ends.")
        content.body = String(
            localized: "Time for your next set of \(timer.exerciseName).",
            comment: "Body of the rest timer notification. The argument is the exercise name, such as Bench Press."
        )
        content.sound = .default
        let message = content.copy() as! UNNotificationContent
        let endsAt = timer.endsAt
        let identifier = Self.notificationID

        // Measured when the request is added, so a slow earlier change cannot
        // push the alert past the end of the rest.
        PendingNotificationRequests.addWhenQueued {
            let interval = endsAt.timeIntervalSinceNow
            guard interval > 1 else { return nil }
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
            return UNNotificationRequest(identifier: identifier, content: message, trigger: trigger)
        }
    }
}
