package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.it.orders.OrdersApplication;
import com.exepex.stratum.data.it.pools.PoolsApplication;
import com.exepex.stratum.data.testsupport.PoolDatabases;
import com.exepex.stratum.data.testsupport.ServiceDataSources;
import com.exepex.stratum.datajpa.DataJpaConstants;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("On PostgreSQL, routing starts only where it is safe, and stays out without a mapping")
class RoutingStartupIntegrationTest {

  /** The key of the mode in which open-in-view stays Spring Boot's default, on. */
  private static final String BACKWARD_COMPATIBLE = "stratum.backward-compatible=true";

  /** The key that picks a free port for a web server. */
  private static final String ANY_PORT = "server.port=0";

  /** Returns the service of the pools tests, mapped, with the pool batch's database. */
  private SpringApplicationBuilder mappedPools(WebApplicationType type) {
    return new SpringApplicationBuilder(
            PoolsApplication.class, PostgresContainerConfiguration.class)
        .initializers(new PoolDatabases.BatchDatabase())
        .web(type)
        .properties(PoolDatabases.MAPPING, PoolDatabases.SQL_INIT, PoolDatabases.SCHEMA, ANY_PORT);
  }

  /** Returns a service with no repository, so with no mapping, and more sources. */
  private SpringApplicationBuilder unmapped(Class<?>... sources) {
    return new SpringApplicationBuilder(
            OrdersApplication.class, PostgresContainerConfiguration.class)
        .sources(sources)
        .web(WebApplicationType.NONE)
        .properties(PoolDatabases.SQL_INIT);
  }

  /** Returns Spring Boot's db health check, which holds one entry per data source it checks. */
  private CompositeHealthContributor healthOf(ConfigurableApplicationContext context) {
    return context.getBean("dbHealthContributor", CompositeHealthContributor.class);
  }

  /** Checks that the application starts, and whether it has the routing data source. */
  private void assertStarts(SpringApplicationBuilder application, boolean routed) {
    try (var context = application.run()) {
      assertThat(context.containsBean(DataConstants.ROUTING_BEAN)).isEqualTo(routed);
    }
  }

  @Nested
  @DisplayName("open-in-view")
  class OpenInView {

    /** The application types that have no open-in-view at all. */
    private static final List<WebApplicationType> WITHOUT_OPEN_IN_VIEW =
        List.of(WebApplicationType.NONE, WebApplicationType.REACTIVE);

    @Test
    @DisplayName("on in a servlet application with a mapping stops startup with SX-DATA-1009")
    void servletWithMappingFails() {
      assertThatThrownBy(
              () -> mappedPools(WebApplicationType.SERVLET).properties(BACKWARD_COMPATIBLE).run())
          .hasStackTraceContaining(DataErrorCode.OPEN_IN_VIEW_WITH_ROUTING.getCode())
          .hasStackTraceContaining("spring.jpa.open-in-view is true or not set");
    }

    @Test
    @DisplayName("off in a servlet application with a mapping lets it start")
    void servletOffStarts() {
      assertStarts(
          mappedPools(WebApplicationType.SERVLET)
              .properties(BACKWARD_COMPATIBLE, "spring.jpa.open-in-view=false"),
          true);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("WITHOUT_OPEN_IN_VIEW")
    @DisplayName("left on, it lets an application with no servlet web layer start with a mapping")
    void otherTypesStart(WebApplicationType type) {
      assertStarts(mappedPools(type).properties(BACKWARD_COMPATIBLE), true);
    }
  }

  @Nested
  @DisplayName("without a mapping")
  class WithoutMapping {

    @Test
    @DisplayName("a service's own plain DataSource starts, with no routing data source")
    void ownDataSourceStarts() {
      assertStarts(unmapped(ServiceDataSources.Plain.class), false);
    }

    @Test
    @DisplayName("a service's own @Primary DataSource starts, with no routing data source")
    void ownPrimaryStarts() {
      assertStarts(unmapped(ServiceDataSources.OwnPrimary.class), false);
    }

    @Test
    @DisplayName("a service whose only data source is its own routing one starts, in db health")
    void ownRouterStarts() {
      try (var context = unmapped(ServiceDataSources.OwnRouter.class).run()) {
        assertThat(healthOf(context).getContributor(ServiceDataSources.ROUTER_KEY)).isNotNull();
        assertThat(context.getEnvironment().getProperty(DataJpaConstants.IGNORE_ROUTING_HEALTH))
            .isNull();
      }
    }

    @Test
    @DisplayName("a servlet application with open-in-view on starts")
    void servletOpenInViewStarts() {
      assertStarts(
          unmapped().web(WebApplicationType.SERVLET).properties(BACKWARD_COMPATIBLE, ANY_PORT),
          false);
    }
  }

  @Nested
  @DisplayName("the db health check, with a mapping")
  class Health {

    @Test
    @DisplayName("leaves out the routing data source by default")
    void skipsRouter() {
      try (var context = mappedPools(WebApplicationType.NONE).run()) {
        assertThat(healthOf(context).getContributor(DataConstants.ROUTING_BEAN)).isNull();
      }
    }

    @Test
    @DisplayName("lists it when the service sets the key to false, whose value wins")
    void serviceValueWins() {
      var application =
          mappedPools(WebApplicationType.NONE)
              .properties(DataJpaConstants.IGNORE_ROUTING_HEALTH + "=false");

      try (var context = application.run()) {
        assertThat(healthOf(context).getContributor(DataConstants.ROUTING_BEAN)).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("the schema")
  class Schema {

    @Test
    @DisplayName("a mapped entity's table missing from main stops startup with SX-DATA-1001")
    void tableOnlyOnPool(CapturedOutput output) {
      var application =
          mappedPools(WebApplicationType.NONE).properties("spring.sql.init.mode=never");

      assertThatThrownBy(application::run).isInstanceOf(RuntimeException.class);

      assertThat(output.getOut())
          .contains(DataErrorCode.SCHEMA_MISMATCH.getCode())
          .contains("missing table [notes]");
    }
  }
}
