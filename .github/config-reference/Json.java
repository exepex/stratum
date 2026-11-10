import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one JSON document, enough for configuration metadata: objects become maps, arrays lists,
 * strings and numbers strings, and {@code true}, {@code false} and {@code null} their Java values.
 */
final class Json {

  /** The file being read, named in errors. */
  private final String file;

  /** The whole document. */
  private final String text;

  /** The position of the next character to read. */
  private int position;

  Json(String file, String text) {
    this.file = file;
    this.text = text;
  }

  /** Returns the document's value, and fails on anything after it. */
  Object document() {
    Object value = value();
    skipSpaces();
    if (position != text.length()) {
      throw failure("text after the document");
    }
    return value;
  }

  /** Reads the value that starts at the next character. */
  private Object value() {
    skipSpaces();
    if (position >= text.length()) {
      throw failure("end of file inside the document");
    }
    return switch (text.charAt(position)) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      default -> literal();
    };
  }

  /** Reads an object, keeping the order of its fields. */
  private Map<String, Object> object() {
    var fields = new LinkedHashMap<String, Object>();
    position++;
    if (next() == '}') {
      position++;
      return fields;
    }
    do {
      skipSpaces();
      String name = string();
      expect(':');
      fields.put(name, value());
    } while (separator('}'));
    return fields;
  }

  /** Reads an array. */
  private List<Object> array() {
    var values = new ArrayList<Object>();
    position++;
    if (next() == ']') {
      position++;
      return values;
    }
    do {
      values.add(value());
    } while (separator(']'));
    return values;
  }

  /** Reads a string with its escapes. */
  private String string() {
    if (position >= text.length() || text.charAt(position) != '"') {
      throw failure("a string expected");
    }
    var value = new StringBuilder();
    position++;
    while (position < text.length() && text.charAt(position) != '"') {
      char character = text.charAt(position++);
      value.append(character == '\\' ? escaped() : character);
    }
    expect('"');
    return value.toString();
  }

  /** Reads the character after a backslash and returns the character it stands for. */
  private char escaped() {
    if (position >= text.length()) {
      throw failure("end of file inside a string");
    }
    char code = text.charAt(position++);
    return switch (code) {
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> unicode();
      case '"', '\\', '/' -> code;
      default -> throw failure("unknown escape \\" + code);
    };
  }

  /** Reads the four hexadecimal digits of a unicode escape. */
  private char unicode() {
    if (position + 4 > text.length()) {
      throw failure("end of file inside a \\u escape");
    }
    try {
      char character = (char) Integer.parseInt(text.substring(position, position + 4), 16);
      position += 4;
      return character;
    } catch (NumberFormatException badDigits) {
      throw failure("bad \\u escape");
    }
  }

  /** Reads a number, {@code true}, {@code false} or {@code null}. */
  private Object literal() {
    int start = position;
    while (position < text.length() && "{}[],: \t\r\n\"".indexOf(text.charAt(position)) < 0) {
      position++;
    }
    String word = text.substring(start, position);
    return switch (word) {
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      case "null" -> null;
      default -> {
        if (!word.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
          throw failure("unexpected '" + word + "'");
        }
        yield word;
      }
    };
  }

  /** Reads a comma, returning true, or the closing character, returning false. */
  private boolean separator(char closing) {
    char character = next();
    position++;
    if (character == ',') {
      return true;
    }
    if (character != closing) {
      throw failure("',' or '" + closing + "' expected");
    }
    return false;
  }

  /** Skips spaces and reads one expected character. */
  private void expect(char expected) {
    if (next() != expected) {
      throw failure("'" + expected + "' expected");
    }
    position++;
  }

  /** Skips spaces and returns the next character without reading it. */
  private char next() {
    skipSpaces();
    if (position >= text.length()) {
      throw failure("end of file inside the document");
    }
    return text.charAt(position);
  }

  /** Moves past spaces, tabs and line ends. */
  private void skipSpaces() {
    while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
      position++;
    }
  }

  /** Returns the error for a fault at the current position. */
  private IllegalStateException failure(String fault) {
    return new IllegalStateException(file + ": " + fault + " at character " + position);
  }
}
