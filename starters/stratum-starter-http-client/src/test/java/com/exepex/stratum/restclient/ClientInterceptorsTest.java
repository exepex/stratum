package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.AuditInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.SigningInterceptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

@DisplayName(
    "ClientInterceptors finds each client's interceptors, creating none while clients build")
class ClientInterceptorsTest {

  /** The name of the signing interceptor's bean. */
  private static final String SIGNING = "signing";

  /** The client the tests ask about. */
  private static final String ORDERS = "orders";

  /** The interceptor type the orders client names. */
  private static final Map<String, List<Class<? extends ClientInterceptor>>> NAMED =
      Map.of(ORDERS, List.of(SigningInterceptor.class));

  /** The application's beans. */
  private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

  /** How many interceptor beans were created. */
  private final AtomicInteger created = new AtomicInteger();

  /** Declares an audit interceptor bean, counting each one created. */
  private void declareAudit(String name) {
    beans.registerBeanDefinition(
        name,
        new RootBeanDefinition(
            AuditInterceptor.class, () -> counted(new AuditInterceptor(new CallLog()))));
  }

  /** Declares a signing interceptor bean, counting each one created. */
  private void declareSigning(String name) {
    beans.registerBeanDefinition(
        name,
        new RootBeanDefinition(
            SigningInterceptor.class, () -> counted(new SigningInterceptor(new CallLog()))));
  }

  /** Counts one interceptor created and returns it. */
  private <T extends ClientInterceptor> T counted(T interceptor) {
    created.incrementAndGet();
    return interceptor;
  }

  @Nested
  @DisplayName("while the clients are built")
  class WhileBuilding {

    @Test
    @DisplayName("it tells which clients have interceptors without creating any")
    void decidesFromDefinitions() {
      declareAudit("audit");
      declareSigning(SIGNING);
      var interceptors = new ClientInterceptors(NAMED, beans);

      assertThat(interceptors.hasInterceptors(ORDERS)).isTrue();
      assertThat(interceptors.hasInterceptors("billing")).isTrue();
      assertThat(created).hasValue(0);
    }

    @Test
    @DisplayName("with no global interceptor, a client that names none has none")
    void noneWithoutGlobal() {
      declareSigning(SIGNING);

      assertThat(new ClientInterceptors(NAMED, beans).hasInterceptors("billing")).isFalse();
    }

    @Test
    @DisplayName("a named type with no bean stops startup with Spring's own error")
    void missingBeanStopsStartup() {
      var interceptors = new ClientInterceptors(NAMED, beans);

      assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
          .isThrownBy(() -> interceptors.hasInterceptors(ORDERS));
    }

    @Test
    @DisplayName("a named type with two beans stops startup with Spring's own error")
    void twoBeansStopStartup() {
      declareSigning(SIGNING);
      declareSigning("otherSigning");
      var interceptors = new ClientInterceptors(NAMED, beans);

      assertThatExceptionOfType(NoUniqueBeanDefinitionException.class)
          .isThrownBy(() -> interceptors.hasInterceptors(ORDERS));
    }
  }

  @Nested
  @DisplayName("on a client's first call")
  class OnFirstCall {

    @Test
    @DisplayName("it creates the client's interceptors: the global ones, then its own")
    void createsInterceptors() {
      declareSigning(SIGNING);
      declareAudit("audit");

      var found = new ClientInterceptors(NAMED, beans).interceptorsOf(ORDERS);

      assertThat(found).hasExactlyElementsOfTypes(AuditInterceptor.class, SigningInterceptor.class);
    }
  }
}
