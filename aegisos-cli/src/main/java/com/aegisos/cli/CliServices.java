package com.aegisos.cli;

import java.io.*;

/** boot实现此装配端口，cli不反向依赖Spring、JPA或Provider客户端。 */
public interface CliServices {
  int execute(CliArguments arguments, InputStream input, PrintStream output, PrintStream error);
}
