package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文件修改、增加/删除、环境变化都不能改变现有注册快照。 */
class ProfileSnapshotTest {
  @TempDir Path dir;

  @Test
  void mutationsOnlyAffectNewLoad() throws Exception {
    Path a =
        CourseAlignmentFixtures.agent(
            dir, "one", ProfileLoaderTest.MINIMAL + "provider_unused: nope\n");
    Files.writeString(
        a.resolve("profile.yml"),
        ProfileLoaderTest.MINIMAL.replace("model: m", "model: '${MODEL}'"));
    var env = new HashMap<String, String>();
    env.put("MODEL", "m");
    var loader = new ProfileLoader(dir, ProfileLoaderTest.providers(), env::get);
    var before = new ProfileRegistry(loader.load().profiles());
    assertThat(before.get("agent-a")).isNotNull();
    env.put("MODEL", "bad");
    Files.delete(a.resolve("profile.yml"));
    CourseAlignmentFixtures.agent(
        dir, "new", ProfileLoaderTest.MINIMAL.replace("agent-a", "other"));
    assertThat(before.get("agent-a")).isNotNull();
    assertThat(before.get("other")).isNull();
    var after = new ProfileRegistry(loader.load().profiles());
    assertThat(after.get("agent-a")).isNull();
    assertThat(after.get("other")).isNotNull();
  }
}
