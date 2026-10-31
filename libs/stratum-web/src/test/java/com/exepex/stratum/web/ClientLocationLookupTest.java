package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.StarterTestSupport;
import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ClientLocationLookup reads country and city from the team's MaxMind database")
class ClientLocationLookupTest {

  /** A client's address. */
  private static final InetAddress CLIENT =
      InetAddress.ofLiteral(StarterTestSupport.ipAddress(198, 51, 100, 20));

  @Test
  @DisplayName("reads the country's ISO code and the city's English name")
  void readsCountryAndCity() throws IOException {
    var database = mock(Reader.class);
    when(database.get(CLIENT, Map.class))
        .thenReturn(
            Map.of(
                "country", Map.of("iso_code", "NL"),
                "city", Map.of("names", Map.of("en", "Amsterdam", "de", "Amsterdam"))));

    assertThat(new ClientLocationLookup(database).fieldsOf(CLIENT))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                LoggingContextConstants.CLIENT_COUNTRY, "NL",
                LoggingContextConstants.CLIENT_CITY, "Amsterdam"));
  }

  @Test
  @DisplayName("an address the database does not hold, such as a private one, has no fields")
  void unknownAddress() throws IOException {
    var database = mock(Reader.class);
    when(database.get(CLIENT, Map.class)).thenReturn(null);

    assertThat(new ClientLocationLookup(database).fieldsOf(CLIENT)).isEmpty();
  }

  @Test
  @DisplayName("a Country database gives the country only")
  void countryOnly() throws IOException {
    var database = mock(Reader.class);
    when(database.get(CLIENT, Map.class)).thenReturn(Map.of("country", Map.of("iso_code", "NL")));

    assertThat(new ClientLocationLookup(database).fieldsOf(CLIENT))
        .containsOnlyKeys(LoggingContextConstants.CLIENT_COUNTRY);
  }

  @Test
  @DisplayName("a file that is not a MaxMind database stops startup with SX-WEB-1006")
  void unreadableFile(@TempDir Path folder) throws IOException {
    var textFile = Files.writeString(folder.resolve("city.mmdb"), "not a database");

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> ClientLocationLookup.open(textFile))
        .withMessageStartingWith("SX-WEB-1006");
  }
}
