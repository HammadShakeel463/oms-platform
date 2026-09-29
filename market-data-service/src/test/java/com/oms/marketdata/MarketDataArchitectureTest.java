package com.oms.marketdata;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

class MarketDataArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.oms.marketdata");
    }

    @Test
    @DisplayName("the streaming machinery knows nothing about HTTP or Spring")
    void streamPackageIsTransportAgnostic() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.marketdata.stream..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.servlet..")
                .because("the mailbox and the broadcaster are a backpressure mechanism, not an SSE "
                        + "implementation. Keeping the transport out is what makes them unit-testable "
                        + "with no container, and reusable if the transport ever changes");

        rule.check(classes);
    }

    @Test
    @DisplayName("the simulator state does no I/O")
    void simulatorStateDoesNoIo() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("SymbolState")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..", "java.net..", "java.sql..",
                        "org.apache.kafka..", "org.springframework..")
                .because("the walk is arithmetic on a single-writer state object, and anything that "
                        + "blocks inside it stalls the whole feed");

        rule.check(classes);
    }

    @Test
    @DisplayName("entities live only in the domain package")
    void entitiesAreConfinedToDomain() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.oms.marketdata.domain..")
                .should().beAnnotatedWith("jakarta.persistence.Entity");

        rule.check(classes);
    }

    @Test
    @DisplayName("controllers do not reach past the service layer")
    void controllersDoNotTouchRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.marketdata.api..")
                .should().dependOnClassesThat().resideInAPackage("com.oms.marketdata.repository..");

        rule.check(classes);
    }

    @Test
    @DisplayName("money is never a floating point type")
    void noFloatingPointMoney() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        "com.oms.marketdata.domain..", "com.oms.marketdata.api..",
                        "com.oms.marketdata.simulator..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because("this is a price that order-service validates orders against (ADR 0002)");

        rule.check(classes);
    }

    @Test
    @DisplayName("no field injection")
    void noFieldInjection() {
        ArchRule rule = noFields()
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired");

        rule.check(classes);
    }
}
