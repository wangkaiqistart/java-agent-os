package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 安全模板新建Profile，下次启动生效；服务由boot端口装配。 */
@Command(name = "create", description = "安全模板新建Profile，下次启动生效", mixinStandardHelpOptions = true)
public final class ProfileCreateCommand extends CommandOptions {}
