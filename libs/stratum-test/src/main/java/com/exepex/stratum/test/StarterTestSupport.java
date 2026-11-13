package com.exepex.stratum.test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;

/**
 * The checks and set-up that the tests of every Stratum starter share, so that each starter calls
 * them instead of keeping its own copy: its customizers file and its architecture rules.
 */
@StratumInternal
@UtilityClass
public class StarterTestSupport {

  /** The annotation every class in a starter's autoconfigure package carries (ST-ENG-23). */
  private static final String CONFIGURATION =
      "org.springframework.context.annotation.Configuration";

  /** The annotation every method in a starter's autoconfigure package carries (ST-ENG-23). */
  private static final String BEAN = "org.springframework.context.annotation.Bean";

  /** The annotation behind every class a component scan picks up, such as {@code @Service}. */
  private static final String COMPONENT = "org.springframework.stereotype.Component";

  /**
   * Runs the work with the customizers listed in a {@code META-INF/spring.factories}, as a
   * service's own file lists them. The file is on the thread's context class loader, which Spring
   * Boot and the module read it from.
   *
   * @param folder an empty folder for the file
   * @param customizerType the module's customizer interface, which the file lists them under
   * @param customizers the customizer classes to list
   * @param work what to run
   * @param <C> the module's customizer interface
   * @param <T> what the work returns
   * @return what the work returned
   * @throws IOException if the file cannot be written
   */
  public static <C extends StratumPropertiesCustomizer<?>, T> T withCustomizers(
      Path folder, Class<C> customizerType, List<Class<? extends C>> customizers, Supplier<T> work)
      throws IOException {
    var factories = Files.createDirectories(folder.resolve("META-INF")).resolve("spring.factories");
    Files.writeString(
        factories,
        customizerType.getName()
            + "="
            + customizers.stream().map(Class::getName).collect(Collectors.joining(",")));
    var thread = Thread.currentThread();
    var originalClassLoader = thread.getContextClassLoader();
    try (var classLoader =
        new URLClassLoader(new URL[] {folder.toUri().toURL()}, originalClassLoader)) {
      thread.setContextClassLoader(classLoader);
      return work.get();
    } finally {
      thread.setContextClassLoader(originalClassLoader);
    }
  }

  /**
   * Returns the environment post-processors that the class path's {@code spring.factories} files
   * list, created as Spring Boot creates them at start-up, so a test can check which ones a starter
   * registers. Those that need constructor arguments, such as Spring Boot's own, are left out.
   *
   * @return one post-processor per listed class that needs no argument
   */
  public static List<EnvironmentPostProcessor> environmentPostProcessors() {
    return SpringFactoriesLoader.forDefaultResourceLocation()
        .load(
            EnvironmentPostProcessor.class,
            null,
            FailureHandler.logging(LogFactory.getLog(StarterTestSupport.class)));
  }

  /**
   * Checks a starter's own classes, without its tests, against Stratum's architecture rules
   * (ST-TEST-21). A failure lists every class that breaks a rule.
   *
   * @param classInStarterPackage any class in the starter's top package, such as its constants
   * @param apiTypes the starter's types, top-level or nested, that services may use; every other
   *     one carries {@link StratumInternal}
   * @param sharedLibraryPackages the packages of the Stratum libraries the starter is built on,
   *     besides {@code stratum-core}, such as {@code com.exepex.stratum.web} for a web starter
   */
  public static void checkStarterArchitecture(
      Class<?> classInStarterPackage, Set<Class<?>> apiTypes, String... sharedLibraryPackages) {
    checkModuleClasses(
        starterArchitecture(
            classInStarterPackage.getPackageName(), apiTypes, sharedLibraryPackages),
        classInStarterPackage);
  }

  /**
   * Checks a library's classes: they stay off while the module is off, even when a service's
   * component scan reaches Stratum's packages (ST-ENG-20), its packages have no cycles (ST-ENG-57),
   * each class a service can reach is labelled or on its API list, and its API exposes no internal
   * type (ST-ENG-56). A starter gets these checks from {@link #checkStarterArchitecture}.
   *
   * @param classInLibraryPackage any class in the library's top package, such as its constants
   * @param apiTypes the library's types, top-level or nested, that services may use; every other
   *     one carries {@link StratumInternal}
   */
  public static void checkLibraryArchitecture(
      Class<?> classInLibraryPackage, Set<Class<?>> apiTypes) {
    checkModuleClasses(
        CompositeArchRule.of(safeToScan())
            .and(noPackageCycles())
            .and(PublicApiRule.labelledOrListed(apiTypes))
            .and(PublicApiRule.apiExposesOnlyApi()),
        classInLibraryPackage);
  }

  /**
   * Returns Stratum's architecture rules for a starter: its autoconfigure package only creates and
   * wires beans (ST-ENG-23), it uses no class of another starter, only of {@code stratum-core} and
   * the shared libraries it is built on, its packages have no cycles, each class a service can
   * reach is labelled or on its API list, and its API exposes no internal type.
   *
   * @param starterPackage the starter's top package, such as {@code com.exepex.stratum.logging}
   * @param apiTypes the starter's types, top-level or nested, that services may use
   * @param sharedLibraryPackages the packages of the Stratum libraries the starter is built on
   * @return the rules, as one rule
   */
  static ArchRule starterArchitecture(
      String starterPackage, Set<Class<?>> apiTypes, String... sharedLibraryPackages) {
    var autoconfigurePackage = starterPackage + ".autoconfigure";
    var allowedStratumPackages =
        Stream.concat(
                Stream.of(starterPackage, "com.exepex.stratum.core"),
                Arrays.stream(sharedLibraryPackages))
            .map(stratumPackage -> stratumPackage + "..")
            .toArray(String[]::new);
    return CompositeArchRule.of(
            classes()
                .that()
                .resideInAPackage(autoconfigurePackage)
                .and()
                .doNotHaveSimpleName("package-info")
                .should()
                .beMetaAnnotatedWith(CONFIGURATION))
        .and(
            methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage(autoconfigurePackage)
                .should()
                .beAnnotatedWith(BEAN)
                // An auto-configuration that only imports configurations declares no method.
                .allowEmptyShould(true))
        .and(
            classes()
                .should()
                .onlyDependOnClassesThat(
                    resideOutsideOfPackage("com.exepex.stratum..")
                        .or(resideInAnyPackage(allowedStratumPackages))))
        .and(noPackageCycles())
        .and(PublicApiRule.labelledOrListed(apiTypes))
        .and(PublicApiRule.apiExposesOnlyApi())
        .and(safeToScan());
  }

  /**
   * Returns the rule that no two Stratum packages depend on each other, directly or through others.
   *
   * @return the rule
   */
  static ArchRule noPackageCycles() {
    return slices().matching("com.exepex.stratum.(**)").should().beFreeOfCycles();
  }

  /**
   * Returns the rule that every class a component scan would pick up carries the module's switch. A
   * module with no such class passes.
   *
   * @return the rule
   */
  static ArchRule safeToScan() {
    return classes()
        .that()
        .areMetaAnnotatedWith(COMPONENT)
        .should()
        .beAnnotatedWith(ConditionalOnStratumModule.class)
        .allowEmptyShould(true);
  }

  /** Checks the rule against the module's own classes, without its tests. */
  private static void checkModuleClasses(ArchRule rule, Class<?> classInModulePackage) {
    rule.check(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackagesOf(classInModulePackage));
  }
}
