package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.test.WebTestSupport;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("IpAddressParser reads an IP address with or without a port")
class IpAddressParserTest {

  /** A client's IPv4 address. */
  private static final String IPV4 = WebTestSupport.ipAddress(203, 0, 113, 7);

  /** A client's IPv6 address, as Java writes it. */
  private static final String IPV6 = WebTestSupport.ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);

  /** The same IPv6 address in its short form, as proxies write it. */
  private static final String IPV6_SHORT = String.join(":", "2001", "db8", "", "1");

  /** The IPv6 address that is all zeros, as Java writes it. */
  private static final String IPV6_ZERO = WebTestSupport.ipAddress(0, 0, 0, 0, 0, 0, 0, 0);

  /** A port after an IPv6 address in brackets. */
  private static final String BRACKETED_PORT = "]:443";

  /** The IPv4 client's address mapped into IPv6. */
  private static final String IPV4_MAPPED = "::ffff:" + IPV4;

  @Nested
  @DisplayName("an address it reads")
  class Accepted {

    /** Each text with the address it holds, as Java writes that address. */
    private static final List<Arguments> ADDRESSES =
        List.of(
            arguments(IPV4, IPV4),
            arguments(IPV4 + ":51234", IPV4),
            arguments(IPV4 + ":0", IPV4),
            arguments(IPV4 + ":65535", IPV4),
            arguments(IPV4 + ":00443", IPV4),
            arguments(IPV6_SHORT, IPV6),
            arguments("[" + IPV6_SHORT + "]", IPV6),
            arguments("[" + IPV6_SHORT + BRACKETED_PORT, IPV6),
            arguments("::", IPV6_ZERO),
            arguments("[::]:443", IPV6_ZERO),
            arguments(IPV4_MAPPED, IPV4),
            arguments("[" + IPV4_MAPPED + BRACKETED_PORT, IPV4));

    /** IPv6 addresses without brackets whose last group looks like a port, read whole. */
    private static final List<Arguments> BARE_IPV6 =
        List.of(
            arguments(
                String.join(":", "", "", "1", "80"),
                WebTestSupport.ipAddress(0, 0, 0, 0, 0, 0, 1, 0x80)),
            arguments(
                IPV6_SHORT + ":443",
                WebTestSupport.ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 1, 0x443)));

    @ParameterizedTest
    @FieldSource("ADDRESSES")
    @DisplayName("an IPv4 address with a port, or an IPv6 address in brackets with one, is read")
    void readsAddressWithOrWithoutPort(String text, String address) {
      assertThat(IpAddressParser.addressOf(text)).hasValue(InetAddress.ofLiteral(address));
    }

    @ParameterizedTest
    @FieldSource("BARE_IPV6")
    @DisplayName("an IPv6 address without brackets is read whole, never as an address and port")
    void readsBareIpv6Whole(String text, String address) {
      assertThat(IpAddressParser.addressOf(text)).hasValue(InetAddress.ofLiteral(address));
    }
  }

  @Nested
  @DisplayName("a text it refuses")
  class Refused {

    /** Ports that are not a number from 0 to 65535 in at most five ASCII digits. */
    private static final List<String> BAD_PORTS =
        List.of(
            IPV4 + ":",
            IPV4 + ":65536",
            IPV4 + ":99999",
            IPV4 + ":123456",
            IPV4 + ":000080",
            IPV4 + ":http",
            IPV4 + ":+80",
            IPV4 + ":-1",
            IPV4 + ": 80",
            IPV4 + ":٨٠",
            IPV4 + ":80:90",
            "[" + IPV6_SHORT + "]:",
            "[" + IPV6_SHORT + "]:65536",
            "[" + IPV6_SHORT + "]:https");

    /** Brackets that do not hold exactly one IPv6 address. */
    private static final List<String> ODD_BRACKETS =
        List.of(
            "[" + IPV6_SHORT + ":443",
            IPV6_SHORT + BRACKETED_PORT,
            "[[" + IPV6_SHORT + "]]:443",
            "[" + IPV6_SHORT + "]443",
            "[]:443",
            BRACKETED_PORT,
            "[" + IPV4 + BRACKETED_PORT,
            "[" + IPV4 + "]");

    /** Texts that are not addresses at all. */
    private static final List<String> NOT_ADDRESSES =
        List.of("", "unknown", ":443", "orders.example.com:443", "_hidden", IPV4 + " ");

    @ParameterizedTest
    @FieldSource("BAD_PORTS")
    @DisplayName("an address whose port is not 0 to 65535 in up to five ASCII digits is not read")
    void refusesBadPort(String text) {
      assertThat(IpAddressParser.addressOf(text)).isEmpty();
    }

    @ParameterizedTest
    @FieldSource("ODD_BRACKETS")
    @DisplayName("brackets that do not hold one IPv6 address are not read")
    void refusesOddBrackets(String text) {
      assertThat(IpAddressParser.addressOf(text)).isEmpty();
    }

    @ParameterizedTest
    @FieldSource("NOT_ADDRESSES")
    @DisplayName("a text that is not an address, such as a host name, is not read or looked up")
    void refusesNonAddress(String text) {
      assertThat(IpAddressParser.addressOf(text)).isEmpty();
    }
  }
}
