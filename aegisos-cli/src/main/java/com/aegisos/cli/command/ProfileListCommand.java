package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 列出合法Profile与安全诊断；服务由boot端口装配。 */
@Command(name = "list", description = "列出合法Profile与安全诊断", mixinStandardHelpOptions = true)
public final class ProfileListCommand extends CommandOptions {}
