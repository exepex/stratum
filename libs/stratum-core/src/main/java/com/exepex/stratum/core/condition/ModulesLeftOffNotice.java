package com.exepex.stratum.core.condition;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.ConditionAndOutcomes;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;

/**
 * Logs one INFO line after startup that lists every Stratum module whose starter is present but
 * switched off, with the property that turns each one on, so a team that added a starter and sees
 * nothing happen knows why. It reads Spring Boot's condition report, so no module list is kept by
 * hand, and logs nothing when no module is off.
 */
@Slf4j
public class ModulesLeftOffNotice implements ApplicationListener<ApplicationStartedEvent> {

  /** Separates a class from its {@code @Bean} method in a condition report source. */
  private static final String METHOD_SEPARATOR = "#";

  /**
   * Logs the modules left off, if any.
   *
   * @param event the event of the started application
   */
  @Override
  public void onApplicationEvent(ApplicationStartedEvent event) {
    var beanFactory = event.getApplicationContext().getBeanFactory();
    var modulesLeftOff =
        modulesLeftOff(
            ConditionEvaluationReport.get(beanFactory), beanFactory.getBeanClassLoader());
    if (!modulesLeftOff.isEmpty() && log.isInfoEnabled()) {
      var switches =
          modulesLeftOff.stream()
              .map(
                  module ->
                      module + " (" + StratumModuleConstants.enabledProperty(module) + "=true)")
              .collect(Collectors.joining(", "));
      // Module names are kebab-case words; this also satisfies SpotBugs.
      log.info(("Stratum modules present but off: " + switches).replaceAll("[\r\n]", " "));
    }
  }

  /** Returns the names of the modules whose switch did not match, sorted and each once. */
  private SortedSet<String> modulesLeftOff(
      ConditionEvaluationReport report, @Nullable ClassLoader classLoader) {
    return report.getConditionAndOutcomesBySource().entrySet().stream()
        .filter(sourceOutcomes -> hasModuleSwitchOff(sourceOutcomes.getValue()))
        .map(Map.Entry::getKey)
        .map(source -> moduleOf(source, classLoader))
        .flatMap(Optional::stream)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /** Returns whether one of a source's conditions is a module switch that is off. */
  private boolean hasModuleSwitchOff(ConditionAndOutcomes outcomes) {
    return outcomes.stream()
        .anyMatch(
            outcome ->
                outcome.getCondition() instanceof OnStratumModuleCondition
                    && !outcome.getOutcome().isMatch());
  }

  /**
   * Returns the module named on a report source: a class, or a class and its {@code @Bean} method
   * written as {@code Class#method}.
   */
  private Optional<String> moduleOf(String source, @Nullable ClassLoader classLoader) {
    var classAndMethod = source.split(METHOD_SEPARATOR, 2);
    var type = ClassUtils.resolveClassName(classAndMethod[0], classLoader);
    AnnotatedElement annotated =
        classAndMethod.length == 1
            ? type
            : Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(classAndMethod[1]))
                .findFirst()
                .map(AnnotatedElement.class::cast)
                .orElse(type);
    return MergedAnnotations.from(annotated)
        .get(ConditionalOnStratumModule.class)
        .getValue(MergedAnnotation.VALUE, String.class);
  }
}
