package com.exepex.stratum.test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorCode;
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
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The checks and set-up that the tests of every Stratum starter share, so that each starter calls
 * them instead of keeping its own copy.
 */
@UtilityClass
public class StarterTestSupport {

  /** The page that lists every Stratum code, relative to a module's directory. */
  static final Path ERROR_CODES_PAGE = Path.of("../../docs/guides/error-codes.md");

  /** The annotation every class in a starter's autoconfigure package carries (ST-ENG-23). */
  private static final String CONFIGURATION =
      "org.springframework.context.annotation.Configuration";

  /** The annotation every method in a starter's autoconfigure package carries (ST-ENG-23). */
  private static final String BEAN = "org.springframework.context.annotation.Bean";

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
   * Checks a starter's own classes, without its tests, against Stratum's architecture rules
   * (ST-TEST-21). A failure lists every class that breaks a rule.
   *
   * @param classInStarterPackage any class in the starter's top package, such as its constants
   */
  public static void checkStarterArchitecture(Class<?> classInStarterPackage) {
    starterArchitecture(classInStarterPackage.getPackageName())
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackagesOf(classInStarterPackage));
  }

  /**
   * Checks that every code of a module is {@code SX-<module>-} and four digits, that no two share a
   * number, and that the error codes page lists exactly the module's codes.
   *
   * @param module the module's part of the code, such as {@code LOGGING}
   * @param codes every code of the module
   * @throws IOException if the error codes page cannot be read
   */
  public static void checkErrorCodes(String module, ErrorCode... codes) throws IOException {
    var codeFormat = Pattern.compile("SX-" + module + "-\\d{4}");
    var moduleCodes = Arrays.stream(codes).map(ErrorCode::getCode).toList();
    var documentedCodes =
        codeFormat
            .matcher(Files.readString(ERROR_CODES_PAGE))
            .results()
            .map(MatchResult::group)
            .distinct()
            .toList();

    assertThat(moduleCodes)
        .allSatisfy(code -> assertThat(code).matches(codeFormat))
        .doesNotHaveDuplicates();
    assertThat(documentedCodes).containsExactlyInAnyOrderElementsOf(moduleCodes);
  }

  /**
   * Returns Stratum's architecture rules for a starter: its autoconfigure package only creates and
   * wires beans (ST-ENG-23), it uses no class of another starter, and its packages have no cycles.
   *
   * @param starterPackage the starter's top package, such as {@code com.exepex.stratum.logging}
   * @return the rules, as one rule
   */
  static ArchRule starterArchitecture(String starterPackage) {
    var autoconfigurePackage = starterPackage + ".autoconfigure";
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
                .beAnnotatedWith(BEAN))
        .and(
            classes()
                .should()
                .onlyDependOnClassesThat(
                    resideOutsideOfPackage("com.exepex.stratum..")
                        .or(
                            resideInAnyPackage(
                                starterPackage + "..", "com.exepex.stratum.core.."))))
        .and(slices().matching("com.exepex.stratum.(**)").should().beFreeOfCycles());
  }
}
