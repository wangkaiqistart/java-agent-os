package com.aegisos.cli.command;

import picocli.CommandLine.Command;

/** 本机Web基础；完整Agent API尚未交付；服务由boot端口装配。 */
@Command(name = "serve", description = "本机Web基础；完整Agent API尚未交付", mixinStandardHelpOptions = true)
public final class ServeCommand extends CommandOptions {}
