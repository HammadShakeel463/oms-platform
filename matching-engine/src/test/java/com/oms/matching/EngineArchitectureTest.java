package com.oms.matching;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Architecture rules for the engine, enforced by the build.
 *
 * <p>The book package is the hot path, and the rules below are the ones that would be quietly
 * violated by a well-meaning change six months from now. "Do not put a database call in the
 * matching loop" is obvious in a code review and invisible in a diff that adds one
 * {@code @Autowired} field.
 */
class EngineArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.oms.matching");
    }

    @Test
    @DisplayName("the book knows nothing about Spring")
    void bookIsFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.matching.book..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "io.micrometer..")
                .because("the book is a data structure. Keeping the framework out of it is "
                        + "what lets JMH benchmark it directly, with no container to start "
                        + "and no proxy between the benchmark and the code under test");

        rule.check(classes);
    }

    @Test
    @DisplayName("the book does no I/O and touches no clock")
    void bookDoesNoIo() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.matching.book..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..", "java.nio.file..", "java.net..",
                        "java.sql..", "javax.sql..",
                        "org.apache.kafka..")
                .because("anything on the matching path that blocks holds the book, and every "
                        + "other order for that symbol queues behind it");

        rule.check(classes);
    }

    @Test
    @DisplayName("the engine has no persistence layer at all")
    void engineHasNoDatabase() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.matching..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate..",
                        "org.springframework.data.jpa..", "java.sql..")
                .because("the engine holds its books in memory and rebuilds them by replaying "
                        + "Kafka. A database here would put I/O on the hot path and, worse, "
                        + "invite someone to use it");

        rule.check(classes);
    }

    @Test
    @DisplayName("the book never uses BigDecimal")
    void bookUsesFixedPointOnly() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().haveSimpleName("PriceTimeOrderBook")
                .or().areDeclaredInClassesThat().haveSimpleName("OrderNode")
                .or().areDeclaredInClassesThat().haveSimpleName("PriceLevel")
                .should().haveRawType(java.math.BigDecimal.class)
                .because("BigDecimal in the inner loop is sustained allocation pressure, which "
                        + "is a p99 problem rather than a throughput problem (ADR 0002). "
                        + "NaiveOrderBook is deliberately exempt - being slower is its job");

        rule.check(classes);
    }

    @Test
    @DisplayName("money is never a floating point type anywhere in the engine")
    void noFloatingPointMoney() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        "com.oms.matching.book..", "com.oms.matching.api..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because("0.1 + 0.2 != 0.3, and this is somebody's limit price (ADR 0002)");

        rule.check(classes);
    }

    @Test
    @DisplayName("no field injection")
    void noFieldInjection() {
        ArchRule rule = noFields()
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("constructor injection keeps dependencies final and the object "
                        + "constructible in a plain unit test");

        rule.check(classes);
    }

    @Test
    @DisplayName("the API layer reads snapshots, never the live book")
    void apiNeverTouchesTheLiveBook() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.matching.api..")
                .should().dependOnClassesThat().haveSimpleName("PriceTimeOrderBook")
                .because("a reader that walks the live book is a data race on the hot path, "
                        + "triggered by an HTTP request (ADR 0005)");

        rule.check(classes);
    }

    @Test
    @DisplayName("the baseline book is never wired into the running service")
    void baselineBookIsNotUsedInProduction() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.oms.matching.engine..",
                        "com.oms.matching.messaging..", "com.oms.matching.api..",
                        "com.oms.matching.config..")
                .should().dependOnClassesThat().haveSimpleName("NaiveOrderBook")
                .because("NaiveOrderBook exists to make the performance comparison "
                        + "reproducible, and for no other reason");

        rule.check(classes);
    }
}
