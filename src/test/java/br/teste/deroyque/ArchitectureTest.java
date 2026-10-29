package br.teste.deroyque;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Regras de dependência: os models não conhecem ninguém; os services não conhecem HTTP de entrada nem o
 * cliente do provedor (só a porta {@code ProviderGateway}); os controllers não falam direto com o provedor
 * nem com os repositórios.
 */
@AnalyzeClasses(packages = "br.teste.deroyque", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controllers").definedBy("..controllers..")
            .layer("Services").definedBy("..services..")
            .layer("Repositories").definedBy("..repositories..")
            .layer("Clients").definedBy("..clients..")
            .whereLayer("Controllers").mayNotBeAccessedByAnyLayer()
            .whereLayer("Clients").mayNotBeAccessedByAnyLayer()
            .whereLayer("Services").mayOnlyBeAccessedByLayers("Controllers", "Clients")
            .whereLayer("Repositories").mayOnlyBeAccessedByLayers("Services");

    @ArchTest
    static final ArchRule modelsDoNotDependOnOtherLayers = noClasses()
            .that().resideInAPackage("..models..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controllers..", "..services..", "..repositories..", "..clients..", "..dtos..");

    @ArchTest
    static final ArchRule modelsDoNotDependOnSpringWeb = noClasses()
            .that().resideInAPackage("..models..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnHttp = noClasses()
            .that().resideInAPackage("..services..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..");
}
