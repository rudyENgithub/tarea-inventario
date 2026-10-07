package com.store.inventory.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.store.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest { // RNF-7 C-1: reglas de dependencia hexagonal

    @ArchTest
    static final ArchRule domainDependsOnlyOnJdkAndApi = classes()
            .that().resideInAPackage("com.store.inventory.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "com.store.inventory.domain..", "com.store.inventory.api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDependsOnlyOnDomainApiAndSlf4j = classes()
            .that().resideInAPackage("com.store.inventory.application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "org.slf4j..",
                    "com.store.inventory.domain..", "com.store.inventory.application..", "com.store.inventory.api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAnyPackage("com.store.inventory.domain..", "com.store.inventory.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "java.sql..", "javax.sql..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule adaptersDoNotDependOnBootstrap = noClasses()
            .that().resideInAPackage("com.store.inventory.adapter..")
            .should().dependOnClassesThat().resideInAPackage("com.store.inventory.bootstrap..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nobodyDependsOnBootstrap = noClasses()
            .that().resideOutsideOfPackage("com.store.inventory.bootstrap..")
            .should().dependOnClassesThat().resideInAPackage("com.store.inventory.bootstrap..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersDoNotKnowOutboundAdapters = noClasses()
            .that().resideInAPackage("com.store.inventory.adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("com.store.inventory.adapter.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersDoNotKnowInboundAdapters = noClasses()
            .that().resideInAPackage("com.store.inventory.adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("com.store.inventory.adapter.in..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiIsSelfContained = classes()
            .that().resideInAPackage("com.store.inventory.api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.store.inventory.api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule restDependsOnlyOnApi = noClasses()
            .that().resideInAPackage("com.store.inventory.adapter.in.rest..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.store.inventory.application..",
                    "com.store.inventory.adapter.out..",
                    "com.store.inventory.domain..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule systemClockOnlyInBootstrap = noClasses()
            .that().resideOutsideOfPackage("com.store.inventory.bootstrap..")
            .should().callMethod(java.time.Clock.class, "systemUTC")
            .allowEmptyShould(true); // C-4: el Clock.systemUTC de producción vive solo en bootstrap
}
