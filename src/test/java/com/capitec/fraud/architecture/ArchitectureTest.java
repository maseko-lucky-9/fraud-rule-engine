package com.capitec.fraud.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.web.bind.annotation.RequestMapping;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Plan §5 promises three boundary rules. They live here and run in the
 * Surefire test phase via the {@code archunit-junit5} runner.
 */
@AnalyzeClasses(
        packages = "com.capitec.fraud",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    /**
     * Rule 1: API controllers must not reach into persistence directly.
     * Persistence is approached via {@code engine}, {@code ingest},
     * {@code query}, or {@code audit} service layers (which own the
     * transactional boundary and never leak entities out).
     */
    @ArchTest
    static final ArchRule api_must_not_depend_on_persistence_directly = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..persistence..");

    /**
     * Rule 2: Domain is pure POJOs. No Spring, JPA, Kafka, Jackson, or
     * validation annotations. Keeps the domain model framework-free so it
     * can be lifted into another stack later without rewriting types.
     */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_frameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "jakarta.validation..",
                    "org.apache.kafka..",
                    "com.fasterxml.jackson.."
            );

    /**
     * Rule 3: Advisory (Day 5) is reachable only from API + engine entry
     * points. Domain / persistence must never call out to it — keeps the
     * deterministic decision path free of Ollama coupling.
     */
    @ArchTest
    static final ArchRule advisory_must_only_be_called_from_api_or_engine = classes()
            .that().resideInAPackage("..advisory..")
            .should().onlyBeAccessed().byAnyPackage(
                    "..advisory..",
                    "..api..",
                    "..engine..",
                    "..config.."
            )
            // Day 5 creates ..advisory..; until then the rule has nothing to inspect.
            .allowEmptyShould(true);

    /**
     * Rule 4: every controller mapped under {@code /admin/**} must carry
     * a class-level {@code @RequestMapping("/admin/...")}. The
     * SecurityFilterChain bound to {@code ROLE_SERVICE} relies on that path
     * prefix — a controller annotated with {@code @RequestMapping("/api/...")}
     * but conceptually "admin" would slip past the API key check. This
     * rule pins the convention so a refactor can't accidentally re-class
     * an admin path as a JWT-only path.
     */
    @ArchTest
    static final ArchRule admin_controllers_use_admin_path_prefix = classes()
            .that().haveSimpleNameEndingWith("AdminController")
            .or().haveSimpleNameEndingWith("AuditController")
            .should(new ArchCondition<JavaClass>("be annotated with @RequestMapping(\"/admin/...\")") {
                @Override
                public void check(JavaClass cls, ConditionEvents events) {
                    RequestMapping rm = cls.reflect().getAnnotation(RequestMapping.class);
                    boolean ok = rm != null && rm.value().length > 0
                            && rm.value()[0].startsWith("/admin");
                    if (!ok) {
                        events.add(SimpleConditionEvent.violated(cls,
                                cls.getFullName() + " is an admin/audit controller but its class-level "
                                        + "@RequestMapping does not start with /admin — this would bypass "
                                        + "the SecurityConfig ROLE_SERVICE rule."));
                    }
                }
            });
}
