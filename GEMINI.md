# Gemini CLI Project Instructions

## Project Overview
SmartDoc-JP is an intelligent document management system built with Java and Spring Boot. It integrates with Google Gemini via Spring AI to provide automated document summarization for various formats (TXT, PDF, etc.).

## Technology Stack
- **Language**: Java 17+
- **Framework**: Spring Boot 3.4.x
- **AI Integration**: Spring AI (Google GenAI)
- **Database**: MySQL, Spring Data JPA
- **Build Tool**: Maven

## Architecture & Design Patterns
- **Layered Architecture**: Strictly follow the Controller -> Service -> Repository -> Model pattern.
- **Strategy Pattern**: Document parsing (e.g., TXT, PDF) is implemented using the Strategy Pattern (`DocumentParser` interface). Always extend this interface when adding support for new document formats.
- **Exception Handling**: Rely on `GlobalExceptionHandler` to handle exceptions centrally and return RFC 7807 Problem Detail responses.

## Code Conventions & Rules
1. **Lombok**: Extensively use Lombok annotations (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Slf4j`) to reduce boilerplate code.
2. **Dependency Injection**: Use Constructor Injection via Lombok's `@RequiredArgsConstructor` for injecting Spring Beans. Avoid field injection (`@Autowired`).
3. **Logging**: Always use `@Slf4j` for logging. **DO NOT** use `System.out.println()`. Ensure log messages are meaningful and include contextual information (e.g., file names, user IDs).
4. **Configuration**: Externalize all configuration (like proxy settings, API keys) into `application.properties` or environment variables. Do not hardcode configurations in Java classes.
5. **Transactions**: Use `@Transactional` in Service layers for methods that modify the database or handle complex file operations to ensure data consistency.

## Build and Run
- Prefer using the Maven Wrapper (`./mvnw` or `.\mvnw.cmd`) over the system Maven installation to ensure consistent build environments.

## AI Interaction Guidelines
- When implementing new AI features, ensure prompts are externalized or easily configurable.
- Handle AI service failures gracefully without crashing the main application flow (e.g., fallback summaries or detailed error logging).
