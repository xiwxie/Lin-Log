# Changelog

All notable changes to LinLog will be documented in this file.

## [1.0.0] - 2026-09-08
### Added
- 🚀 **Universal Early-Bird Spooling**: Direct zero-latency physical pre-writing before Application.attachBaseContext, providing 100% crash survivability during cold boot.
- ⚡ **Atomic State Handover State Machine**: Three-state lock-free handover eliminating all orphan log loss.
- 🌐 **Multi-Domain Physical Segregation**: Dedicated channel directory isolation (`log/`, `socket/`, `apm/`, `network/`, `track/`).
- 🔄 **Safe Hot-Cold Roll & Export**: Built-in concurrent active log roll preventing data loss during network uploading.
- 🎯 **Full-Symmetric Polymorphism**: Uniform API overloads across all log levels (`v`, `d`, `i`, `w`, `e`), supporting positional `toFile` argument and direct `Throwable` handling without compiler ambiguity.
- 🤖 **AI-Native Skill & Headless Diagnostic Engine**: In-repo Antigravity/Cursor agent skill with persistent hash indexing cache and interactive REPL mode.
