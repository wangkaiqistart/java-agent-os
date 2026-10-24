package com.aegisos.channel.cli;

import com.aegisos.core.agent.ProcessingResult;
import java.io.InputStream;
import java.io.PrintStream;

/** 命令层只依赖单次输入输出行为，不访问渠道实现或校验器的内部状态。 */
public interface CliInputOutput {
  String read(InputStream input);

  void write(ProcessingResult result, PrintStream output);
}
