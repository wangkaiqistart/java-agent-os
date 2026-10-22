package com.aegisos.core.profile;

/** 身份文本仅为元数据，本轮不加入 system prompt，也不展开环境引用。 */
public record Identity(String agentName, String prompt) {
  public Identity {
    if ((agentName != null && agentName.length() > 4096)
        || (prompt != null && prompt.length() > 65536)) throw MetadataValue.invalid();
  }

  @Override
  public String toString() {
    return "Identity[redacted]";
  }
}
