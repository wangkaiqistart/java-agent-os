package com.aegisos.core.provider;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** ASCII 长度、大小写和入口模式必须独立于数据库或 SDK。 */
class SessionAssociationTest {
  @Test
  void exactFormatAndModes() {
    for (String valid : List.of("A", "a", "0_a-B", "x".repeat(64))) {
      assertThat(SessionAssociation.safe(valid)).isEqualTo(valid);
      assertThat(SessionAssociation.error(SessionAssociation.Mode.SESSION_REQUIRED, valid))
          .isNull();
    }
    for (String invalid : List.of("", " A", "A ", "x".repeat(65), "a/b", "会话", "-A", "_A", "A\n")) {
      assertThat(SessionAssociation.safe(invalid)).isNull();
      assertThat(SessionAssociation.error(SessionAssociation.Mode.SESSION_REQUIRED, invalid))
          .isEqualTo(ProviderErrorCode.INVALID_SESSION_ID);
    }
    assertThat(SessionAssociation.error(SessionAssociation.Mode.SESSION_REQUIRED, null))
        .isEqualTo(ProviderErrorCode.SESSION_REQUIRED);
    assertThat(SessionAssociation.error(SessionAssociation.Mode.LEGACY_UNASSOCIATED, null))
        .isNull();
  }
}
