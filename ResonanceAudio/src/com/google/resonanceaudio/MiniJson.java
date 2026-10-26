package com.google.resonanceaudio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser (objects, arrays, strings, numbers,
 * booleans, null). Returns {@code Map<String,Object>}, {@code List<Object>},
 * {@code String}, {@code Double}, {@code Boolean} or {@code null}.
 */
final class MiniJson {

  static final class ParseException extends RuntimeException {
    ParseException(String message) {
      super(message);
    }
  }

  private final String s;
  private int pos;

  private MiniJson(String s) {
    this.s = s;
  }

  static Object parse(String text) {
    MiniJson p = new MiniJson(stripBom(text));
    p.skipWs();
    Object value = p.readValue();
    p.skipWs();
    if (p.pos < p.s.length()) {
      throw new ParseException("Trailing characters at offset " + p.pos);
    }
    return value;
  }

  private static String stripBom(String text) {
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      return text.substring(1);
    }
    return text;
  }

  private Object readValue() {
    if (pos >= s.length()) {
      throw new ParseException("Unexpected end of input");
    }
    char c = s.charAt(pos);
    switch (c) {
      case '{':
        return readObject();
      case '[':
        return readArray();
      case '"':
        return readString();
      case 't':
        expect("true");
        return Boolean.TRUE;
      case 'f':
        expect("false");
        return Boolean.FALSE;
      case 'n':
        expect("null");
        return null;
      default:
        return readNumber();
    }
  }

  private Map<String, Object> readObject() {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    pos++; // '{'
    skipWs();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWs();
      String key = readString();
      skipWs();
      if (peek() != ':') {
        throw new ParseException("Expected ':' at offset " + pos);
      }
      pos++;
      skipWs();
      map.put(key, readValue());
      skipWs();
      char c = peek();
      pos++;
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw new ParseException("Expected ',' or '}' at offset " + (pos - 1));
      }
    }
  }

  private List<Object> readArray() {
    List<Object> list = new ArrayList<Object>();
    pos++; // '['
    skipWs();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      skipWs();
      list.add(readValue());
      skipWs();
      char c = peek();
      pos++;
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw new ParseException("Expected ',' or ']' at offset " + (pos - 1));
      }
    }
  }

  private String readString() {
    if (peek() != '"') {
      throw new ParseException("Expected string at offset " + pos);
    }
    pos++;
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (pos >= s.length()) {
        throw new ParseException("Unterminated string");
      }
      char c = s.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
        char e = s.charAt(pos++);
        switch (e) {
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          case '/':
            sb.append('/');
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'u':
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
            break;
          default:
            throw new ParseException("Bad escape '\\" + e + "'");
        }
      } else {
        sb.append(c);
      }
    }
  }

  private Double readNumber() {
    int start = pos;
    while (pos < s.length() && "+-.eE0123456789".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
    try {
      return Double.valueOf(s.substring(start, pos));
    } catch (NumberFormatException e) {
      throw new ParseException("Invalid value at offset " + start);
    }
  }

  private void expect(String literal) {
    if (!s.startsWith(literal, pos)) {
      throw new ParseException("Expected '" + literal + "' at offset " + pos);
    }
    pos += literal.length();
  }

  private char peek() {
    if (pos >= s.length()) {
      throw new ParseException("Unexpected end of input");
    }
    return s.charAt(pos);
  }

  private void skipWs() {
    while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
      pos++;
    }
  }
}
