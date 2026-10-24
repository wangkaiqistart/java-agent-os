package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeatherFactAssertionsTest {
  static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");
  static final String BODY =
      "{\"latitude\":31.23,\"longitude\":121.47,\"timezone\":\"Asia/Shanghai\",\"current_units\":{\"temperature_2m\":\"°C\"},\"current\":{\"temperature_2m\":12.35,\"wind_speed_10m\":1,\"weather_code\":0,\"time\":\"2026-10-22T09:00\"}}";

  WeatherFactAssertions.Fact fact(String body) {
    return WeatherFactAssertions.weather(
        body, new BigDecimal("31.23"), new BigDecimal("121.47"), "Asia/Shanghai", NOW);
  }

  @Test
  void exactCoordinateToleranceAndTemperatureRounding() {
    assertThat(fact(BODY.replace("31.23", "31.33")).temperature()).isEqualByComparingTo("12.4");
    assertThat(fact(BODY.replace("12.35", "-12.35")).temperature()).isEqualByComparingTo("-12.4");
    WeatherFactAssertions.answer(fact(BODY), "气温：+12.40 ℃；建议：带伞。");
    WeatherFactAssertions.answer(fact(BODY.replace("12.35", "-12.35")), "气温: -12.4 摄氏度; 建议: coat");
  }

  @Test
  void invalidWeatherCannotPass() {
    for (String body :
        List.of(
            BODY.replace("31.23", "31.330001"),
            BODY.replace("121.47", "100.0"),
            BODY.replace("°C", "°F"),
            BODY.replace("temperature_2m\":\"°C", "missing\":\"°C"),
            BODY.replace("2026-10-22T09:00", "2026-10-21T09:00"),
            BODY.replace("Asia/Shanghai", "UTC"),
            BODY.replace("wind_speed_10m\":1", "wind_speed_10m\":-1"),
            BODY.replace("weather_code\":0", "weather_code\":0.5")))
      assertThatThrownBy(() -> fact(body)).hasMessage("INVALID_WEATHER_FACT");
    fact(BODY.replace("2026-10-22T09:00", "2026-10-22T07:00"));
    assertThatThrownBy(() -> fact(BODY.replace("2026-10-22T09:00", "2026-10-22T06:59")))
        .hasMessage("INVALID_WEATHER_FACT");
  }

  @Test
  void wholeNumericValueUniqueFieldsAndNonemptyAdviceAreRequired() {
    var f = fact(BODY.replace("12.35", "1.2"));
    for (String answer :
        List.of(
            "气温：11.2 °C；建议：带伞",
            "气温：1.2 °F；建议：带伞",
            "气温：1.2 °C；建议：  ",
            "气温：1.2 °C；建议：123",
            "气温：1.2 °C；建议：好；建议：好",
            "气温：1.2 °C；气温：1.2 °C；建议：好",
            "只有建议"))
      assertThatThrownBy(() -> WeatherFactAssertions.answer(f, answer))
          .hasMessage("INVALID_WEATHER_ANSWER");
  }

  @Test
  void latestForwardedObservationBelongsToTheSameSession() {
    var old = new WeatherFactAssertions.Observation("s", "p1", "a", BODY, NOW);
    var fresh =
        new WeatherFactAssertions.Observation("s", "p2", "b", BODY.replace("12.35", "15.0"), NOW);
    assertThat(WeatherFactAssertions.latest("s", List.of(old, fresh), List.of("a", "b")))
        .isEqualTo(fresh);
    assertThatThrownBy(() -> WeatherFactAssertions.latest("other", List.of(old), List.of("a")))
        .hasMessage("INVALID_WEATHER_ASSOCIATION");
    assertThatThrownBy(
            () -> WeatherFactAssertions.latest("s", List.of(old), List.of("not-forwarded")))
        .hasMessage("INVALID_WEATHER_ASSOCIATION");
    assertThatThrownBy(() -> WeatherFactAssertions.answer(fact(fresh.body()), "气温：12.4 °C；建议：外出"))
        .hasMessage("INVALID_WEATHER_ANSWER");
  }
}
