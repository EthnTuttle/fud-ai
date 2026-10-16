# Voice assistant (the orb)

A live, speech-to-speech assistant that can do anything a person can do in Fud AI:
open the camera and log what it sees, scan barcodes, pick photos, search and log
saved foods, edit or delete entries, log water, fasting, weight, body fat and
workouts, change settings, move between screens, and answer like Coach. It talks
while it works, and the user sees each step happen in the real app.

This folder is the contract shared by the iOS app, the Android app and the
Worker (`web/`). Change it here first, then on every platform.

- `tools.json`: the assistant's actions (name, description, parameters, effect,
  confirmation). Both apps bundle an identical copy
  (`ios/calorietracker/VoiceAssistant/VoiceAssistantTools.json`,
  `android/app/src/main/assets/voice_assistant_tools.json`);
  `scripts/check_voice_assistant_tools.py` fails CI when a copy drifts.

## Providers

| Choice | Transport | Who pays |
|---|---|---|
| Fud AI (Plus/Pro) | Worker relay to Gemini Live (`gemini-3.8-live`) | Subscription voice minutes, then credits |
| Gemini (own key) | Gemini Live WebSocket, key in `x-goog-api-key` header | User |
| OpenAI (own key) | Realtime WebSocket, `Authorization: Bearer` | User |
| xAI (own key) | Grok Voice realtime WebSocket, `Authorization: Bearer` | User |

Settings offer **Automatic** (default): the selected own-key provider when its key
is saved, otherwise Fud AI hosting when subscribed, otherwise an explanation of
both options (subscribe, or add a Gemini/OpenAI/xAI key). Own keys reuse the
keys already saved in AI provider settings; nothing new is stored.

Built-in models (first is the default): Gemini `gemini-3.8-live`,
`gemini-3.1-flash-live-preview`; OpenAI `gpt-realtime-2.1-mini`,
`gpt-realtime-2.1`; xAI `grok-voice-latest`, `grok-voice-3.0`. Hosted sessions
always use the Worker's model.

Voices: Gemini Kore (default), Puck, Charon, Fenrir, Aoede, Leda, Orus, Zephyr,
Sulafat, Achird, Despina, Gacrux; OpenAI marin (default), cedar, coral, sage,
shimmer, ash, ballad, alloy, echo, verse; xAI ara (default), eve, rex, sal, leo.

Camera frames the model can see: Gemini and hosted `continuous` (one JPEG per
second while the camera is open, ≤ 768 px long side); OpenAI `per_turn` (the
latest frame with each user turn and before capture decisions); xAI `none`
(the model asks the user to say when the food is in frame).

## Session

- Audio: 16-bit mono PCM. Gemini/hosted: 16 kHz in, 24 kHz out. OpenAI/xAI:
  24 kHz both ways. Echo cancellation on (iOS voice processing, Android
  `VOICE_COMMUNICATION` + `AcousticEchoCanceler`). Speaking over the assistant
  stops its playback (OpenAI also gets `conversation.item.truncate`).
- Ends after 45 s with no speech either way (not while a tool is waiting for
  the user), after 15 minutes, when the user closes it, on `end_conversation`,
  or when the app goes to the background.
- Server messages can arrive as text or binary WebSocket frames; decode both
  as UTF-8 JSON.
- Gemini sends `toolCall.functionCalls[{id,name,args}]` and accepts
  `toolResponse.functionResponses[{id,name,response}]`; it may send
  `toolCallCancellation.ids`. OpenAI/xAI send
  `response.function_call_arguments.done {call_id,name,arguments}` and accept
  `conversation.item.create {type: function_call_output, call_id, output}`
  followed by `response.create`.
- Tools are declared at setup: Gemini `tools: [{functionDeclarations}]`;
  OpenAI/xAI `session.tools: [{type: "function", name, description,
  parameters}]` with `tool_choice: "auto"`. The declared set is `tools.json`
  plus the Coach data tools; tools marked `"workout": true` and the Coach
  workout tools are left out when the workout diary is turned off.

### Instructions

