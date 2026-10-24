package com.aegisos.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;

/** 唯一生产内置工具；只能使用 Sandbox 发放的单次网络许可。 */
public final class HttpGetTool implements AegisTool {
  private final ControlledHttpTransport transport = new ControlledHttpTransport();

  public ToolSchema schema() {
    return new ToolSchema(
        "http_get",
        "查询部署明确授权的天气 HTTP 数据",
        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"经部署授权的天气查询绝对地址\"}},\"required\":[\"url\"],\"additionalProperties\":false}");
  }

  public ToolResult execute(
      ToolExecutionContext context, ValidatedArguments args, ToolPermission permission) {
    if (!(permission instanceof HttpGetPermission approved)
        || !approved.uri.toString().equals(args.values().get("url")))
      throw new AgentException(AgentErrorCode.SANDBOX_DENIED);
    return transport.get(approved);
  }
}
