package com.aegisos.core.profile;

/** 仅保存已校验的相对绑定和目标路径；不读取/执行技能正文。 */
public record SkillBinding(String name, String bindingPath, String targetPath) {
  public SkillBinding {
    Profile.requireName(name);
    Profile.relative(bindingPath);
    Profile.relative(targetPath);
  }

  @Override
  public String toString() {
    return "SkillBinding[redacted]";
  }
}
