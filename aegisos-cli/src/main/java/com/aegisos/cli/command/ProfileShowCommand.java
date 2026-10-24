package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 查看安全元数据；服务由boot端口装配。 */
@Command(name = "show", description = "查看安全元数据", mixinStandardHelpOptions = true)
public final class ProfileShowCommand extends CommandOptions {}
