package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 移出Agent定义，下次启动生效，不删除历史；服务由boot端口装配。 */
@Command(name = "delete", description = "移出Agent定义，下次启动生效，不删除历史", mixinStandardHelpOptions = true)
public final class ProfileDeleteCommand extends CommandOptions {}
