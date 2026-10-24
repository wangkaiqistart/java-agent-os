package com.aegisos.boot.react;

import java.util.*;

/** 外呼前同步扣额度；额度耗尽抛固定错误，不能发起请求后才做统计。 */
final class RealReactSmokeBudget {
  private final int iterations, modelLimit, getLimit;
  private final Map<UUID, Integer> processRounds = new HashMap<>();
  int logical, physical, gets;

  RealReactSmokeBudget(int iterations, int modelLimit, int getLimit) {
    if (iterations < 1
        || iterations > 3
        || modelLimit < 1
        || modelLimit > 6
        || getLimit < 1
        || getLimit > 2) throw new IllegalArgumentException("INVALID_SMOKE_BUDGET");
    this.iterations = iterations;
    this.modelLimit = modelLimit;
    this.getLimit = getLimit;
  }

  synchronized void model(UUID process) {
    int round = processRounds.getOrDefault(process, 0);
    if (logical >= modelLimit
        || round >= iterations
        || !processRounds.containsKey(process) && processRounds.size() >= 2) throw exhausted();
    processRounds.put(process, round + 1);
    logical++;
  }

  synchronized void physical() {
    if (physical >= modelLimit || physical >= logical) throw exhausted();
    physical++;
  }

  synchronized void get() {
    if (gets >= getLimit) throw exhausted();
    gets++;
  }

  private static IllegalStateException exhausted() {
    return new IllegalStateException("REACT_SMOKE_BUDGET_EXHAUSTED");
  }
}
