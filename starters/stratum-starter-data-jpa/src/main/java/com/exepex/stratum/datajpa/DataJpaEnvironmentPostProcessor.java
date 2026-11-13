package com.exepex.stratum.datajpa;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.BackwardCompatibleMode;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.data.DataConstants;
import java.util.Map;
import org.hibernate.cfg.BatchSettings;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.QuerySettings;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Gives Spring Boot's JPA settings Stratum's defaults unless {@code stratum.data.enabled=false}. In
 * both modes, Hibernate sends inserts and updates in ordered batches of 50, pads {@code IN} lists
 * and logs no JDBC errors or warnings of its own. With {@code stratum.backward-compatible=false},
 * its default, open-in-view is off and paging a fetch join over a collection fails. Every value the
 * service sets for these Spring Boot keys wins.
 */
@StratumInternal
public class DataJpaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** The defaults that change nothing a consumer of the service sees, so apply in both modes. */
  private static final Map<String, Object> INTERNAL_DEFAULTS =
      Map.of(
          DataJpaConstants.JPA_PROPERTIES + QuerySettings.IN_CLAUSE_PARAMETER_PADDING,
          true,
          DataJpaConstants.JPA_PROPERTIES + BatchSettings.STATEMENT_BATCH_SIZE,
          50,
          DataJpaConstants.JPA_PROPERTIES + BatchSettings.ORDER_INSERTS,
          true,
          DataJpaConstants.JPA_PROPERTIES + BatchSettings.ORDER_UPDATES,
          true,
          DataJpaConstants.JPA_PROPERTIES + JdbcSettings.LOG_JDBC_ERRORS,
          false,
          DataJpaConstants.JPA_PROPERTIES + JdbcSettings.LOG_JDBC_WARNINGS,
          false);

  /** The defaults that can change what a consumer sees, so apply only outside compatible mode. */
  private static final Map<String, Object> EXTERNAL_DEFAULTS =
      Map.of(
          DataJpaConstants.OPEN_IN_VIEW,
          false,
          DataJpaConstants.JPA_PROPERTIES + QuerySettings.FAIL_ON_PAGINATION_OVER_COLLECTION_FETCH,
          true);

  /**
   * Runs right after the configuration files are read, so that the module switch and the mode are
   * known.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  /**
   * Adds the defaults below the service's own values: the internal ones when the module is on, and
   * the external ones when {@code stratum.backward-compatible} is off too.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!StratumModuleSwitch.isEnabled(environment, DataConstants.MODULE)) {
      return;
    }
    StratumDefaults.addBelowServiceValues(environment, INTERNAL_DEFAULTS);
    if (!BackwardCompatibleMode.isOn(environment)) {
      StratumDefaults.addBelowServiceValues(environment, EXTERNAL_DEFAULTS);
    }
  }
}
