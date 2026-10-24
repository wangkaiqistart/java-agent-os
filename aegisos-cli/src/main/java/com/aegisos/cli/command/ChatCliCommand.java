package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 统一Agent入口：终端交互、管道单次JSON；服务由boot端口装配。 */
@Command(name = "chat", description = "统一Agent入口：终端交互、管道单次JSON", mixinStandardHelpOptions = true)
public final class ChatCliCommand extends CommandOptions {}
