package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 安全配置与存储状态，不探测网络；服务由boot端口装配。 */
@Command(name = "status", description = "安全配置与存储状态，不探测网络", mixinStandardHelpOptions = true)
public final class StatusCommand extends CommandOptions {}
