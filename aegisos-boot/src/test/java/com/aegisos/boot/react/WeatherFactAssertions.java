package com.aegisos.boot.react;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.*;

/** 纯内存天气验收，不输出天气/回复正文；固定十进制、时区、归属与唯一字段判据。 */
final class WeatherFactAssertions {
  private static final ObjectMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  record Fact(BigDecimal temperature) {}

  record Observation(String session, String process, String audit, String body, Instant received) {
    @Override
    public String toString() {
      return "Observation[redacted]";
    }
  }

  static Fact weather(
      String body, BigDecimal latitude, BigDecimal longitude, String zone, Instant received) {
    try {
      var root = JSON.readTree(body);
      BigDecimal lat = number(root.get("latitude")), lon = number(root.get("longitude"));
      if (lat.abs().compareTo(new BigDecimal("90")) > 0
          || lon.abs().compareTo(new BigDecimal("180")) > 0
          || lat.subtract(latitude).abs().compareTo(new BigDecimal("0.1")) > 0
          || lon.subtract(longitude).abs().compareTo(new BigDecimal("0.1")) > 0
          || !zone.equals(root.path("timezone").asText())
          || !"°C".equals(root.path("current_units").path("temperature_2m").asText()))
        throw invalid();
      var current = root.path("current");
      BigDecimal wind = number(current.get("wind_speed_10m"));
      number(current.get("weather_code")).toBigIntegerExact();
      if (wind.signum() < 0) throw invalid();
      Instant time =
          LocalDateTime.parse(current.path("time").asText()).atZone(ZoneId.of(zone)).toInstant();
      if (Duration.between(time, received).abs().compareTo(Duration.ofHours(2)) > 0)
        throw invalid();
      return new Fact(number(current.get("temperature_2m")).setScale(1, RoundingMode.HALF_UP));
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static BigDecimal number(JsonNode node) {
    if (node == null || !node.isNumber()) throw invalid();
    return node.decimalValue();
  }

  static void answer(Fact fact, String answer) {
    try {
      if (answer == null
          || Pattern.compile("气温\\s*[:：]").matcher(answer).results().count() != 1
          || Pattern.compile("建议\\s*[:：]").matcher(answer).results().count() != 1)
        throw new IllegalArgumentException();
      var match =
          Pattern.compile(
                  "气温\\s*[:：]\\s*([+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))\\s*(°C|℃|摄氏度)\\s*[;；]\\s*建议\\s*[:：]\\s*(.+)",
                  Pattern.DOTALL)
              .matcher(answer);
      if (!match.find()
          || new BigDecimal(match.group(1)).compareTo(fact.temperature()) != 0
          || !Pattern.compile("[\\p{IsHan}\\p{L}]").matcher(match.group(3)).find())
        throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("INVALID_WEATHER_ANSWER");
    }
  }

  /** 只从本次实际发送 Prompt 的 TOOL 顺序中找最新观察，不能拿内存中未转发的新值充数。 */
  static Observation latest(
      String session, List<Observation> observations, List<String> forwardedAudits) {
    for (int i = forwardedAudits.size() - 1; i >= 0; i--) {
      String id = forwardedAudits.get(i);
      for (var observation : observations)
        if (observation.audit().equals(id)) {
          if (!observation.session().equals(session)) throw association();
          return observation;
        }
    }
    throw association();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_WEATHER_FACT");
  }

  private static IllegalArgumentException association() {
    return new IllegalArgumentException("INVALID_WEATHER_ASSOCIATION");
  }
}
