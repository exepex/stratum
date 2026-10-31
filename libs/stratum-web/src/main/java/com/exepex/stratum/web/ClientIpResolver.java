package com.exepex.stratum.web;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Finds the IP address of the client that sent a request. Behind the service's own load balancers
 * and proxies, the {@code X-Forwarded-For} header is read from right to left, and the first address
 * that is not a trusted proxy is the client's. A request that does not come from a trusted proxy is
 * taken as it arrives, so a client cannot claim another address by sending the header itself.
 */
public class ClientIpResolver {

  /** The ranges of the trusted proxies. */
  private final List<AddressRange> trustedProxies;

  /**
   * Makes the resolver.
   *
   * @param settings the web settings, whose trusted proxy ranges it uses
   * @throws StratumException {@code SX-CORE-1003} if a range is not an IP address with a prefix
   *     length that fits it
   */
  public ClientIpResolver(WebProperties settings) {
    this.trustedProxies =
        settings.clientIp().trustedProxies().stream().map(AddressRange::parse).toList();
  }

  /**
   * Returns the client's IP address, or nothing when the socket's address is unknown.
   *
   * @param socketAddress the address the request came from, such as the load balancer's
   * @param forwardedFor the addresses in the request's {@code X-Forwarded-For} headers, left to
   *     right
   * @return the client's address, as the request or a trusted proxy gave it
   */
  public Optional<InetAddress> clientIp(
      @Nullable InetAddress socketAddress, List<String> forwardedFor) {
    if (socketAddress == null) {
      return Optional.empty();
    }
    var client = socketAddress;
    // Right to left, each trusted hop names the one before it, until an address is not a proxy. The
    // walk stops early and depends on the previous step, which a loop shows more plainly than a
    // stream.
    for (var hop = forwardedFor.size() - 1; hop >= 0 && isTrustedProxy(client); hop--) {
      var forwardedAddress = parseAddress(forwardedFor.get(hop));
      if (forwardedAddress.isEmpty()) {
        break;
      }
      client = forwardedAddress.get();
    }
    return Optional.of(client);
  }

  /** Returns whether the address is one of the trusted proxies. */
  private boolean isTrustedProxy(InetAddress address) {
    return trustedProxies.stream().anyMatch(range -> range.contains(address));
  }

  /**
   * Returns the address an {@code X-Forwarded-For} entry holds, or nothing when it is not an IP
   * address, such as {@code unknown} or a forged value. It never looks up a host name.
   */
  private Optional<InetAddress> parseAddress(String entry) {
    try {
      return Optional.of(InetAddress.ofLiteral(entry));
    } catch (IllegalArgumentException notAnAddress) {
      return Optional.empty();
    }
  }

  /**
   * A range of IP addresses, such as {@code 10.0.0.0/8}.
   *
   * @param network the range's first address
   * @param prefixLength how many leading bits every address in the range shares with it
   */
  private record AddressRange(InetAddress network, int prefixLength) {

    /**
     * Returns the range a setting holds, or stops startup.
     *
     * @param range the setting's value, such as {@code 10.0.0.0/8}
     * @return the range
     */
    static AddressRange parse(String range) {
      // The value is not shown: the property name is enough to find it.
      return parsedRange(range)
          .filter(AddressRange::prefixFitsAddress)
          .orElseThrow(
              () ->
                  new StratumException(
                      CoreErrorCode.INVALID_SETTINGS,
                      WebConstants.CLIENT_IP_TRUSTED_PROXIES
                          + " holds a range that is not an IP address and a prefix length that"
                          + " fits it"));
    }

    /** Returns the address and prefix length in a range, or nothing when they cannot be read. */
    private static Optional<AddressRange> parsedRange(String range) {
      var slash = range.indexOf('/');
      try {
        return Optional.of(
            new AddressRange(
                InetAddress.ofLiteral(range.substring(0, slash)),
                Integer.parseInt(range.substring(slash + 1))));
      } catch (IllegalArgumentException | IndexOutOfBoundsException unreadableRange) {
        return Optional.empty();
      }
    }

    /** Returns whether the prefix length is at most the address's length in bits. */
    private boolean prefixFitsAddress() {
      return prefixLength >= 0 && prefixLength <= network.getAddress().length * Byte.SIZE;
    }

    /**
     * Returns whether the address is in the range; an IPv4 address is never in an IPv6 range.
     *
     * @param address the address
     * @return whether it is in the range
     */
    boolean contains(InetAddress address) {
      var rangeBytes = network.getAddress();
      var addressBytes = address.getAddress();
      if (rangeBytes.length != addressBytes.length) {
        return false;
      }
      return IntStream.range(0, prefixLength)
          .allMatch(bit -> bitAt(rangeBytes, bit) == bitAt(addressBytes, bit));
    }

    /** Returns one bit of an address, counted from the left. */
    private int bitAt(byte[] address, int bit) {
      return (address[bit / Byte.SIZE] >> (Byte.SIZE - 1 - bit % Byte.SIZE)) & 1;
    }
  }
}
