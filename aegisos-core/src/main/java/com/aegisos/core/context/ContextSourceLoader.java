package com.aegisos.core.context;

import com.aegisos.core.profile.Profile;

/** Prompt 只依赖正文加载行为端口，不接收或暴露具体读取器的内部状态。 */
@FunctionalInterface
public interface ContextSourceLoader {
  ContextLoader.LoadedContext load(Profile profile, AgentSource source);
}
