package com.oms.position;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

class PositionArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.oms.position");
    }

    @Test
    @DisplayName("ADR 0002: money is never a floating point type")
    void moneyIsNeverFloatingPoint() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        "com.oms.position.domain..", "com.oms.position.api..",
                        "com.oms.position.service..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because("these fields are realised and unrealised P and L. A float here is a wrong "
                        + "number that nobody notices until a reconciliation (ADR 0002)");

        rule.check(classes);
    }

    @Test
    @DisplayName("the P and L arithmetic does not depend on the framework")
    void domainIsFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.position.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "com.oms.position.api..",
                        "com.oms.position.repository..")
                .because("PositionEntity holds the average-cost arithmetic, and it has to be "
                        + "testable as plain arithmetic with no container");

        rule.check(classes);
    }

    @Test
    @DisplayName("entities live only in the domain package")
    void entitiesAreConfinedToDomain() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.oms.position.domain..")
                .should().beAnnotatedWith("jakarta.persistence.Entity");

        rule.check(classes);
    }

    @Test
    @DisplayName("controllers do not reach past the service layer")
    void controllersDoNotTouchRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.position.api..")
                .should().dependOnClassesThat().resideInAPackage("com.oms.position.repository..");

        rule.check(classes);
    }

    @Test
    @DisplayName("transactions are declared in the service layer, never on a controller or a listener")
    void transactionsBelongToServices() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.oms.position.api..", "com.oms.position.messaging..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .because("the listener delegates and the service owns the boundary, which is what "
                        + "makes the delegate unit-testable without a broker");

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
