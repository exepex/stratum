package com.exepex.stratum.test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.jayway.jsonpath.JsonPath;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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

  /** The media type of every error body the web starters send. */
  private static final String PROBLEM_JSON = "application/problem+json";

  /** The annotation every method in a starter's autoconfigure package carries (ST-ENG-23). */
  private static final String BEAN = "org.springframework.context.annotation.Bean";

  /** The number of bytes in an IPv4 address. */
  private static final int IPV4_BYTES = 4;

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
   * Checks a starter's own classes, without its tests, against Stratum's architecture rules
   * (ST-TEST-21). A failure lists every class that breaks a rule.
   *
   * @param classInStarterPackage any class in the starter's top package, such as its constants
   * @param sharedLibraryPackages the packages of the Stratum libraries the starter is built on,
   *     besides {@code stratum-core}, such as {@code com.exepex.stratum.web} for a web starter
   */
  public static void checkStarterArchitecture(
      Class<?> classInStarterPackage, String... sharedLibraryPackages) {
    checkModuleClasses(
        starterArchitecture(classInStarterPackage.getPackageName(), sharedLibraryPackages),
        classInStarterPackage);
  }

  /**
   * Checks that a module's classes stay off while the module is off, even when a service's
   * component scan reaches Stratum's packages (ST-ENG-20). A starter gets this check from {@link
   * #checkStarterArchitecture}; a library calls it itself.
   *
   * @param classInModulePackage any class in the module's top package, such as its constants
   */
  public static void checkSafeToScan(Class<?> classInModulePackage) {
    checkModuleClasses(safeToScan(), classInModulePackage);
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
   * Checks an error answer of a web starter: its status, the Problem Details media type, the {@code
   * status} and {@code code} members of the body, and a 32-hex {@code traceId}.
   *
   * @param response the answer
   * @param status the HTTP status it must have
   * @param code the error code its body must have, such as {@code SX-WEB-1003}
   */
  public static void checkProblemDetails(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue(PROBLEM_JSON);
    assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(status);
    assertThat(JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.TRACE_ID))
        .matches("\\p{XDigit}{32}");
  }

  /**
   * Returns the text of an IP address from its numbers: four decimal numbers make an IPv4 address,
   * such as {@code ipAddress(198, 51, 100, 20)} for {@code 198.51.100.20}, and eight make an IPv6
   * address, such as {@code ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)} for {@code 2001:db8::1}.
   * Tests write addresses this way because PMD's {@code AvoidUsingHardCodedIP} rule, which keeps
   * server addresses out of code, rejects address literals.
   *
   * @param numbers the four bytes of an IPv4 address, or the eight groups of an IPv6 address
   * @return the address as text, such as {@code 198.51.100.20} or {@code 2001:db8:0:0:0:0:0:1}
   */
  public static String ipAddress(int... numbers) {
    return numbers.length == IPV4_BYTES
        ? IntStream.of(numbers).mapToObj(Integer::toString).collect(Collectors.joining("."))
        : IntStream.of(numbers).mapToObj(Integer::toHexString).collect(Collectors.joining(":"));
  }

  /**
   * Returns Stratum's architecture rules for a starter: its autoconfigure package only creates and
   * wires beans (ST-ENG-23), it uses no class of another starter, only of {@code stratum-core} and
   * the shared libraries it is built on, and its packages have no cycles.
   *
   * @param starterPackage the starter's top package, such as {@code com.exepex.stratum.logging}
   * @param sharedLibraryPackages the packages of the Stratum libraries the starter is built on
   * @return the rules, as one rule
   */
  static ArchRule starterArchitecture(String starterPackage, String... sharedLibraryPackages) {
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
                .beAnnotatedWith(BEAN))
        .and(
            classes()
                .should()
                .onlyDependOnClassesThat(
                    resideOutsideOfPackage("com.exepex.stratum..")
                        .or(resideInAnyPackage(allowedStratumPackages))))
        .and(slices().matching("com.exepex.stratum.(**)").should().beFreeOfCycles())
        .and(safeToScan());
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
