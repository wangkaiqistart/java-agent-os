package com.aegisos.boot.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** 课程测试只使用调用方提供的临时目录与合成环境，不访问个人工作区或环境密钥。 */
final class CourseAlignmentFixtures {
  private CourseAlignmentFixtures() {}

  static Path agent(Path workspace, String directory, String yaml) throws IOException {
    Path root = workspace.resolve(".aegisos/agents").resolve(directory);
    Files.createDirectories(root);
    Files.writeString(root.resolve("profile.yml"), yaml);
    return root;
  }

  static Path database(Path workspace) {
    return workspace.resolve("course-audit.db");
  }

  static Function<String, String> environment(Map<String, String> values) {
    Map<String, String> snapshot = Map.copyOf(values);
    return snapshot::get;
  }
}
