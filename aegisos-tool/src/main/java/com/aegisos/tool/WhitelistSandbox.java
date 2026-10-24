package com.aegisos.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.tool.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** 每次许可只解析一次 DNS；全部地址须安全，固定首地址且不会降级到其他地址。 */
public final class WhitelistSandbox implements Sandbox {
  @FunctionalInterface
  public interface Dns {
    InetAddress[] resolve(String host) throws Exception;
  }

  private static final Semaphore EXECUTIONS = new Semaphore(8);
  private static final ThreadPoolExecutor DNS =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new SynchronousQueue<>(),
          r -> {
            var t = new Thread(r, "aegisos-sandbox-dns");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final NetworkPolicy policy;
  private final Dns dns;
  private final boolean testLoopback;

  public WhitelistSandbox(NetworkPolicy policy, Dns dns) {
    this(policy, dns, false);
  }

  private WhitelistSandbox(NetworkPolicy policy, Dns dns, boolean testLoopback) {
    this.policy = policy;
    this.dns = dns;
    this.testLoopback = testLoopback;
  }

  /** 仅测试装配调用；生产 YAML 没有此能力开关。 */
  public static WhitelistSandbox isolatedLoopback(NetworkPolicy p, Dns dns) {
    return new WhitelistSandbox(p, dns, true);
  }

  public ToolPermission authorize(ToolExecutionContext context, ValidatedArguments arguments) {
    if (!context.profile().tools().contains("http_get")) throw denied();
    URI uri = uri(Objects.toString(arguments.values().get("url"), ""));
    boolean match =
        policy.targets().stream()
            .anyMatch(
                t ->
                    t.scheme().equals(uri.getScheme())
                        && t.host().equals(uri.getHost())
                        && t.port() == port(uri)
                        && t.path().equals(uri.getRawPath())
                        && t.queryKeys().equals(queryKeys(uri)));
    if (!match) throw denied();
    if (!EXECUTIONS.tryAcquire()) throw new AgentException(AgentErrorCode.RESOURCE_LIMIT);
    boolean granted = false;
    Future<InetAddress[]> future = null;
    long deadline = System.nanoTime() + policy.execution().toNanos();
    try {
      future = DNS.submit(() -> dns.resolve(uri.getHost()));
      var addresses =
          future.get(
              Math.min(policy.dns().toNanos(), deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (addresses == null || addresses.length == 0) throw denied();
      for (var address : addresses)
        if (address == null
            || !(publicAddress(address) || testLoopback && address.isLoopbackAddress()))
          throw denied();
      if (System.nanoTime() >= deadline) throw new AgentException(AgentErrorCode.TOOL_TIMEOUT);
      var permission = new HttpGetPermission(uri, addresses[0], deadline, policy, EXECUTIONS);
      granted = true;
      return permission;
    } catch (AgentException e) {
      throw e;
    } catch (RejectedExecutionException e) {
      throw new AgentException(AgentErrorCode.RESOURCE_LIMIT);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentException(AgentErrorCode.INTERRUPTED);
    } catch (TimeoutException e) {
      throw new AgentException(AgentErrorCode.TOOL_TIMEOUT);
    } catch (Exception e) {
      throw denied();
    } finally {
      if (future != null && !future.isDone()) future.cancel(true);
      if (!granted) EXECUTIONS.release();
    }
  }

  private URI uri(String text) {
    try {
      var u = URI.create(text);
      if (!u.isAbsolute()
          || u.getRawUserInfo() != null
          || u.getRawFragment() != null
          || u.getHost() == null
          || u.getPort() < -1
          || port(u) < 1
          || port(u) > 65535
          || !("https".equals(u.getScheme()) || testLoopback && "http".equals(u.getScheme())))
        throw denied();
      String host = IDN.toASCII(u.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      if (!host.equals(u.getHost())
          || host.endsWith(".")
          || host.matches("[0-9.]+")
          || host.contains(":")) throw denied();
      String raw = u.getRawPath();
      if (raw == null
          || !raw.startsWith("/")
          || raw.contains("%")
          || raw.contains("\\")
          || !u.normalize().getRawPath().equals(raw)
          || raw.contains("//")) throw denied();
      queryKeys(u);
      return u;
    } catch (RuntimeException e) {
      throw denied();
    }
  }

  private static int port(URI u) {
    return u.getPort() == -1 ? ("https".equals(u.getScheme()) ? 443 : 80) : u.getPort();
  }

  private static Set<String> queryKeys(URI uri) {
    var keys = new HashSet<String>();
    String query = uri.getRawQuery();
    if (query == null) return keys;
    if (query.isEmpty() || query.length() > 2048) throw denied();
    for (String pair : query.split("&", -1)) {
      var parts = pair.split("=", -1);
      if (parts.length != 2
          || !parts[0].matches("[A-Za-z][A-Za-z0-9_]{0,63}")
          || !keys.add(parts[0])
          || parts[1].isEmpty()
          || parts[1].length() > 512) throw denied();
      String decoded = URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
      if (decoded.chars().anyMatch(c -> c < 32 || c == 127) || decoded.contains("%"))
        throw denied();
    }
    return keys;
  }

  public static boolean publicAddress(InetAddress a) {
    if (a.isAnyLocalAddress()
        || a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) return false;
    byte[] b = a.getAddress();
    int x = b[0] & 255, y = b[1] & 255;
    if (b.length == 4)
      return !(x == 0
          || x == 10
          || x == 127
          || x >= 224
          || x == 100 && y >= 64 && y <= 127
          || x == 169 && y == 254
          || x == 172 && y >= 16 && y <= 31
          || x == 192 && (y == 0 || y == 168 || y == 88 && (b[2] & 255) == 99)
          || x == 198 && (y == 18 || y == 19 || y == 51 && (b[2] & 255) == 100)
          || x == 203 && y == 0 && (b[2] & 255) == 113);
    // IPv6 仅接受可分类的全球单播；协议/文档/6to4/映射/转换前缀全部拒绝。
    return b.length == 16
        && (x & 0xe0) == 0x20
        && !(x == 0x20
            && y == 0x01
            && ((b[2] & 255) < 2 || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)))
        && !(x == 0x20 && y == 2)
        && !(x == 0x3f && y == 0xfe)
        && !(x == 0x3f && y == 0xff && (b[2] & 0xf0) == 0);
  }

  private static AgentException denied() {
    return new AgentException(AgentErrorCode.SANDBOX_DENIED);
  }
}
