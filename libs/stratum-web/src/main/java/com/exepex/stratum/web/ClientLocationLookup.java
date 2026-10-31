package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.StratumException;
import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the country and city of a client's IP address in the MaxMind database the service's team
 * provides. Private and unknown addresses have no location, and then no field is logged.
 */
@Slf4j
@RequiredArgsConstructor
public class ClientLocationLookup implements AutoCloseable {

  /** The member of a database record that holds the country. */
  private static final String COUNTRY = "country";

  /** The member of a country that holds its two-letter ISO code. */
  private static final String ISO_CODE = "iso_code";

  /** The member of a database record that holds the city. */
  private static final String CITY = "city";

  /** The member of a city that holds its names, by language. */
  private static final String NAMES = "names";

  /** The language of the city name that is logged. */
  private static final String ENGLISH = "en";

  /** The open database. */
  private final Reader database;

  /**
   * Opens the database. The file is mapped into memory, not read onto the heap.
   *
   * @param databaseFile the {@code .mmdb} file
   * @return the lookup
   * @throws StratumException {@code SX-WEB-1006} if the file cannot be read as a MaxMind database
   */
  public static ClientLocationLookup open(Path databaseFile) {
    try {
      return new ClientLocationLookup(new Reader(databaseFile.toFile(), new CHMCache()));
    } catch (IOException unreadable) {
      throw new StratumException(
          WebErrorCode.CLIENT_LOCATION_DATABASE_UNREADABLE,
          "check the file in " + WebConstants.CLIENT_LOCATION_DATABASE,
          unreadable);
    }
  }

  /**
   * Returns the country and city fields of an address. A value the database does not hold is left
   * out.
   *
   * @param clientIp the client's IP address
   * @return the fields, by their names in the logging context
   */
  public Map<String, String> fieldsOf(InetAddress clientIp) {
    var locationRecord = recordOf(clientIp);
    var location = new HashMap<String, String>();
    textAt(locationRecord, COUNTRY, ISO_CODE)
        .ifPresent(country -> location.put(LoggingContextConstants.CLIENT_COUNTRY, country));
    textAt(locationRecord, CITY, NAMES, ENGLISH)
        .ifPresent(city -> location.put(LoggingContextConstants.CLIENT_CITY, city));
    return location;
  }

  /**
   * Closes the database when the application stops.
   *
   * @throws IOException if the file cannot be closed
   */
  @Override
  public void close() throws IOException {
    database.close();
  }

  /** Returns the database's record of an address, which is empty when it has none. */
  private Map<?, ?> recordOf(InetAddress clientIp) {
    try {
      return Objects.requireNonNullElse(database.get(clientIp, Map.class), Map.of());
    } catch (IOException damagedDatabase) {
      // The file was checked when it was opened; a damaged record costs only this location.
      log.debug("No client location: the MaxMind database could not be read", damagedDatabase);
      return Map.of();
    }
  }

  /** Returns the text at a path of member names in a record, such as city, names, en. */
  private Optional<String> textAt(Map<?, ?> locationRecord, String... memberNames) {
    Optional<Object> member = Optional.of(locationRecord);
    // Each step reads the map the previous one found, which a loop shows more plainly.
    for (var memberName : memberNames) {
      member =
          member
              .filter(Map.class::isInstance)
              .map(members -> ((Map<?, ?>) members).get(memberName));
    }
    return member.filter(String.class::isInstance).map(String.class::cast);
  }
}
