package com.oms.order;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Architecture rules, enforced by the build instead of by code review.
 *
 * <p>Every rule here corresponds to a decision recorded in an ADR or a design document.
 * Writing the decision down is necessary; making it fail the build is what keeps it true
 * eighteen months later, when the person who wrote the ADR has moved on and someone adds a
 * repository call to a controller because it was quicker.
 *
 * <p>This is the closest Java gets to the compile-time enforcement a C++ codebase achieves
 * with header discipline and physical dependency structure - except it also catches
 * runtime-only coupling like an annotation on the wrong layer.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.oms");
    }

    @Test
    @DisplayName("ADR 0001: the shared contract module never contains persistence")
    void sharedModuleHasNoPersistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate..", "org.springframework.data..")
                .because("oms-common carries contracts, not entities - sharing entities "
                        + "couples services to each other's schema (ADR 0001)");

        rule.check(classes);
    }

    @Test
    @DisplayName("the shared contract module does not depend on Spring")
    void sharedModuleIsFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.common..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("oms-common must be usable by a plain client with no Spring on "
                        + "the classpath");

        rule.check(classes);
    }

    @Test
    @DisplayName("controllers do not reach past the service layer")
    void controllersDoNotTouchRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.order.api..")
                .should().dependOnClassesThat().resideInAPackage("com.oms.order.repository..")
                .because("the service layer owns the transaction; a controller that queries "
                        + "directly runs outside one");

        rule.check(classes);
    }

    @Test
    @DisplayName("transactions are declared in the service layer, never on a controller")
    void transactionsBelongToServices() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.order.api..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .because("a transaction spanning HTTP serialisation holds a database "
                        + "connection while bytes are written to a socket");

        rule.check(classes);
    }

    @Test
    @DisplayName("entities live only in the domain package")
    void entitiesAreConfinedToDomain() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.oms.order.domain..")
                .should().beAnnotatedWith("jakarta.persistence.Entity")
                .because("entities scattered across packages is how a service loses track of "
                        + "what it persists");

        rule.check(classes);
    }

    @Test
    @DisplayName("no field injection anywhere")
    void noFieldInjection() {
        ArchRule rule = noFields()
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("constructor injection keeps dependencies final, makes the object "
                        + "constructible in a plain unit test, and fails fast on a cycle");

        rule.check(classes);
    }

    @Test
    @DisplayName("ADR 0002: money is never a floating point type")
    void moneyIsNeverFloatingPoint() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        "com.oms.order.domain..", "com.oms.order.api.dto..", "com.oms.common..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because("0.1 + 0.2 != 0.3, and this is somebody's limit price (ADR 0002)");

        rule.check(classes);
    }

    @Test
    @DisplayName("entity fields are never public")
    void entityFieldsAreEncapsulated() {
        ArchRule rule = fields()
                .that().areDeclaredInClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
                .should().notBePublic()
                .because("Hibernate change tracking depends on going through the object, and "
                        + "a public mutable field bypasses every invariant the class declares");

        rule.check(classes);
    }

    @Test
    @DisplayName("the domain layer does not depend on the web layer")
    void domainDoesNotDependOnWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.oms.order.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.oms.order.api..", "org.springframework.web..")
                .because("dependencies point inwards: the domain must not know it is served "
                        + "over HTTP");

        rule.check(classes);
    }
}
