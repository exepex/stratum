package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    classes = LeftoverFieldsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // One server thread, so the next request runs where the last one left its field.
      "server.tomcat.threads.max=1",
      "server.tomcat.threads.min-spare=1",
      "stratum.observability.enabled=false"
    })
@DisplayName("A field a Spring MVC handler leaves behind never reaches the next request")
class MdcCleanupIntegrationTest {

  /** The paths of a request that answers and of one that fails, each leaving a customer. */
  private static final List<String> LEAVING_REQUESTS =
      List.of("/customers/CUST-A", "/customers/CUST-B/failing");

  @LocalServerPort private int port;

  @ParameterizedTest
  @FieldSource("LEAVING_REQUESTS")
  @DisplayName("the next request on the same server thread starts with no customer")
  void nextRequestStartsClean(String leavingRequest) throws Exception {
    StarterTestSupport.getFromService(port, leavingRequest);

    assertThat(StarterTestSupport.getFromService(port, "/customer-on-thread").body())
        .isEqualTo(LeftoverFieldsApplication.NO_CUSTOMER);
  }
}
