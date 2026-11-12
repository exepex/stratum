package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.BackwardCompatibleMode;
import com.exepex.stratum.core.properties.StratumDefaults;
import java.util.Map;
import org.hibernate.cfg.BatchSettings;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.QuerySettings;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Gives Spring Boot's pool, JPA and transaction settings Stratum's defaults unless {@code
 * stratum.data.enabled=false}. In both modes, a caller waits at most 3 seconds for a connection,
 * Hibernate sends inserts and updates in ordered batches of 50 and pads {@code IN} lists, and
 * Hibernate logs no JDBC errors or warnings of its own. With {@code
 * stratum.backward-compatible=false}, its default, open-in-view is off, paging a fetch join over a
 * collection fails, and every transaction has 60 seconds, except when Spring Batch is present,
 * whose jobs run longer. Every value the service sets for these Spring Boot keys wins. In both
 * modes, startup stops when the service sets a leak alarm threshold that HikariCP would turn off.
 */
@StratumInternal
public class DataEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** The defaults that change nothing a consumer of the service sees, so apply in both modes. */
  private static final Map<String, Object> INTERNAL_DEFAULTS =
      Map.of(
          DataConstants.CONNECTION_TIMEOUT,
          3000,
          DataConstants.JPA_PROPERTIES + QuerySettings.IN_CLAUSE_PARAMETER_PADDING,
          true,
          DataConstants.JPA_PROPERTIES + BatchSettings.STATEMENT_BATCH_SIZE,
          50,
          DataConstants.JPA_PROPERTIES + BatchSettings.ORDER_INSERTS,
          true,
          DataConstants.JPA_PROPERTIES + BatchSettings.ORDER_UPDATES,
          true,
          DataConstants.JPA_PROPERTIES + JdbcSettings.LOG_JDBC_ERRORS,
          false,
          DataConstants.JPA_PROPERTIES + JdbcSettings.LOG_JDBC_WARNINGS,
          false);

  /** The defaults that can change what a consumer sees, so apply only outside compatible mode. */
  private static final Map<String, Object> EXTERNAL_DEFAULTS =
      Map.of(
          DataConstants.OPEN_IN_VIEW,
          false,
          DataConstants.JPA_PROPERTIES + QuerySettings.FAIL_ON_PAGINATION_OVER_COLLECTION_FETCH,
          true);

  /** The transaction time limit, external too, which Spring Batch jobs would outlive. */
  private static final Map<String, Object> TRANSACTION_TIMEOUT =
      Map.of(DataConstants.TRANSACTION_TIMEOUT, "60s");

  /** Checks the leak alarm threshold the service set. */
  private final LeakAlarmCheck leakAlarmCheck = new LeakAlarmCheck();

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
   * the external ones when {@code stratum.backward-compatible} is off too. Then it checks the leak
   * alarm threshold the service set.
   *
   * @param environment the application's environment
   * @param application the application being started, whose class loader shows Spring Batch
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-DATA-1002} if HikariCP would
   *     turn the leak alarm off
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!StratumModuleSwitch.isEnabled(environment, DataConstants.MODULE)) {
      return;
    }
    StratumDefaults.addBelowServiceValues(environment, INTERNAL_DEFAULTS);
    if (!BackwardCompatibleMode.isOn(environment)) {
      addExternalDefaults(environment, application);
    }
    leakAlarmCheck.check(environment);
  }

  /**
   * Adds the defaults a consumer can see, and the transaction limit unless Spring Batch is present.
   */
  private void addExternalDefaults(
      ConfigurableEnvironment environment, SpringApplication application) {
    StratumDefaults.addBelowServiceValues(environment, EXTERNAL_DEFAULTS);
    if (!ClassUtils.isPresent(DataConstants.SPRING_BATCH_JOB, application.getClassLoader())) {
      StratumDefaults.addBelowServiceValues(environment, TRANSACTION_TIMEOUT);
    }
  }
}
