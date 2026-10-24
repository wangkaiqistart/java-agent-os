package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

class ReactPropertiesTest {
  ReactProperties config(Integer total) {
    return new ReactProperties(total, null, null, null, null, null, null, null, null);
  }

  @Test
  void requiresExplicitTotalAndDoesNotClamp() {
    assertThatThrownBy(() -> config(null).budget()).hasMessage("INVALID_REACT_BUDGET");
    for (int n : new int[] {0, 4095, 1048577})
      assertThatThrownBy(() -> config(n).budget()).hasMessage("INVALID_REACT_BUDGET");
  }

  @Test
  void defaultsAndUnits() {
    var c = config(262144);
    assertThat(c.budget().maxContextBytes()).isEqualTo(262144);
    assertThat(c.budget().maxMessageBytes()).isEqualTo(32768);
    assertThat(c.budget().maxContextFiles()).isEqualTo(32);
    assertThat(c.budget().maxOutputTokens()).isEqualTo(1024);
    assertThat(c.zoneId().getId()).isEqualTo("UTC");
  }

  @Test
  void rejectsInconsistentAndInvalidLimits() {
    assertThatThrownBy(() -> config(4096).budget()).hasMessage("INVALID_REACT_BUDGET");
    assertThatThrownBy(
            () ->
                new ReactProperties(262144, 32768, 32768, 65, 8192, 16384, 8, 1024, "UTC").budget())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReactProperties(
                        262144, null, null, null, null, null, null, null, "invalid-zone")
                    .zoneId())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bindsYamlHyphenKeys() throws Exception {
    var env = new StandardEnvironment();
    var resource =
        new ByteArrayResource(
            "aegisos:\n  react:\n    max-context-bytes: 262144\n    zone: Asia/Shanghai\n"
                .replace("\\n", "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (var source : new YamlPropertySourceLoader().load("react-test", resource))
      env.getPropertySources().addFirst(source);
    var c = Binder.get(env).bind("aegisos.react", Bindable.of(ReactProperties.class)).get();
    assertThat(c.budget().maxContextBytes()).isEqualTo(262144);
    assertThat(c.zoneId().getId()).isEqualTo("Asia/Shanghai");
  }

  Profile profile(Integer max, Integer history) {
    return new Profile(
        "test",
        null,
        null,
        new ProviderSelection("p", "m", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Settings(max, history, Map.of()));
  }

  @Test
  void profileDefaultsAndStrictBounds() {
    assertThat(ProfileRuntimePolicy.from(profile(null, null)))
        .isEqualTo(new ProfileRuntimePolicy(10, 20));
    assertThatThrownBy(() -> ProfileRuntimePolicy.from(profile(101, 20)))
        .hasMessage("INVALID_PROFILE_RUNTIME_POLICY");
    assertThatThrownBy(() -> ProfileRuntimePolicy.from(profile(10, 1001)))
        .hasMessage("INVALID_PROFILE_RUNTIME_POLICY");
  }
}
