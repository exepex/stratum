package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream.TokenAnswer;
import com.exepex.stratum.restclient.testsupport.files.FilesApplication;
import com.exepex.stratum.restclient.testsupport.files.FilesClient;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The files client sends the service's own token, with a {@code timeout} far above its {@code
 * total-timeout}. A streamed download keeps its full answer timeout, but its token request must
 * not.
 */
@SpringBootTest(
    classes = FilesApplication.class,
    properties = {
      RestClientTestData.FILES_SETTINGS + ".timeout=30s",
      RestClientTestData.FILES_SETTINGS + ".total-timeout=300ms",
      RestClientTestData.FILES_SETTINGS
          + ".auth.client-credentials="
          + RestClientTestData.REGISTRATION
    })
@Timeout(60)
@DisplayName("A streamed download's token request ends by the call's total-timeout")
class DownloadTokenIntegrationTest {

  /** An identity provider that does not answer. */
  @AutoClose private static final TokenDownstream IDENTITY_PROVIDER = new TokenDownstream();

  @Autowired private FilesClient filesClient;

  /**
   * Points the files client and the registration at the identity provider.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void serverAddresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.FILES_SETTINGS + ".url", IDENTITY_PROVIDER::url);
    RestClientTestData.pointRegistrationAtTokenServer(registry, IDENTITY_PROVIDER);
  }

  @Test
  @DisplayName("not by the client's timeout, which the download itself keeps")
  void tokenRequestEndsDuringDownload() {
    IDENTITY_PROVIDER.tokenAnswer(TokenAnswer.HOLD);
    var started = System.nanoTime();

    assertThatThrownBy(filesClient::download)
        .isInstanceOf(StratumException.class)
        .hasMessageStartingWith("SX-HTTPC-0002");
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(RestClientTestData.FAILS_WITHIN);
    assertThat(IDENTITY_PROVIDER.authorizations()).isEmpty();
  }
}