Built per session from: a short persona (Fud AI's assistant and coach, warm and
brief, speaks the user's language), the same profile/goal/forecast summary
Coach uses, today's totals, the rules below, and the capabilities of this
session (camera frames, auto-capture, confirm-before-saving).

Rules given to the model: say what it is about to do in a few words, then do
it; use tools for anything in the app instead of telling the user to tap;
never invent numbers (use the data tools); one question at a time; after a
draft, read back the main items and total calories; never call a tool with
`user_confirmed: true` unless the user agreed to that exact change.

## Tool conventions

- Results are JSON objects: `{"ok": true, ...}` or
  `{"ok": false, "error": "<code>", "message": "<short English text>"}`.
  Codes: `invalid_arguments`, `not_found`, `unsupported`, `cancelled`,
  `timeout`, `camera_unavailable`, `permission_denied`, `analysis_failed`,
  `quota_exceeded`, `confirmation_required`, `workout_diary_off`.
- `effect`: `read` (no change), `navigate` (moves the UI), `write` (adds or
  changes data), `destructive` (removes data).
- `confirm`: `never`; `always` (destructive); `setting` (only when **Confirm
  before saving** is on, the default). When confirmation is needed and
  `user_confirmed` is not true, nothing changes; the app shows a card with the
  pending change and Confirm/Cancel buttons and returns
  `{"ok": false, "error": "confirmation_required", "summary": "..."}`. The model
  asks the user and calls again with `user_confirmed: true`. Tapping Confirm on
  the card performs the change and tells the model through the next turn
  (`clientContent` text for Gemini, a user text item for OpenAI/xAI).
- Writes show a card with **Undo** for 8 seconds (log_food, update_food_entry,
  log_water, log_weight, log_body_fat, start_fast, end_fast, log_workout,
  log_cardio, update_setting restore the previous state). Deletes have no undo
  because they were confirmed.
- Drafts (`draft_id`) live for the session. A draft result lists items
  (name, serving, calories, protein_g, carbs_g, fat_g) and totals, and appears
  as a card the user can also tap to log.
- Tools that wait for the user (`pick_photos`, `scan_barcode`) time out after
  90 s with `timeout`; closing the picker/scanner returns `cancelled`.
- Navigation is real: `open_screen` switches tabs and pushes the settings
  section; the assistant shrinks to a floating bubble so the screen is visible.
- Food analysis, barcode lookup and workout parsing use the app's existing
  pipelines and AI settings (hosted analysis spends hosted actions as today).
- Dates are the user's local calendar; `today`/`yesterday` accepted where noted.

## UI

- Entry points: an orb button on Home; iOS App Shortcut / Siri phrase
  ("Talk to Fud AI"), usable from the Action Button, plus a Control Center
  control; Android Quick Settings tile, launcher shortcut and a home-screen
  widget. All open the app straight into a listening session.
- Overlay states: **expanded** (dimmed background, large orb, live captions,
  camera panel when open, cards) and **compact** (a floating orb bubble with a
  caption pill above the tab bar, used while the app navigates or the user is
  looking at a screen). Tapping the bubble expands; an X ends the session.
- The orb is `LiveOrbView` (iOS Metal) / its AGSL port (Android 13+; animated
  Canvas fallback below), tinted with the app theme colour, driven by the
  loudness of whoever is talking: idle, listening, thinking (waiting on the
  model or a tool), speaking.
- Settings → Voice Assistant: provider, model (own key), voice, response
  language (automatic / app language / a chosen one), auto-capture photos,
  confirm before saving, save conversations to Coach, voice minutes left
  (hosted), and how to open it from the system (Action Button/Siri/Control
  Center or tile/shortcut/widget).
- Each finished turn is added to the Coach chat history when **Save
  conversations to Coach** is on (default on).
- All new text is localized in every app language.

## Hosted relay (Worker)

`wss://fud-ai.app/api/hosted-ai/v1/live`, same identity headers as the other
hosted routes (`X-Fud-User-Id`, plus `X-Fud-Platform: ios|android`).

1. Before upgrading, the Worker checks the id, rate limits, the RevenueCat
   plan and the voice balance. Failures are plain HTTP JSON responses like the
   other routes: 401 `invalid_user_id`, 403 `subscription_required`, 402
   `voice_quota_exceeded`, 429 `rate_limited`, 503 `voice_unavailable`.
2. After the upgrade the client speaks the Gemini Live wire protocol. The first
   message must be `setup`; the Worker replaces `model`, and allows only
   `generationConfig` (response modalities, speech/voice config),
   `systemInstruction` (≤ 60 000 chars), `tools` (function declarations only,
   ≤ 64, ≤ 64 KB), input/output transcription, context window compression,
   session resumption and realtime input config. Later client messages may
   only be `realtimeInput`, `clientContent` or `toolResponse`, each ≤ 1 MB.
3. Server messages are forwarded unchanged. The Worker adds its own:
   `{"fudVoice": {"secondsRemaining": n}}` after setup and every 60 s, and
   `{"fudVoice": {"error": "voice_quota_exceeded" | "session_limit"}}` before
   closing with code 4402 (out of minutes) or 4408 (15-minute session limit).
   An upstream failure closes with 1011.
4. Metering: from `setupComplete` until close, in 15-second blocks charged at
   the start of each block. Blocks come from the monthly voice allowance
   (Plus 20 min, Pro 100 min, UTC calendar month), then from the credit bank at
   1 credit per block (4 credits a minute). Each block has an idempotent billing
   receipt, so retrying a committed debit cannot charge twice. A charge that
   cannot be confirmed within five seconds closes the session. The 15-minute
   cap runs independently of database work.
5. `GET /quota` also returns `voice: {month, secondsUsed, secondsLimit,
   creditBank}`.
6. Hosted session-resumption handles are opaque, signed wrappers bound to the
   subscriber. They expire after two hours and become invalid if the hosted
   Gemini key rotates. Clients return them unchanged; raw provider handles,
   modified handles, and handles from another subscriber are rejected.

## Privacy

Voice audio, captions, camera frames sent to the model and tool results go to
the chosen provider (Google for hosted). The Worker stores usage counters and
billing receipts, not conversation content. Receipts expire after 30 days.
The privacy policy describes this.
