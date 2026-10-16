import Foundation
import Testing
@testable import calorietracker

/// Setup, encoding and parsing for every live provider, including the hosted relay.
struct VoiceAssistantWireTests {
    private static let tools = [
        LiveToolDeclaration(
            name: "log_water",
            description: "Log water.",
            parametersJSON: #"{"type":"object","properties":{"amount":{"type":"number"},"unit":{"type":"string","enum":["ml","l"]}},"required":["amount","unit"]}"#
        ),
        LiveToolDeclaration(
            name: "get_app_state",
            description: "What the app shows.",
            parametersJSON: #"{"type":"object","properties":{}}"#
        ),
    ]

    private static func config(_ provider: LiveProvider, credential: LiveSessionConfig.Credential = .apiKey("secret-key")) -> LiveSessionConfig {
        LiveSessionConfig(
            provider: provider,
            model: provider.defaultModel,
            voice: provider.defaultVoice,
            credential: credential,
            instructions: "Be brief.",
            tools: tools
        )
    }

    private static func json(_ text: String) throws -> [String: Any] {
        try #require(LiveJSON.object(text))
    }

    // MARK: Gemini

    @Test func geminiKeepsTheKeyInAHeaderAndDeclaresTools() throws {
        let wire = GeminiLiveWire()
        let request = wire.request(for: Self.config(.gemini))
        #expect(request.value(forHTTPHeaderField: "x-goog-api-key") == "secret-key")
        #expect(request.url?.absoluteString.contains("secret-key") == false)

        let messages = wire.setupMessages(for: Self.config(.gemini), resumptionHandle: "handle-1")
        #expect(messages.count == 1)
        let setup = try #require(try Self.json(messages[0])["setup"] as? [String: Any])
        #expect(setup["model"] as? String == "models/gemini-3.8-live")
        let generation = try #require(setup["generationConfig"] as? [String: Any])
        #expect(Set(generation.keys) == ["responseModalities", "speechConfig"])
        let voice = ((generation["speechConfig"] as? [String: Any])?["voiceConfig"] as? [String: Any])?["prebuiltVoiceConfig"] as? [String: Any]
        #expect(voice?["voiceName"] as? String == "Kore")
        #expect((setup["sessionResumption"] as? [String: Any])?["handle"] as? String == "handle-1")

        let tools = try #require(setup["tools"] as? [[String: Any]])
        let declarations = try #require(tools.first?["functionDeclarations"] as? [[String: Any]])
        #expect(declarations.map { $0["name"] as? String } == ["log_water", "get_app_state"])
        // Only name, description and parameters; no parameters for a tool without arguments.
        #expect(Set(declarations[0].keys) == ["name", "description", "parameters"])
        #expect(Set(declarations[1].keys) == ["name", "description"])
    }

    @Test func geminiParsesToolCallsAndCancellations() throws {
        let wire = GeminiLiveWire()
        let events = wire.parse(#"{"toolCall":{"functionCalls":[{"id":"call-1","name":"log_water","args":{"amount":500,"unit":"ml"}},{"name":"get_app_state"}]}}"#)
        guard case .toolCalls(let calls) = try #require(events.first) else {
            Issue.record("Expected tool calls, got \(events)")
            return
        }
        #expect(calls.map(\.id) == ["call-1", "get_app_state"])
        #expect(calls[0].arguments["amount"] as? Int == 500)
        #expect(calls[0].arguments["unit"] as? String == "ml")
        #expect(calls[1].arguments.isEmpty)

        #expect(wire.parse(#"{"toolCallCancellation":{"ids":["call-1"]}}"#) == [.toolCallCancellation(["call-1"])])
    }

