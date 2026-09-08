# Source capture support — beta candidate

Status date: 8 September 2026. A fixture is not evidence of a current authenticated provider UI.

| Source/route | Implementation | Executed evidence | Live acceptance |
|---|---|---|---|
| Selected text on permitted current tab | Every nonblank selection wins; literal escaping; toolbar/shortcut/context menu | Packaged Chrome selection, exact exported bytes and phone hash; deterministic short/Unicode cases | Generic selection verified on synthetic pages; provider-wide claim not made |
| ChatGPT | Scoped response controls, incremental reconciliation, structured conversion | Local ChatGPT-shaped fixture in real packaged Chrome; permission controls and synthetic capture-to-phone | NOT MEASURED on current signed-in ChatGPT |
| Claude, Gemini, Perplexity | Optional per-site adapter permissions and response extraction | Source/fixture tests; settings controls present | NOT MEASURED on current authenticated sites |
| Google Notebook | Conservative preview adapter | Source/fixture coverage | NOT MEASURED; NotebookLM alias not implicitly enabled |
| Grok | Conservative detail/response preview | Source/fixture coverage | NOT MEASURED; embedded Grok on X not implicitly supported |
| Substack detail | Conservative post-detail preview | Source/fixture coverage | NOT MEASURED; home-feed capture not promised |
| X detail | Conservative detail preview | Source/fixture coverage | NOT MEASURED; feed-wide capture not promised |
| Generic article | Readability/structured conversion; confirmation for uncertain extraction | Native Chrome preview Cancel/Save and exact Markdown export; same payload stored on TCL | Broad arbitrary-site compatibility not claimed |
| Browser-restricted pages | Useful error instead of a saved claim | Deterministic boundary coverage | Every browser-specific restricted-page UI not measured |

Provider buttons require explicit site permission. Toolbar capture uses the current
tab's active-tab grant. Low confidence requires preview confirmation. The current native reader renders image descriptions as text and does not fetch
remote article images.
