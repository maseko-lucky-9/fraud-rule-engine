# ADR-0011: Spring Boot 4 (4.0.6) and JDK 21 — Plan Deviation Log

**Status:** Accepted · **Date:** 2026-05-19

## Context

The original execution plan (Section 2) locked Spring Boot **3.4.5** as the framework version. When scaffolding via Spring Initializr (`start.spring.io`) on 2026-05-19, Initializr's metadata reported the minimum supported version as **3.5.0** and the current default as **4.0.6** (Spring Boot 4 GA). Spring Boot 3.4.x has reached end-of-OSS-support.

Additionally, the host machine ships with JDK 26 (Temurin); JDK 21 was installed via `brew install openjdk@21` to match the plan's `java.version=21` target.

## Decision

- **Spring Boot 4.0.6** (the current stable default, Java 21 baseline).
- **JDK 21** as the build and runtime target (`<java.version>21</java.version>` in `pom.xml`; `eclipse-temurin:21-jdk-alpine` builder / `21-jre-alpine` runtime in the Dockerfile).

## Initial gotchas (recorded for the reviewer)

1. **`4.0.6.RELEASE` is not a real Maven artefact.** Initializr's metadata returns the legacy `.RELEASE` suffix in the version dropdown; the actual published artefact is plain `4.0.6`. The generated `pom.xml` had to be edited from `4.0.6.RELEASE` → `4.0.6` before the build resolved.
2. **Starter renames.** In Boot 4 the `spring-boot-starter-web` artefact is split: `spring-boot-starter-webmvc` (servlet stack) vs. `spring-boot-starter-webflux` (reactive). We use `webmvc`.
3. **Modular test starters.** Boot 4 ships per-feature `*-test` starters (`spring-boot-starter-webmvc-test`, `-actuator-test`, `-security-test`, etc.) rather than a single `spring-boot-starter-test`. Initializr generates the correct ones automatically.

## Consequences

**Positive.**
- Submission demonstrates currency with the latest Spring stack (signal value).
- Java 21 virtual threads + sealed types available if we need them later.

**Negative / risk.**
- Some third-party libraries (springdoc-openapi, resilience4j) have not yet published Boot-4-specific versions; we pin known-good 2.x / 2.3.x versions that work via Spring's binary compatibility. If incompatibilities surface in Days 2–7, ADR-0011-v2 will document the fix.

## Migration back

If a target environment requires Boot 3.5 LTS:

1. Set `<version>3.5.14</version>` in `pom.xml` parent.
2. Rename `spring-boot-starter-webmvc` → `spring-boot-starter-web`.
3. Collapse `*-test` starters back to `spring-boot-starter-test`.
4. `./mvnw verify`. All application code is Boot-3-compatible (we avoid Boot-4-only APIs).
