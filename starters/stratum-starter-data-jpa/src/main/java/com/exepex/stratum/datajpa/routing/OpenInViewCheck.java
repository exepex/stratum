package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.datajpa.DataJpaConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Stops a servlet web application at startup when repositories are mapped to named pools and
 * open-in-view is on: the request's entity manager would hold one pool's connection across every
 * call of the request, outside any transaction.
 */
@StratumInternal
@RequiredArgsConstructor
public final class OpenInViewCheck implements InitializingBean {

  /** Shows whether repositories are mapped. */
  private final BeanFactory beanFactory;

  /** The application's environment, with the open-in-view key. */
  private final Environment environment;

  /**
   * Checks open-in-view, which Spring Boot turns on when the key is not set.
   *
   * @throws StratumException {@code SX-DATA-1009} if it is on while repositories are mapped
   */
  @Override
  public void afterPropertiesSet() {
    if (beanFactory.containsBean(DataJpaConstants.REPOSITORY_POOLS)
        && Boolean.TRUE.equals(
            environment.getProperty(DataJpaConstants.OPEN_IN_VIEW, Boolean.class, Boolean.TRUE))) {
      throw new StratumException(
          DataErrorCode.OPEN_IN_VIEW_WITH_ROUTING,
          DataJpaConstants.OPEN_IN_VIEW
              + " is true or not set, so each web request would hold one pool's connection");
    }
  }
}
