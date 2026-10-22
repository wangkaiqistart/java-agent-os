package com.aegisos.boot.config;

import com.aegisos.core.profile.ProfileDiagnostic.Code;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.nodes.*;

/** 有界读取 + 事件/节点检查 + SafeConstructor。只靠 SafeConstructor 不能拒绝标量 anchor。 */
public final class SafeProfileYamlReader {
  private static final int LIMIT = 262144;

  /**
   * @param file 已由路径策略验证的profile文件
   * @return 仍需业务字段校验的安全基础类型树
   * @throws ProfileLoadException 大小/深度/节点越限或语法不安全，禁止保留原解析器cause
   */
  public Map<String, Object> read(Path file) {
    try (var in = Files.newInputStream(file)) {
      byte[] bytes = in.readNBytes(LIMIT + 1);
      if (bytes.length > LIMIT) throw error(Code.LIMIT_EXCEEDED);
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (text.codePointCount(0, text.length()) > LIMIT) throw error(Code.LIMIT_EXCEEDED);
      var options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setWarnOnDuplicateKeys(false);
      options.setAllowRecursiveKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setNestingDepthLimit(32);
      options.setCodePointLimit(LIMIT);
      options.setTagInspector(tag -> false);
      var yaml = new Yaml(new SafeConstructor(options));
      int docs = 0;
      int depth = 0;
      int nodes = 0;
      for (var event : yaml.parse(new StringReader(text))) {
        if (event instanceof CollectionStartEvent && ++depth > 32) throw error(Code.LIMIT_EXCEEDED);
        if (event instanceof CollectionEndEvent) depth--;
        if (event instanceof NodeEvent && ++nodes > 4096) throw error(Code.LIMIT_EXCEEDED);
        if (event instanceof DocumentStartEvent && ++docs > 1) throw error(Code.INVALID_YAML);
        if (event instanceof AliasEvent || (event instanceof NodeEvent n && n.getAnchor() != null))
          throw error(Code.INVALID_YAML);
      }
      Node root = yaml.compose(new StringReader(text));
      if (!(root instanceof MappingNode)) throw error(Code.INVALID_YAML);
      inspect(root, 0, new int[] {0});
      Object loaded = yaml.load(text);
      if (!(loaded instanceof Map<?, ?> map)) throw error(Code.INVALID_YAML);
      var result = new LinkedHashMap<String, Object>();
      for (var e : map.entrySet()) {
        if (!(e.getKey() instanceof String key)) throw error(Code.INVALID_YAML);
        result.put(key, e.getValue());
      }
      return result;
    } catch (ProfileLoadException e) {
      throw e;
    } catch (IOException e) {
      throw error(Code.READ_FAILED);
    } catch (RuntimeException e) {
      throw error(Code.INVALID_YAML);
    }
  }

  private static void inspect(Node n, int depth, int[] nodes) {
    if (depth > 32 || ++nodes[0] > 4096) throw error(Code.LIMIT_EXCEEDED);
    if (n.getAnchor() != null
        || !Set.of(Tag.MAP, Tag.SEQ, Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL)
            .contains(n.getTag())) throw error(Code.INVALID_YAML);
    if (n instanceof MappingNode map) {
      var keys = new HashSet<String>();
      for (var tuple : map.getValue()) {
        if (!(tuple.getKeyNode() instanceof ScalarNode key)
            || !Tag.STR.equals(key.getTag())
            || key.getValue().equals("<<")
            || !keys.add(key.getValue())) throw error(Code.INVALID_YAML);
        inspect(key, depth + 1, nodes);
        inspect(tuple.getValueNode(), depth + 1, nodes);
      }
    } else if (n instanceof SequenceNode seq) {
      if (seq.getValue().size() > 256) throw error(Code.LIMIT_EXCEEDED);
      for (var item : seq.getValue()) inspect(item, depth + 1, nodes);
    }
  }

  private static ProfileLoadException error(Code code) {
    return new ProfileLoadException(code);
  }
}
