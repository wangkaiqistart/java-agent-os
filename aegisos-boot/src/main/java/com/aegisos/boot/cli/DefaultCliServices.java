package com.aegisos.boot.cli;

import com.aegisos.cli.*;
import java.io.*;
import java.util.*;

/** 轻命令组合根；只在确实需要时读取元数据，不持有任何运行时生命周期。 */
public final class DefaultCliServices implements CliServices {
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  public int execute(CliArguments args, InputStream input, PrintStream out, PrintStream err) {
    if (args.command().equals("gateway")) {
      out.println(
          "{\"status\":\"CAPABILITY_DISABLED\",\"reason\":\"MULTI_CHANNEL_NOT_IMPLEMENTED\"}");
      return 6;
    }
    var metadata = new WorkspaceMetadataService(args.workspace());
    Object result;
    int exit = 0;
    switch (args.command()) {
      case "profile create" -> {
        new ProfileDefinitionService(args.workspace())
            .create(
                args.options().get("name"),
                args.options().get("provider"),
                args.options().get("model"));
        result = Map.of("status", "CREATED", "activation", "NEXT_START");
      }
      case "profile delete" -> {
        new ProfileDefinitionService(args.workspace()).delete(args.options().get("name"));
        result = Map.of("status", "REMOVED_FROM_REGISTRY_SOURCE", "activation", "NEXT_START");
      }
      case "serve" -> {
        return new CliRuntimeLauncher().serve(args, out);
      }
      case "session list", "status" -> {
        try {
          var query =
              new com.aegisos.core.memory.SessionQuery(
                  args.agent(),
                  Integer.parseInt(args.options().getOrDefault("limit", "20")),
                  Integer.parseInt(args.options().getOrDefault("offset", "0")));
          var page =
              new com.aegisos.storage.session.ReadOnlySessionCatalog(
                      java.nio.file.Path.of(
                          metadata.properties().get("aegisos.audit.path").toString()))
                  .list(query);
          result =
              args.command().equals("status")
                  ? Map.of(
                      "configuration",
                      metadata.report().diagnostics().isEmpty() ? "CONFIGURED" : "INVALID",
                      "database",
                      "AVAILABLE",
                      "gateway",
                      "CAPABILITY_DISABLED",
                      "serve",
                      "WEB_BASELINE_ONLY")
                  : page;
          if (args.command().equals("status") && !metadata.report().diagnostics().isEmpty())
            exit = 2;
        } catch (com.aegisos.core.memory.SessionQueryException e) {
          result =
              Map.of(
                  "configuration",
                  metadata.report().diagnostics().isEmpty() ? "CONFIGURED" : "INVALID",
                  "database",
                  e.state().name());
          exit =
              e.state() == com.aegisos.core.memory.SessionQueryException.State.QUERY_UNAVAILABLE
                  ? 5
                  : 2;
        }
      }
      case "profile list" -> {
        result = metadata.profiles();
        exit = metadata.report().diagnostics().isEmpty() ? 0 : 2;
      }
      case "profile show" -> result = metadata.show(args.options().get("name"));
      case "provider list" -> result = metadata.providerList();
      case "tool list" ->
          result =
              Map.of(
                  "items",
                  List.of(
                      Map.of(
                          "name",
                          "http_get",
                          "status",
                          "IMPLEMENTED",
                          "policy",
                          metadata.properties().keySet().stream()
                                  .anyMatch(k -> k.startsWith("aegisos.sandbox.http-get.targets["))
                              ? "TARGETS_DECLARED_NOT_VALIDATED"
                              : "DENY_ALL")));
      default -> {
        result = Map.of("status", "CAPABILITY_DISABLED");
        exit = 6;
      }
    }
    out.println(JSON.writeValueAsString(result));
    return out.checkError() ? 5 : exit;
  }
}
