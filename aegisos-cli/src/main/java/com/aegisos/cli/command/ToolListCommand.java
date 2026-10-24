package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 实际Tool及策略状态，不执行；服务由boot端口装配。 */
@Command(name = "list", description = "实际Tool及策略状态，不执行", mixinStandardHelpOptions = true)
public final class ToolListCommand extends CommandOptions {}
