package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.files.FilesApplication;
import com.exepex.stratum.restclient.testsupport.files.FilesClient;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The files client sends the service's own token and reads answers of at most 50 bytes, less than
 * the identity provider's token answer. A streamed download is not limited, but its token request
 * is.
 */
@SpringBootTest(
    classes = FilesApplication.class,
    properties = {
      RestClientTestData.FILES_SETTINGS + ".response-body-limit=50B",
      RestClientTestData.FILES_SETTINGS
          + ".auth.client-credentials="
          + RestClientTestData.REGISTRATION
    })
@Timeout(60)
@DisplayName("A streamed download's token request is limited by response-body-limit")
class DownloadTokenLimitIntegrationTest {

  /** An identity provider whose token answer is longer than the limit. */
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
  @DisplayName("so a token answer over the limit is not fetched, and the download is not sent")
  void tokenAnswerLimitedDuringDownload() {
    assertThatThrownBy(filesClient::download)
        .isInstanceOf(StratumException.class)
        .hasMessageStartingWith("SX-HTTPC-0002");
    assertThat(IDENTITY_PROVIDER.tokenRequests()).isPositive();
    assertThat(IDENTITY_PROVIDER.authorizations()).isEmpty();
  }
}
