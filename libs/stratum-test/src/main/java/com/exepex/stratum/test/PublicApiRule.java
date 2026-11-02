package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The rule that Stratum's public API never exposes a type marked {@link StratumInternal}
 * (ST-ENG-56, ADR 0009), so a service never needs an internal type to use or replace an API type.
 */
@StratumInternal
@UtilityClass
public class PublicApiRule {

  /** The package of every Stratum type. */
  private static final String STRATUM_PACKAGE = "com.exepex.stratum";

  /** The member modifiers that make a member part of a type's API. */
  private static final Set<JavaModifier> VISIBLE =
      Set.of(JavaModifier.PUBLIC, JavaModifier.PROTECTED);

  /** The modifiers of members the compiler made, which no service calls. */
  private static final Set<JavaModifier> GENERATED =
      Set.of(JavaModifier.SYNTHETIC, JavaModifier.BRIDGE);

  /**
   * Returns the rule: the superclass, the interfaces, the type parameters and the public and
   * protected constructors, methods and fields of each API type, with their bounds and {@code
   * throws} clauses, use no internal type. A module with no API type passes.
   *
   * @return the rule
   */
  public static ArchRule apiExposesOnlyApi() {
    return ArchRuleDefinition.classes()
        .that(DescribedPredicate.describe("are Stratum's API", PublicApiRule::isApi))
        .should(exposeOnlyApi())
        .allowEmptyShould(true);
  }

  /**
   * Whether a service may use the type: it and every class around it can be reached from a
   * service's code, and none of them is labelled.
   */
  private static boolean isApi(JavaClass type) {
    return type.getPackageName().startsWith(STRATUM_PACKAGE)
        && withEnclosingClasses(type).allMatch(PublicApiRule::isReachable)
        && !isInternal(type);
  }

  /** Reports each internal type that an API type exposes, with where it exposes it. */
  private static ArchCondition<JavaClass> exposeOnlyApi() {
    return new ArchCondition<>("expose only API types") {
      @Override
      public void check(JavaClass apiType, ConditionEvents events) {
        exposures(apiType)
            .filter(exposure -> isInternal(exposure.type()))
            .map(exposure -> violation(apiType, exposure))
            .forEach(events::add);
      }
    };
  }

  /**
   * Every type named by the API type's supertypes, type parameters and visible members, including
   * the members it inherits.
   */
  private static Stream<Exposure> exposures(JavaClass apiType) {
    var supertypes =
        Stream.concat(apiType.getSuperclass().stream(), apiType.getInterfaces().stream())
            .flatMap(supertype -> namedTypes(supertype, "its supertypes"));
    var typeParameters =
        apiType.getTypeParameters().stream()
            .flatMap(typeParameter -> namedTypes(typeParameter, "its type parameters"));
    var members =
        apiType.getAllMembers().stream()
            .filter(PublicApiRule::isVisible)
            .flatMap(PublicApiRule::memberExposures);
    return Stream.of(supertypes, typeParameters, members).flatMap(Function.identity());
  }

  /** Every type a constructor, method or field names in its signature: bounds and throws too. */
  private static Stream<Exposure> memberExposures(JavaMember member) {
    var signatureTypes =
        member instanceof JavaCodeUnit codeUnit
            ? Stream.of(
                    Stream.of(codeUnit.getReturnType()),
                    codeUnit.getParameterTypes().stream(),
                    codeUnit.getTypeParameters().stream(),
                    codeUnit.getExceptionTypes().stream())
                .<JavaType>flatMap(Function.identity())
            : Stream.<JavaType>of(((JavaField) member).getType());
    return signatureTypes.flatMap(type -> namedTypes(type, member.getDescription()));
  }

  /** The classes a type names, including its type arguments and array components. */
  private static Stream<Exposure> namedTypes(JavaType type, String where) {
    return type.getAllInvolvedRawTypes().stream().map(named -> new Exposure(where, named));
  }

  /** Whether a member is public or protected and was written in the source, not generated. */
  private static boolean isVisible(JavaMember member) {
    var modifiers = member.getModifiers();
    return modifiers.stream().anyMatch(VISIBLE::contains)
        && modifiers.stream().noneMatch(GENERATED::contains);
  }

  /** Whether the type, or a class it is nested in, is marked internal. */
  private static boolean isInternal(JavaClass type) {
    return withEnclosingClasses(type).anyMatch(PublicApiRule::isLabelledInternal);
  }

  /** Whether the class itself carries the label. */
  private static boolean isLabelledInternal(JavaClass type) {
    return type.isAnnotatedWith(StratumInternal.class);
  }

  /**
   * Whether a service's code can reach the class itself: a public top-level class, or a nested one
   * that is public, or protected and so open to the service's subclasses.
   */
  private static boolean isReachable(JavaClass type) {
    var modifiers = type.getModifiers();
    return type.getEnclosingClass().isPresent()
        ? modifiers.stream().anyMatch(VISIBLE::contains)
        : modifiers.contains(JavaModifier.PUBLIC);
  }

  /** The type, then each class it is nested in, out to the top-level class. */
  private static Stream<JavaClass> withEnclosingClasses(JavaClass type) {
    return Stream.iterate(type, Objects::nonNull, PublicApiRule::enclosingClassOrNull);
  }

  /** The class the type is nested in, or {@code null} for a top-level class. */
  private static @Nullable JavaClass enclosingClassOrNull(JavaClass type) {
    return type.getEnclosingClass().orElse(null);
  }

  /** The failure line for one internal type that an API type exposes. */
  private static ConditionEvent violation(JavaClass apiType, Exposure exposure) {
    return SimpleConditionEvent.violated(
        apiType,
        apiType.getName()
            + " exposes the internal type "
            + exposure.type().getName()
            + " in "
            + exposure.where());
  }

  /**
   * One type that an API type names in its signature.
   *
   * @param where the supertypes, or the member that names it
   * @param type the type it names
   */
  private record Exposure(String where, JavaClass type) {}
}
