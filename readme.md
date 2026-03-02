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
│       ├── ActionSpec.java         — tool name, args, risk level, human description
│       ├── ContactsLookupTool.java — queries ContactsContract (needs READ_CONTACTS)
│       ├── EmailSummaryTool.java   — mock inbox with 3 sample emails
│       ├── MapsNavigateTool.java   — opens Google Maps nav, geo: fallback
│       ├── PhoneDialTool.java      — ACTION_DIAL intent (no permission needed)
│       ├── Plan.java               — list of ActionSpec + assistant message
│       ├── RiskLevel.java          — LOW / MEDIUM / HIGH
│       ├── SmsComposeTool.java     — ACTION_SENDTO with smsto: (no permission)
│       ├── Tool.java               — interface: name(), defaultRiskLevel(), execute()
│       ├── ToolRegistry.java       — name→Tool map
│       └── ToolResult.java         — status, message, data, error, audit trail
├── llm/
│   ├── LLMClient.java             — interface: plan(userText, session)
│   └── MockLLMClient.java         — deterministic keyword parser (EN + CN)
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
Show my emails / 收件箱	Returns mock email summary
拨打 555-1234 / 发短信	Chinese call/SMS commands
Anything else	Shows help text with supported commands
