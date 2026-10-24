package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** Provider配置元数据；不探测连接；服务由boot端口装配。 */
@Command(name = "list", description = "Provider配置元数据；不探测连接", mixinStandardHelpOptions = true)
public final class ProviderListCommand extends CommandOptions {}
