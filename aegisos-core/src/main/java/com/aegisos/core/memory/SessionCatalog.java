package com.aegisos.core.memory;

import java.util.List;

/** 查询端口只返回投影，不持有Memory写生命周期。 */
public interface SessionCatalog {
  Page list(SessionQuery query);

  record Page(List<SessionSummary> items, int limit, int offset, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
