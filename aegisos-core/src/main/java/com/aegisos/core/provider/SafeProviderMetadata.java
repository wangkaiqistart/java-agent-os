package com.aegisos.core.provider;

import java.util.regex.Pattern;

/** 只接纳有限长度的标识；从不处理正文、URL 或异常消息。 */
public final class SafeProviderMetadata {
  private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

  private SafeProviderMetadata() {}

  public static String provider(String s) {
    return s != null && PROVIDER.matcher(s).matches() ? s : null;
  }

  public static String model(String s) {
    return s != null && MODEL.matcher(s).matches() ? s : null;
  }
}
