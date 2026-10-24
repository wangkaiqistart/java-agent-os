package com.aegisos.boot;

import com.aegisos.boot.config.ProviderConfiguration;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import com.aegisos.web.baseline.WebBaselineConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Runnable Spring Boot entry point for the AegisOS runtime. */
@SpringBootApplication
@Import({
  WebBaselineConfiguration.class,
  AuditStorageConfiguration.class,
  ProviderConfiguration.class
})
public class AegisOsApplication {

  public static void main(String[] args) {
    if (args.length == 0
        || (args.length == 1
            && java.util.Set.of("--spring.profiles.active=dev", "--spring.profiles.active=metrics")
                .contains(args[0]))) {
      SpringApplication.run(AegisOsApplication.class, args);
    } else {
      System.exit(dispatch(args, System.in, System.out, System.err));
    }
  }

  /** CLI 在启动 Spring 前分流；init 不接触凭据/数据库，chat 的所有资源在退出前关闭。 */
  public static int dispatch(
      String[] args, java.io.InputStream in, java.io.PrintStream out, java.io.PrintStream err) {
    try {
      var parsed = com.aegisos.cli.CliArguments.parse(args);
      if (parsed.command().equals("help")) {
        com.aegisos.cli.AegisOsCli.printHelp(parsed.options().get("help-path"), out);
        return 0;
      }
      if (parsed.command().equals("version")) {
        out.println("AegisOS 0.1.0-SNAPSHOT");
        return 0;
      }
      if (!parsed.command().equals("init") && !parsed.command().equals("chat"))
        return new com.aegisos.boot.cli.DefaultCliServices().execute(parsed, in, out, err);
      if (parsed.command().equals("init")) {
        new com.aegisos.cli.InitCommand().run(parsed.workspace());
        out.println("{\"status\":\"INITIALIZED\"}");
        return 0;
      }
      return new com.aegisos.boot.cli.CliRuntimeLauncher().chat(parsed, in, out, err);
    } catch (RuntimeException e) {
      int exit = 2;
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause instanceof com.aegisos.core.agent.AgentException agent
            && agent.code() == com.aegisos.core.agent.AgentErrorCode.WORKSPACE_IN_USE) exit = 3;
      }
      // 不打印异常/路径/原始身份；启动失败不伪造已确认的session或process。
      err.println(exit == 3 ? "WORKSPACE_IN_USE" : "CLI_CONFIGURATION_OR_INPUT_REJECTED");
      out.println(
          "{\"sessionId\":null,\"processId\":null,\"status\":\"FAILED\",\"iterationCount\":0,\"reply\":null,\"error\":\""
              + (exit == 3 ? "WORKSPACE_IN_USE" : "INVALID_INPUT")
              + "\",\"persistenceConfirmed\":false}");
      return exit;
    }
  }
}
