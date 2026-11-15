package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.LogLines;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * Checks at startup that every mapped pool runs main's database product, read from the pools' JDBC
 * URLs with no connection, since Hibernate speaks one dialect, the one of main. Then it logs, for
 * each mapped pool, the packages and the annotated interfaces mapped to it.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public final class RoutingCheck implements SmartInitializingSingleton {

  /** The products a URL does not show, which the check skips. */
  private static final Set<DatabaseDriver> UNKNOWN_PRODUCTS =
      EnumSet.of(DatabaseDriver.UNKNOWN, DatabaseDriver.TESTCONTAINERS, DatabaseDriver.AWS_WRAPPER);

  /** The room a startup line starts with, enough for most lines. */
  private static final int LINE_CAPACITY = 256;

  /** How many names a startup line shows before it counts the rest. */
  private static final int SHOWN_NAMES = 3;

  /** Finds the pools' connection details. */
  private final ListableBeanFactory beanFactory;

  /** Each mapped repository's pool. */
  private final RepositoryPools pools;

  /**
   * Checks each mapped pool's product, then logs the mapping.
   *
   * @throws StratumException {@code SX-DATA-1007} if a mapped pool runs another product than main
   */
  @Override
  public void afterSingletonsInstantiated() {
    var main = beanFactory.getBeanProvider(JdbcConnectionDetails.class).getIfUnique();
    var mainProduct = main == null ? DatabaseDriver.UNKNOWN : productOf(main);
    pools.targets().forEach(pool -> checkPool(pool, mainProduct));
  }

  /** Checks one mapped pool's product, then logs its mapping. */
  private void checkPool(String pool, DatabaseDriver mainProduct) {
    var details =
        beanFactory.getBean(pool + DataConstants.DETAILS_SUFFIX, JdbcConnectionDetails.class);
    checkProduct(pool, productOf(details), mainProduct);
    LogLines.info(log, () -> line(pool));
  }

  /** Fails when both products are known and differ. */
  private void checkProduct(String pool, DatabaseDriver product, DatabaseDriver mainProduct) {
    if (UNKNOWN_PRODUCTS.contains(product) || UNKNOWN_PRODUCTS.contains(mainProduct)) {
      LogLines.debug(
          log,
          () ->
              "Pool '%s' was not checked against main's database product: a URL does not show it"
                  .formatted(pool));
    } else if (product != mainProduct) {
      throw new StratumException(
          DataErrorCode.ROUTING_INVALID,
          "the pool %s runs %s, but main runs %s".formatted(pool, product, mainProduct));
    }
  }

  /** Returns the product a pool's JDBC URL names. */
  private DatabaseDriver productOf(JdbcConnectionDetails details) {
    return DatabaseDriver.fromJdbcUrl(details.getJdbcUrl());
  }

  /** Returns the startup line of one pool, with its packages and annotated interfaces. */
  private String line(String pool) {
    var packages = pools.packages().getOrDefault(pool, List.of());
    var annotated = pools.annotated().getOrDefault(pool, List.of());
    var line =
        new StringBuilder(LINE_CAPACITY)
            .append("Pool '")
            .append(pool)
            .append("' serves repositories");
    append(line, " in ", packages, "package");
    append(line, packages.isEmpty() ? " in " : ", and ", annotated, "annotated interface");
    return line.toString();
  }

  /**
   * Appends a count of names, such as {@code 2 packages}, the first names, and how many more there
   * are, if there are any names.
   */
  private void append(StringBuilder line, String lead, List<String> names, String noun) {
    if (names.isEmpty()) {
      return;
    }
    line.append(lead)
        .append(names.size())
        .append(' ')
        .append(noun)
        .append(names.size() == 1 ? "" : "s")
        .append(": ")
        .append(String.join(", ", names.subList(0, Math.min(SHOWN_NAMES, names.size()))));
    if (names.size() > SHOWN_NAMES) {
      line.append(" and ").append(names.size() - SHOWN_NAMES).append(" more");
    }
  }
}