    @Test func geminiParsesAudioTranscriptsAndTurns() throws {
        let audio = Data([1, 2, 3, 4])
        let message = """
        {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"\(audio.base64EncodedString())"}}]},"inputTranscription":{"text":"two eggs"},"outputTranscription":{"text":"Got it"},"turnComplete":true}}
        """
        #expect(GeminiLiveWire().parse(message) == [
            .audio(audio, itemID: nil),
            .userText("two eggs", replacing: false),
            .assistantText("Got it"),
            .turnComplete,
        ])
        #expect(GeminiLiveWire().parse(#"{"setupComplete":{}}"#) == [.ready])
        #expect(GeminiLiveWire().parse(#"{"serverContent":{"interrupted":true}}"#) == [.interrupted])
    }

    @Test func geminiEncodesToolResponsesContextTextAndFrames() throws {
        let wire = GeminiLiveWire()
        let responses = wire.toolResponseMessages([
            LiveToolResponse(id: "call-1", name: "log_water", outputJSON: #"{"ok":true,"milliliters":500}"#),
        ])
        let response = try #require(try Self.json(responses[0])["toolResponse"] as? [String: Any])
        let function = try #require((response["functionResponses"] as? [[String: Any]])?.first)
        #expect(function["id"] as? String == "call-1")
        #expect(function["name"] as? String == "log_water")
        #expect((function["response"] as? [String: Any])?["ok"] as? Bool == true)

        let context = try #require(try Self.json(wire.contextTextMessages("[App] The user tapped Undo.")[0])["clientContent"] as? [String: Any])
        #expect(context["turnComplete"] as? Bool == true)
        let parts = (context["turns"] as? [[String: Any]])?.first?["parts"] as? [[String: Any]]
        #expect(parts?.first?["text"] as? String == "[App] The user tapped Undo.")

        let frame = try #require(try Self.json(wire.imageMessages(Data([9]))[0])["realtimeInput"] as? [String: Any])
        #expect((frame["video"] as? [String: Any])?["mimeType"] as? String == "image/jpeg")
        #expect(wire.responseCreateMessage() == nil)
    }

    // MARK: Hosted relay

    @Test func hostedRelayIdentifiesTheUserAndPlatformWithoutAKey() throws {
        let wire = HostedLiveWire()
        let config = Self.config(.hosted, credential: .hostedUser("$RCAnonymousID:abc"))
        let request = wire.request(for: config)
        #expect(request.url?.absoluteString == "wss://fud-ai.app/api/hosted-ai/v1/live")
        #expect(request.value(forHTTPHeaderField: "X-Fud-User-Id") == "$RCAnonymousID:abc")
        #expect(request.value(forHTTPHeaderField: "X-Fud-Platform") == "ios")
        #expect(request.value(forHTTPHeaderField: "x-goog-api-key") == nil)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)

        // The relay accepts only these setup fields.
        let setup = try #require(try Self.json(wire.setupMessages(for: config, resumptionHandle: nil)[0])["setup"] as? [String: Any])
        let allowed: Set<String> = [
            "model", "generationConfig", "systemInstruction", "tools", "inputAudioTranscription",
            "outputAudioTranscription", "contextWindowCompression", "sessionResumption", "realtimeInputConfig",
        ]
        #expect(Set(setup.keys).isSubset(of: allowed))
        #expect((setup["inputAudioTranscription"] as? [String: Any])?.isEmpty == true)
        #expect(wire.inputSampleRate == 16_000)
        #expect(wire.outputSampleRate == 24_000)
    }

    @Test func hostedRelayReadsFudVoiceMessages() {
        let wire = HostedLiveWire()
        #expect(wire.parse(#"{"fudVoice":{"secondsRemaining":1185}}"#) == [.hostedSecondsRemaining(1185)])
        #expect(wire.parse(#"{"fudVoice":{"error":"voice_quota_exceeded"}}"#) == [.hostedError(code: "voice_quota_exceeded", detail: nil)])
        #expect(wire.parse(#"{"fudVoice":{"error":"invalid_setup","detail":"tools"}}"#) == [.hostedError(code: "invalid_setup", detail: "tools")])
        // Gemini's own messages pass through unchanged.
        #expect(wire.parse(#"{"setupComplete":{}}"#) == [.ready])
    }

    // MARK: OpenAI

    @Test func openAIDeclaresToolsSeparatelyAndKeepsTheKeyOutOfTheURL() throws {
        let wire = OpenAIRealtimeWire()
        let request = wire.request(for: Self.config(.openai))
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer secret-key")
        #expect(request.url?.query == "model=gpt-realtime-2.1-mini")

        let messages = try wire.setupMessages(for: Self.config(.openai), resumptionHandle: nil).map(Self.json)
        #expect(messages.count == 3)
        let core = try #require(messages[0]["session"] as? [String: Any])
        #expect(core["type"] as? String == "realtime")
        #expect(core["instructions"] as? String == "Be brief.")
        let toolsSession = try #require(messages[1]["session"] as? [String: Any])
        #expect(toolsSession["tool_choice"] as? String == "auto")
        let tools = try #require(toolsSession["tools"] as? [[String: Any]])
        #expect(tools.count == 2)
        #expect(tools[0]["type"] as? String == "function")
        #expect(tools[0]["name"] as? String == "log_water")
        #expect((tools[1]["parameters"] as? [String: Any])?["type"] as? String == "object")
    }

    @Test func openAIParsesFunctionCallsAndResponseLifecycle() throws {
        let wire = OpenAIRealtimeWire()
        let call = wire.parse(#"{"type":"response.function_call_arguments.done","item_id":"item-1","call_id":"call_9","name":"log_water","arguments":"{\"amount\":2,\"unit\":\"cup\"}"}"#)
        guard case .toolCalls(let calls) = try #require(call.first) else {
            Issue.record("Expected a tool call, got \(call)")
            return
        }
        #expect(calls.first?.id == "call_9")
        #expect(calls.first?.arguments["unit"] as? String == "cup")

        #expect(wire.parse(#"{"type":"response.created","response":{}}"#) == [.responseStarted])
        #expect(wire.parse(#"{"type":"response.done","response":{"output":[{"type":"function_call","call_id":"call_9"}]}}"#) == [.responseDone(hasToolCalls: true)])
        #expect(wire.parse(#"{"type":"response.done","response":{"output":[{"type":"message"}]}}"#) == [.responseDone(hasToolCalls: false)])
        #expect(wire.parse(#"{"type":"input_audio_buffer.speech_started"}"#) == [.userSpeechStarted])
        #expect(wire.parse(#"{"type":"error","error":{"message":"busy","code":"conversation_already_has_active_response"}}"#)
            == [.error("busy", code: "conversation_already_has_active_response")])
    }

    @Test func openAIEncodesFunctionOutputsAndAsksForTheNextResponse() throws {
        let wire = OpenAIRealtimeWire()
        let outputs = wire.toolResponseMessages([LiveToolResponse(id: "call_9", name: "log_water", outputJSON: #"{"ok":true}"#)])
        let item = try #require(try Self.json(outputs[0])["item"] as? [String: Any])
        #expect(item["type"] as? String == "function_call_output")
        #expect(item["call_id"] as? String == "call_9")
        #expect(item["output"] as? String == #"{"ok":true}"#)
        #expect(try Self.json(try #require(wire.responseCreateMessage()))["type"] as? String == "response.create")
        #expect(wire.needsResponseCreate)

        let truncate = try Self.json(try #require(wire.truncateMessage(itemID: "item-1", audioEndMs: -5)))
        #expect(truncate["audio_end_ms"] as? Int == 0)
        #expect(wire.imageMessages(Data([1])).count == 1)
    }

    // MARK: xAI

    @Test func xaiDeclaresToolsAndTakesNoImages() throws {
        let wire = XAIVoiceWire()
        let request = wire.request(for: Self.config(.xai))
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer secret-key")
        #expect(request.url?.host == "api.x.ai")

        let messages = try wire.setupMessages(for: Self.config(.xai), resumptionHandle: nil).map(Self.json)
        let session = try #require(messages[0]["session"] as? [String: Any])
        #expect(session["voice"] as? String == "ara")
        #expect((session["tools"] as? [[String: Any]])?.count == 2)
        // tool_choice goes alone, so a rejection can't drop the voice settings.
        #expect((messages[1]["session"] as? [String: Any])?.keys.sorted() == ["tool_choice"])

        #expect(wire.imageMessages(Data([1])).isEmpty)
        #expect(LiveProvider.xai.cameraFrames == .none)
        let call = wire.parse(#"{"type":"response.function_call_arguments.done","call_id":"c1","name":"end_conversation","arguments":""}"#)
        #expect(call == [.toolCalls([LiveToolCall(id: "c1", name: "end_conversation", argumentsJSON: "{}")])])
        #expect(wire.parse(#"{"type":"conversation.item.input_audio_transcription.updated","transcript":"log a banana"}"#)
            == [.userText("log a banana", replacing: true)])
    }

    @Test func cameraFramePoliciesFollowTheContract() {
        #expect(LiveProvider.hosted.cameraFrames == .continuous)
        #expect(LiveProvider.gemini.cameraFrames == .continuous)
        #expect(LiveProvider.openai.cameraFrames == .perTurn)
        #expect(LiveProvider.xai.cameraFrames == .none)
        #expect(VoiceAssistantCamera.frameMaximumSide <= 768)
    }

    // MARK: Socket

    @Test func socketReadsBinaryAndTextFrames() throws {
        let text = #"{"setupComplete":{}}"#
        #expect(try URLSessionLiveSocket.text(from: .string(text)) == text)
        #expect(try URLSessionLiveSocket.text(from: .data(Data(text.utf8))) == text)
    }

    @Test func socketReportsRefusedUpgradesAndServerCloses() throws {
        let url = try #require(URL(string: "wss://fud-ai.app/api/hosted-ai/v1/live"))
        let refused = HTTPURLResponse(url: url, statusCode: 402, httpVersion: nil, headerFields: nil)
        let paymentError = URLSessionLiveSocket.classify(URLError(.badServerResponse), response: refused, closeCode: 0, closeReason: nil)
        #expect((paymentError as? LiveSocketError)?.endReason == .refused(statusCode: 402))

        let upgraded = HTTPURLResponse(url: url, statusCode: 101, httpVersion: nil, headerFields: nil)
        let minutes = URLSessionLiveSocket.classify(URLError(.networkConnectionLost), response: upgraded, closeCode: 4402, closeReason: nil)
        #expect((minutes as? LiveSocketError)?.endReason == .closedByServer(code: 4402, reason: nil))

        let normal = URLSessionLiveSocket.classify(URLError(.networkConnectionLost), response: upgraded, closeCode: 1000, closeReason: Data("bye".utf8))
        #expect((normal as? LiveSocketError)?.endReason == .closedByServer(code: 1000, reason: "bye"))

        let network = URLSessionLiveSocket.classify(URLError(.notConnectedToInternet), response: nil, closeCode: 0, closeReason: nil)
        #expect(network is URLError)
    }

    // MARK: Session

    @Test func sessionSendsSetupFirstAndQueuesInputUntilGeminiIsReady() async throws {
        let socket = FakeLiveSocket()
        let events = EventRecorder()
        let session = LiveSession(config: Self.config(.gemini), wire: GeminiLiveWire(), makeSocket: { _ in socket }) { event in
            events.append(event)
        }
        session.connect()
        session.send(toolResponses: [LiveToolResponse(id: "1", name: "get_app_state", outputJSON: #"{"ok":true}"#)])
        try await socket.waitForSent(count: 1)
        #expect(socket.sent.count == 1)
        #expect(socket.sent.first?.contains("\"setup\"") == true)

        socket.deliver(#"{"setupComplete":{}}"#)
        try await socket.waitForSent(count: 2)
        #expect(socket.sent[1].contains("toolResponse"))
        try await events.waitFor { $0.contains(.ready) }

        socket.deliver(#"{"toolCall":{"functionCalls":[{"id":"7","name":"close_camera","args":{}}]}}"#)
        try await events.waitFor { $0.contains(.toolCalls([LiveToolCall(id: "7", name: "close_camera", argumentsJSON: "{}")])) }
        session.close()
    }

    @Test func sessionReportsWhyTheServerClosed() async throws {
        let socket = FakeLiveSocket()
        let events = EventRecorder()
        let session = LiveSession(config: Self.config(.hosted, credential: .hostedUser("u")), wire: HostedLiveWire(), makeSocket: { _ in socket }) { event in
            events.append(event)
        }
        session.connect()
        socket.fail(with: LiveSocketError.closed(code: 4408, reason: nil))
        try await events.waitFor { $0.contains(.closed(.closedByServer(code: 4408, reason: nil))) }
        session.close()
    }
}

// MARK: - Fakes

/// A socket that records what is sent and hands back scripted server messages.
final class FakeLiveSocket: LiveSocket, @unchecked Sendable {
    private let lock = NSLock()
    private var outbox: [String] = []
    private var inbox: [Result<String, Error>] = []
    private var waiting: CheckedContinuation<String, Error>?

    var sent: [String] { lock.withLock { outbox } }

    func send(_ text: String) async throws {
        lock.withLock { outbox.append(text) }
    }

    func receive() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if !inbox.isEmpty {
                let next = inbox.removeFirst()
                lock.unlock()
                continuation.resume(with: next)
            } else {
                waiting = continuation
                lock.unlock()
            }
        }
    }

    func close() {
        let pending: CheckedContinuation<String, Error>? = lock.withLock {
            defer { waiting = nil }
            return waiting
        }
        pending?.resume(throwing: CancellationError())
    }

    func deliver(_ text: String) {
        push(.success(text))
    }

    func fail(with error: Error) {
        push(.failure(error))
    }

    private func push(_ result: Result<String, Error>) {
        lock.lock()
        if let waiting {
            self.waiting = nil
            lock.unlock()
            waiting.resume(with: result)
        } else {
            inbox.append(result)
            lock.unlock()
        }
    }

    func waitForSent(count: Int) async throws {
        for _ in 0..<200 where sent.count < count {
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

final class EventRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [LiveEvent] = []

    func append(_ event: LiveEvent) {
        lock.withLock { events.append(event) }
    }

    func waitFor(_ condition: ([LiveEvent]) -> Bool) async throws {
        for _ in 0..<200 {
            if condition(lock.withLock({ events })) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out waiting for events; got \(lock.withLock { events })")
    }
}
