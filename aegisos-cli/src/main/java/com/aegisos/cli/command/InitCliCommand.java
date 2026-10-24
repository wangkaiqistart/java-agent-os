package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 离线初始化，不覆盖已有目录；服务由boot端口装配。 */
@Command(name = "init", description = "离线初始化，不覆盖已有目录", mixinStandardHelpOptions = true)
public final class InitCliCommand extends CommandOptions {}
