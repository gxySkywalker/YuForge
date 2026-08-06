# Repository Guidelines

## Project Structure & Module Organization

YuForge is a Java 17 command-line agent. The repository root is the parent of this `config/` directory. Production code lives under `src/main/java/com/yuforge/`, organized by responsibility: `agent/` contains ReAct, plan, and multi-agent execution; `cli/` owns command parsing and startup; `tool/`, `mcp/`, `memory/`, `render/`, and `wechat/` provide major subsystems. Resources, prompts, and bundled skills are in `src/main/resources/`. Tests mirror the production package layout in `src/test/java/com/yuforge/`; test fixtures live in `src/test/resources/`. Documentation is in `docs/`, while web demos and assets are in `landing/`, `demo-*`, and `img/`. This directory contains MCP client configuration such as `config/mcporter.json`.

## Build, Test, and Development Commands

Run commands from the repository root:

- `mvn clean package` builds the shaded executable JAR; tests are skipped by default.
- `java -jar target/yuforge-1.0-SNAPSHOT.jar` starts the interactive CLI.
- `mvn test -Pquick` runs the normal regression suite.
- `mvn test -Pphase16-smoke` exercises terminal/TUI behavior.
- `mvn test -Dtest=CliCommandParserTest -DskipTests=false` runs one focused test class.
- `mvn test -DskipTests=false` runs the complete suite.

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8, and straightforward Java 17. Follow existing naming: packages are lowercase, classes use `PascalCase`, and methods/fields use `camelCase`. Keep responsibilities within the established subsystem packages and prefer readable, local changes over new abstraction layers. No formatter is enforced; match surrounding code and imports.

## Testing Guidelines

Tests use JUnit 5, with Mockito and MockWebServer where appropriate. Name tests `*Test.java` and mirror the package of the class under test. Add focused regression coverage for behavior changes, especially CLI parsing, policy/HITL, MCP, and rendering. Run the targeted test first, then `mvn test -Pquick` before submitting.

## Commit & Pull Request Guidelines

Recent history is dominated by automated `pre-turn`/`post-turn` snapshot commits, so it does not define a durable human convention. Use concise, imperative commit subjects such as `fix(cli): preserve submitted input`. Keep commits scoped and exclude `.env`, API keys, and `target/`. Pull requests should explain the user-visible change, list verification commands, link relevant issues, and include terminal screenshots for rendering or interaction changes. Update `README.md`, `AGENTS.md`, and relevant docs whenever behavior or command entry points change.

## Security & Configuration

Copy `.env.example` to `.env` locally and configure at least one supported provider key. Never commit credentials. Keep file operations inside the project root and preserve the existing approval, path-guard, and command-guard layers when adding tools.
