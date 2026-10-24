package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 只读分页会话摘要，不初始化或恢复；服务由boot端口装配。 */
@Command(name = "list", description = "只读分页会话摘要，不初始化或恢复", mixinStandardHelpOptions = true)
public final class SessionListCommand extends CommandOptions {}
