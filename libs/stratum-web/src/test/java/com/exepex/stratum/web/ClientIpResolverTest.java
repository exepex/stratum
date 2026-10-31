package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("ClientIpResolver finds the client's IP address behind trusted proxies")
class ClientIpResolverTest {

  /** A resolver with the default trusted proxies: the private and loopback ranges. */
  private final ClientIpResolver resolver = new ClientIpResolver(WebTestData.defaults().build());

  /** A load balancer in the service's own network. */
  private static final InetAddress LOAD_BALANCER =
      InetAddress.ofLiteral(StarterTestSupport.ipAddress(10, 0, 0, 5));

  /** The address of a client on the internet. */
  private static final String INTERNET_CLIENT_ADDRESS =
      StarterTestSupport.ipAddress(198, 51, 100, 20);

  /** A client on the internet. */
  private static final InetAddress INTERNET_CLIENT = InetAddress.ofLiteral(INTERNET_CLIENT_ADDRESS);

  /** Another address on the internet, which the client can write into the header itself. */
  private static final String SPOOFED_ADDRESS = StarterTestSupport.ipAddress(203, 0, 113, 1);

  /** A proxy inside the service's own network. */
  private static final String INNER_PROXY_ADDRESS = StarterTestSupport.ipAddress(10, 0, 0, 9);

  /** A private address a client claims to have. */
  private static final String CLAIMED_ADDRESS = StarterTestSupport.ipAddress(10, 9, 9, 9);

  /** A client inside an office network, behind private proxies only. */
  private static final String OFFICE_CLIENT_ADDRESS = StarterTestSupport.ipAddress(192, 168, 1, 2);

  /** The IPv6 loopback address. */
  private static final String IPV6_LOOPBACK = StarterTestSupport.ipAddress(0, 0, 0, 0, 0, 0, 0, 1);

  /** A client on the internet with an IPv6 address. */
  private static final String IPV6_CLIENT_ADDRESS =
      StarterTestSupport.ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);

  @Nested
  @DisplayName("with the default trusted proxies")
  class DefaultProxies {

    @Test
    @DisplayName("a request with no X-Forwarded-For comes from its socket's address")
    void socketAddressWithoutHeader() {
      assertThat(resolver.clientIp(LOAD_BALANCER, List.of())).hasValue(LOAD_BALANCER);
    }

    @Test
    @DisplayName("behind a trusted proxy, the last untrusted address in the header is the client")
    void lastUntrustedForwardedAddress() {
      var client =
          resolver.clientIp(
              LOAD_BALANCER,
              List.of(SPOOFED_ADDRESS, INTERNET_CLIENT_ADDRESS, INNER_PROXY_ADDRESS));

      assertThat(client).hasValue(INTERNET_CLIENT);
    }

    @Test
    @DisplayName("a client that is not a trusted proxy cannot claim another address")
    void untrustedSocketIgnoresHeader() {
      assertThat(resolver.clientIp(INTERNET_CLIENT, List.of(CLAIMED_ADDRESS)))
          .hasValue(INTERNET_CLIENT);
    }

    @Test
    @DisplayName("when every hop is trusted, the first one in the header is the client")
    void allHopsTrusted() {
      var client =
          resolver.clientIp(LOAD_BALANCER, List.of(OFFICE_CLIENT_ADDRESS, INNER_PROXY_ADDRESS));

      assertThat(client).hasValue(InetAddress.ofLiteral(OFFICE_CLIENT_ADDRESS));
    }

    @Test
    @DisplayName("an entry that is not an IP address stops the walk at the last good hop")
    void malformedEntryStopsWalk() {
      var client = resolver.clientIp(LOAD_BALANCER, List.of(INTERNET_CLIENT_ADDRESS, "unknown"));

      assertThat(client).hasValue(LOAD_BALANCER);
    }

    @Test
    @DisplayName("an IPv6 loopback proxy is trusted, and IPv4 ranges never match IPv6")
    void ipv6() {
      var client =
          resolver.clientIp(InetAddress.ofLiteral(IPV6_LOOPBACK), List.of(IPV6_CLIENT_ADDRESS));

      assertThat(client).hasValue(InetAddress.ofLiteral(IPV6_CLIENT_ADDRESS));
    }

    @Test
    @DisplayName("an unknown socket address gives no client IP")
    void unknownSocket() {
      assertThat(resolver.clientIp(null, List.of(INTERNET_CLIENT_ADDRESS))).isEmpty();
    }
  }

  @Nested
  @DisplayName("with the service's own trusted proxies")
  class OwnProxies {

    /** Ranges that are not address ranges: no slash, a bad address, a prefix too long. */
    private static final List<String> BAD_RANGES =
        List.of(INNER_PROXY_ADDRESS, "10.0.0.999/8", "10.0.0.0/33");

    @Test
    @DisplayName("an empty list trusts no proxy, so the socket's address is the client")
    void noTrustedProxies() {
      var resolverWithoutProxies =
          new ClientIpResolver(
              WebTestData.defaults()
                  .clientIp(
                      WebProperties.ClientIpSettings.builder().trustedProxies(List.of()).build())
                  .build());

      assertThat(resolverWithoutProxies.clientIp(LOAD_BALANCER, List.of(INTERNET_CLIENT_ADDRESS)))
          .hasValue(LOAD_BALANCER);
    }

    @ParameterizedTest
    @FieldSource("BAD_RANGES")
    @DisplayName("a range that is not an address and prefix length stops startup with SX-CORE-1003")
    void badRangeStopsStartup(String badRange) {
      var settings =
          WebTestData.defaults()
              .clientIp(
                  WebProperties.ClientIpSettings.builder()
                      .trustedProxies(List.of(badRange))
                      .build())
              .build();

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> new ClientIpResolver(settings))
          .withMessageStartingWith("SX-CORE-1003")
          .withMessageContaining(WebConstants.CLIENT_IP_TRUSTED_PROXIES)
          .withMessageNotContaining(badRange);
    }
  }
}
