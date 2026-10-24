package com.aegisos.core.tool;

import com.aegisos.core.provider.ToolSchema;
import java.util.*;

public interface ToolRegistryView {
  Optional<AegisTool> find(String name);

  List<ToolSchema> schemas();
}
