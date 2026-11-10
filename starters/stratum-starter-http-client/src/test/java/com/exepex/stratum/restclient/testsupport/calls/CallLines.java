package com.exepex.stratum.restclient.testsupport.calls;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.Arrays;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.boot.test.system.CapturedOutput;

/** Finds and checks the call log lines a test's clients wrote. */
@UtilityClass
public class CallLines {

  /**
   * Returns the lines a client's call log wrote.
   *
   * @param output the test's output
   * @param client the client's name
   * @return the lines, in order
   */
  public static List<String> linesOf(CapturedOutput output, String client) {
    var logger = HttpClientConstants.CALL_LOGGER_PREFIX + client;
    return output.getAll().lines().filter(line -> line.contains(logger)).toList();
  }

  /**
   * Checks that a client's call log wrote exactly one line, holding every part.
   *
   * @param output the test's output
   * @param client the client's name
   * @param parts the parts the line holds, such as {@code outcome=SERVER_ERROR}
   * @return the line
   */
  public static String assertCallLine(CapturedOutput output, String client, String... parts) {
    var lines = linesOf(output, client);
    assertThat(lines).hasSize(1);
    Arrays.stream(parts).forEach(part -> assertThat(lines.getFirst()).contains(part));
    return lines.getFirst();
  }
}
