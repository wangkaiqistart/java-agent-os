package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import com.aegisos.core.memory.ExchangeGroup;
import com.aegisos.core.provider.*;
import java.util.*;

/** 只改变发送视图；持久原始身份仍用于同一处理内去重。 */
public final class ToolCallProjection {
  private ToolCallProjection() {}

  public static List<ModelMessage> project(ExchangeGroup group) {
    if (group.state() != ExchangeGroup.State.CLOSED) return List.of();
    var ids = new HashMap<String, String>();
    var out = new ArrayList<ModelMessage>();
    var observed = new HashSet<String>();
    for (var message : group.messages()) {
      if (!message.toolCalls().isEmpty()) {
        var calls = new ArrayList<ToolCall>();
        int ordinal = 0;
        for (var call : message.toolCalls()) {
          String wire =
              "t_"
                  + group.processId().toString().replace("-", "")
                  + "_"
                  + group.groupOrdinal()
                  + "_"
                  + (++ordinal);
          if (ids.putIfAbsent(call.id(), wire) != null) throw invalid();
          calls.add(new ToolCall(wire, call.name(), call.argumentsJson()));
        }
        out.add(new ModelMessage(message.role(), message.content(), null, calls));
      } else if (message.role() == ModelMessage.Role.TOOL) {
        String wire = ids.get(message.toolCallId());
        if (wire == null || !observed.add(message.toolCallId())) throw invalid();
        out.add(new ModelMessage(message.role(), message.content(), wire, List.of()));
      } else out.add(message);
    }
    if (ids.size() != observed.size()) throw invalid();
    return List.copyOf(out);
  }

  private static AgentException invalid() {
    return new AgentException(AgentErrorCode.INVALID_RESPONSE);
  }
}
