package com.aegisos.core.profile;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** 领域边界独立于 YAML/SDK，扩展树不允许通过嵌套容器逃逸冻结。 */
class ProfileContractTest {
  Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new ProviderSelection("p", "m", null),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void strictNamesAndExplicitSelection() {
    for (String s : List.of("", " A", "-a", "x".repeat(65), "中文"))
      assertThatThrownBy(() -> profile(s)).hasMessage("INVALID_FIELD");
    assertThat(profile("a".repeat(64)).name()).hasSize(64);
    assertThatThrownBy(() -> new ProviderSelection("p", null, null)).hasMessage("INVALID_FIELD");
    for (double d : new double[] {-1, 3, Double.NaN, Double.POSITIVE_INFINITY})
      assertThatThrownBy(() -> new ProviderSelection("p", "m", d)).hasMessage("INVALID_FIELD");
  }

  @Test
  void deepImmutabilityAndRedactedNestedStrings() {
    var list = new ArrayList<Object>();
    list.add("private-sentinel");
    var config = new HashMap<String, Object>();
    config.put("items", list);
    var c = new ChannelMetadata("cli", config);
    list.clear();
    config.clear();
    assertThat((List<?>) c.config().get("items")).hasSize(1);
    assertThatThrownBy(() -> c.config().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<?>) c.config().get("items")).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(c.toString() + new Identity("private-sentinel", "private-sentinel"))
        .doesNotContain("private-sentinel");
    assertThatThrownBy(() -> new MetadataValue(Map.of("api-key", "private-sentinel")))
        .hasMessage("INVALID_FIELD");
    var registry = new ProfileRegistry(List.of(profile("a")));
    assertThatThrownBy(() -> registry.entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(registry.get("A")).isNull();
  }

  @Test
  void optionalAndMetadataBoundaries() {
    assertThat(profile("a").settings().maxIterations()).isNull();
    assertThatThrownBy(() -> new Identity(null, "x".repeat(65537))).hasMessage("INVALID_FIELD");
    assertThatThrownBy(() -> new Settings(0, null, Map.of())).hasMessage("INVALID_FIELD");
    assertThatThrownBy(() -> new ScheduleMetadata("a", "*", "bad-zone", "x", null))
        .hasMessage("INVALID_FIELD");
    assertThatThrownBy(() -> Profile.relative("../secret")).hasMessage("INVALID_FIELD");
    assertThatThrownBy(() -> new MetadataValue(Map.of("items", Collections.nCopies(257, "a"))))
        .hasMessage("INVALID_FIELD");
  }
}
