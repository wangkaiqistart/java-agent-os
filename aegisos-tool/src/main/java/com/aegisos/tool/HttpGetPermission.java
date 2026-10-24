package com.aegisos.tool;

import com.aegisos.core.tool.ToolPermission;
import java.net.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次许可绑定地址和单调时钟截止时间；close 幂等归还全局执行额度。 */
public final class HttpGetPermission implements ToolPermission, AutoCloseable {
  final URI uri;
  final InetAddress address;
  final long deadline;
  final NetworkPolicy policy;
  private final Semaphore slots;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean used = new AtomicBoolean();

  HttpGetPermission(
      URI uri, InetAddress address, long deadline, NetworkPolicy policy, Semaphore slots) {
    this.uri = uri;
    this.address = address;
    this.deadline = deadline;
    this.policy = policy;
    this.slots = slots;
  }

  void claim() {
    if (closed.get() || !used.compareAndSet(false, true) || remainingNanos() <= 0)
      throw new com.aegisos.core.agent.AgentException(
          com.aegisos.core.agent.AgentErrorCode.TOOL_TIMEOUT);
  }

  long remainingNanos() {
    return deadline - System.nanoTime();
  }

  public void close() {
    if (closed.compareAndSet(false, true)) slots.release();
  }

  @Override
  public String toString() {
    return "HttpGetPermission[redacted]";
  }
}
