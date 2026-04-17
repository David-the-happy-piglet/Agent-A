agent_a/           
├── core/
│   ├── agent/
│   │   ├── Executor.java          — runs tools sequentially, resolves data between steps
│   │   ├── Planner.java           — orchestrates LLMClient to produce a Plan
│   │   ├── Policy.java            — enforces action preview before execution
│   │   └── PromptBuilder.java     — builds session context string
│   ├── memory/
│   │   ├── Message.java           — USER/ASSISTANT role, text, timestamp
│   │   └── SessionStore.java      — in-memory list (max 20, auto-compress old messages)
│   └── tools/
│       ├── AppCapabilityTool.java — lists installed apps and checks command feasibility
│       ├── ActionSpec.java         — tool name, args, risk level, human description
│       ├── ContactsLookupTool.java — queries ContactsContract (needs READ_CONTACTS)
│       ├── EmailSummaryTool.java   — reads cached Gmail notification snippets for today's email list
│       ├── GmailNotificationListenerService.java — captures new Gmail notification snippets locally
│       ├── MapsNavigateTool.java   — opens Google Maps nav, geo: fallback
│       ├── MediaShareTool.java     — finds recent photos/videos and opens Android share/MMS compose
│       ├── NewsFeedTool.java       — fetches real RSS headlines by category
│       ├── PhoneDialTool.java      — ACTION_DIAL intent (no permission needed)
│       ├── Plan.java               — list of ActionSpec + assistant message
│       ├── RiskLevel.java          — LOW / MEDIUM / HIGH
│       ├── SmsComposeTool.java     — ACTION_SENDTO with smsto: (no permission)
│       ├── SpotifyControlTool.java — opens Spotify search/URI and sends media controls
│       ├── Tool.java               — interface: name(), defaultRiskLevel(), execute()
│       ├── ToolRegistry.java       — name→Tool map
│       ├── ToolResult.java         — status, message, data, error, audit trail
│       └── WeatherTool.java        — fetches current weather via wttr.in JSON
├── llm/
│   ├── ConfigurableLLMClient.java  — runtime OpenAI-compatible LLM config
│   ├── GeminiLLMClient.java        — Gemini API client
│   ├── LLMClient.java             — interface: plan(userText, session)
│   ├── MiniMaxLLMClient.java       — MiniMax API client
│   └── MockLLMClient.java          — deterministic keyword parser (EN + CN)
└── ui/
    ├── ActionPreviewHelper.java    — MaterialAlertDialog listing planned actions
    ├── AgentChatActivity.java      — main chat screen, orchestrates entire pipeline
    └── ChatAdapter.java            — RecyclerView adapter with user/assistant bubbles


Pipeline Flow
User types a command and presses Send
Planner calls MockLLMClient.plan() which parses keywords to produce a Plan
Policy checks if preview is required (always yes for prototype)
Action Preview dialog shows numbered actions with risk labels
On Confirm, Executor runs each tool sequentially, resolving data between steps (e.g., contact lookup phone number flows to SMS compose)
Results appear as assistant messages in the chat


Supported Commands (Mock LLM)
Input Example	What Happens
Call 555-1234	Opens dialer with number
Call Mom	Looks up contact "Mom"
Text 555-1234 hello there	Opens SMS compose
Text John saying meet at 5	Looks up "John", then composes SMS
Navigate to Boston	Opens Google Maps navigation
去星巴克 / 导航到机场	Chinese navigation commands
Show my emails / 收件箱	Returns today's cached Gmail notification list, or opens Notification Access setup
拨打 555-1234 / 发短信	Chinese call/SMS commands
Play Taylor Swift on Spotify / 暂停播放	Uses Spotify Web API to search/play after OAuth; falls back to Spotify app/media controls if not configured
Send my latest photo to John / 把最近拍摄的照片发给John	Looks up John, finds latest MediaStore photo, opens share/MMS compose
Weather in Boston / 天气如何	Fetches current weather; empty location uses approximate network/IP location
Can this phone execute Spotify commands? / 手机里有哪些app	Lists apps or checks whether commands are feasible
Anything else	Shows help text with supported commands


Custom LLM
The LLM spinner now includes "Custom LLM". Selecting it opens a dialog for:
- Display name
- OpenAI-compatible chat completions base URL
- Model name
- API key

The config is stored locally in SharedPreferences and is hot-swapped into Planner without restarting the app.


Spotify Web API
Set these in local.properties:
- SPOTIFY_CLIENT_ID=your_spotify_dashboard_client_id
- SPOTIFY_REDIRECT_URI=agenta://spotify-auth

The Spotify Dashboard Redirect URI must exactly match agenta://spotify-auth.

First Spotify playback command opens Spotify OAuth with PKCE. After approval, Android returns to Agent-A through the agenta://spotify-auth deep link, stores access/refresh tokens locally, and retries the pending Spotify command.

Supported Web API commands:
- play <query>: searches Spotify tracks and starts the first match
- play <spotify:track|album|playlist URI>: starts that item
- pause Spotify
- next song
- previous song

Playback requires Spotify Premium and an active Spotify playback device. If Spotify reports NO_ACTIVE_DEVICE, open Spotify once or start playback on any Spotify Connect device, then retry.


Android safety notes
- Media/file sending cannot silently send files. The app finds accessible recent media and opens Android share/MMS compose so the user confirms the final send.
- Recent media lookup requires READ_MEDIA_IMAGES / READ_MEDIA_VIDEO on Android 13+, or READ_EXTERNAL_STORAGE on older versions.
- Spotify Web API controls a Spotify playback device; it does not provide audio streams for Agent-A to play by itself.
- Gmail inbox contents are not directly readable by this prototype. Email listing uses Gmail notification snippets captured after the user enables "Agent-A Gmail Email Capture" in Android Notification Access settings.
