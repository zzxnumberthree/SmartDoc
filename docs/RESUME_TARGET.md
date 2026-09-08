# Current Resume Target

This document defines the capabilities that the current SmartDoc-JP repository is intended to demonstrate truthfully. A capability belongs in interview or resume wording only when its implementation and supporting test or demo evidence are present in the repository.

## Current target capabilities

- Java 21
- Spring Boot 3.4.1
- Spring AI
- Google Gemini 2.5 Flash integration
- PDF supplied to Gemini as multimodal input
- Automatic document summarization
- Asynchronous document processing
- Local RAG using Spring AI `SimpleVectorStore`
- Semantic question answering with document-source citations
- Web Server-Sent Events (SSE) streaming
- Five read-only Spring AI `@Tool` functions
- JUnit 5 tests
- Dockerfile and Docker Compose configuration

These are deliberately narrow capability statements. They do not imply production scale, measured performance, complete provider independence, formal workflow planning, or clean-host reproducibility unless `docs/RESUME_EVIDENCE.md` records that additional evidence.

## Not current target claims

The following must not be presented as current capabilities and must not be implemented merely to make the resume sound more sophisticated:

- Redis
- A formal finite-state machine (FSM)
- Autonomous Task Planning
- Robotics or autonomous control
- A 30x speed improvement
- End-to-end latency below 200 ms
- Proven high-concurrency reliability
- Test coverage of 90% or higher

## Evidence contract

- `docs/RESUME_EVIDENCE.md` is the claim-by-claim evidence record.
- `docs/TESTING.md` documents reproducible commands and the limits of each test tier.
- Dependencies, mocks of application business services, placeholder classes, TODOs, and unmeasured assertions are not sufficient evidence by themselves.
- Performance numbers require a checked-in benchmark definition and reproducible result artifact.
- Status fields may be described as lifecycle tracking, not as an FSM.
- Model-selected use of registered functions may be described as Tool Calling, not as an autonomous planner.
