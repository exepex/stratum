package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import java.net.InetAddress;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Reads an IP address as a proxy or server writes it, with or without a port: {@code 203.0.113.7},
 * {@code 203.0.113.7:51234}, {@code 2001:db8::1}, {@code [2001:db8::1]} or {@code
 * [2001:db8::1]:443}. It never looks up a host name.
 */
@StratumInternal
@UtilityClass
public class IpAddressParser {

  /** The highest port number. */
  private static final int MAX_PORT = 65_535;

  /** The most digits a port has. */
  private static final int MAX_PORT_DIGITS = 5;

  /**
   * Returns the address the text holds, or nothing when it is not an IP address with an optional
   * port, such as {@code unknown}, a host name or an address with port {@code 99999}.
   *
   * @param text the address as the proxy or server wrote it
   * @return the address, without its port
   */
  public Optional<InetAddress> addressOf(String text) {
    try {
      return Optional.of(InetAddress.ofLiteral(withoutPort(text)));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  /**
   * Returns the text without its port. Only an IPv4 address, with a single colon, or an IPv6
   * address in brackets can carry one; an IPv6 address without brackets is read whole. A text whose
   * port is not a number from 0 to 65535 is returned whole, so it is not an address.
   */
  private String withoutPort(String text) {
    var portColon = text.lastIndexOf(':');
    var canCarryPort =
        portColon > 0 && (text.charAt(portColon - 1) == ']' || text.indexOf(':') == portColon);
    return canCarryPort && isPort(text.substring(portColon + 1))
        ? text.substring(0, portColon)
        : text;
  }

  /** Returns whether the text is a port: one to five ASCII digits, at most 65535. */
  private boolean isPort(String text) {
    return !text.isEmpty()
        && text.length() <= MAX_PORT_DIGITS
        && text.chars().allMatch(character -> character >= '0' && character <= '9')
        && Integer.parseInt(text) <= MAX_PORT;
  }
}
