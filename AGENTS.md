# Repository Working Rules

These rules apply to all work in this repository.

- Resume claims must be backed by source code plus reproducible test or demo evidence.
- Never add a technology merely to satisfy a buzzword.
- Do not claim performance without a reproducible benchmark, a defined workload, and retained results.
- Do not treat a dependency declaration as proof that a technology is implemented or used.
- Do not describe enum values or status flags as a finite-state machine.
- Do not describe model-selected Tool Calling as a task-planning system.
- Prefer small, verifiable changes over broad refactors.
- Never commit or push automatically.
- Run the relevant tests after modifying code or configuration.
- Keep `docs/RESUME_EVIDENCE.md` synchronized with behavior that has actually been verified.

When the working tree is dirty, preserve all existing user changes. Do not reset, discard, overwrite, stash, or reorganize unrelated work.

## Multi-Agent Delegation

For non-trivial repository tasks, the main agent must delegate before implementation.

- Use an explorer subagent for repository inspection and evidence gathering.
- Use a tester subagent for test design, execution, and failure analysis.
- Use a reviewer subagent for post-change review and regression/risk checks.

The main agent should focus on architecture, task decomposition, cross-module decisions, and final synthesis. It should not perform repository-wide exploration or repetitive test/debug loops when those activities can be delegated.

For tasks expected to require more than approximately 10 minutes or touch multiple modules, use at least two subagents unless delegation is technically unavailable.

When finishing, report which subagents were invoked, what each did, and what remained with the main agent.
