package com.exepex.stratum.cache.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.context.ConfigurableApplicationContext;

/** The checks the cache module's auto-configuration tests share. */
@UtilityClass
public class CacheAssertions {

  /**
   * Checks that Spring Boot's condition report shows an auto-configuration left out as a whole, not
   * only some of its beans.
   *
   * @param context the started context
   * @param autoConfiguration the auto-configuration that must not have matched
   */
  public static void assertLeftOut(
      ConfigurableApplicationContext context, Class<?> autoConfiguration) {
    assertThat(
            ConditionEvaluationReport.get(context.getBeanFactory())
                .getConditionAndOutcomesBySource()
                .get(autoConfiguration.getName())
                .isFullMatch())
        .as("%s left out", autoConfiguration.getSimpleName())
        .isFalse();
  }
}
