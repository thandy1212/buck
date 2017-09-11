/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.json;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.vfs.FileSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is work in progress and does not support many functions provided by Python
 * DSL parser like {@code read_config} and {@code include_defs} (currently implemented as a noop),
 * so DO NOT USE it production.
 */
public class SkylarkProjectBuildFileParser implements ProjectBuildFileParser {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

  private static final ImmutableSet<String> IMPLICIT_ATTRIBUTES =
      ImmutableSet.of("visibility", "within_view");

  private final FileSystem fileSystem;
  private final TypeCoercerFactory typeCoercerFactory;
  private final ProjectBuildFileParserOptions options;
  private final BuckEventBus buckEventBus;

  private SkylarkProjectBuildFileParser(
      final ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      TypeCoercerFactory typeCoercerFactory) {
    this.options = options;
    this.buckEventBus = buckEventBus;
    this.fileSystem = fileSystem;
    this.typeCoercerFactory = typeCoercerFactory;
  }

  public static SkylarkProjectBuildFileParser using(
      final ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      TypeCoercerFactory typeCoercerFactory) {
    return new SkylarkProjectBuildFileParser(options, buckEventBus, fileSystem, typeCoercerFactory);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAll(Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    return getAllRulesAndMetaRules(buildFile, processedBytes);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAllRulesAndMetaRules(
      Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    ParseBuckFileEvent.Started startEvent = ParseBuckFileEvent.started(buildFile);
    buckEventBus.post(startEvent);
    ImmutableList<Map<String, Object>> rules = ImmutableList.of();
    try {
      rules = parseBuildRules(buildFile);
      LOG.verbose("Got rules: %s", rules);
      LOG.verbose("Parsed %d rules from %s", rules.size(), buildFile);
    } finally {
      // TODO(ttsugrii): think about reporting processed bytes and profiling support
      buckEventBus.post(ParseBuckFileEvent.finished(startEvent, rules, 0L, Optional.empty()));
    }
    return rules;
  }

  /**
   * Parses and returns build rules defined in {@code buildFile}.
   *
   * @param buildFile The build file to parse.
   * @return The build rules defined in {@code buildFile}.
   */
  private ImmutableList<Map<String, Object>> parseBuildRules(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
    // configured for terminals that support it.
    PrintingEventHandler eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(
            ParserInputSource.create(fileSystem.getPath(buildFile.toString())), eventHandler);
    ImmutableList.Builder<Map<String, Object>> builder = ImmutableList.builder();
    try (Mutability mutability = Mutability.create("BUCK")) {
      Environment env = Environment.builder(mutability).build();
      setupBuckRules(buildFile, builder, env);
      boolean exec = buildFileAst.exec(env, eventHandler);
      if (!exec) {
        throw BuildFileParseException.createForUnknownParseError("Cannot parse build file");
      }
      return builder.build();
    }
  }

  /**
   * Sets up native Buck rules in Skylark environment.
   *
   * <p>This makes Buck rules like {@code java_library} available in build files.
   */
  private void setupBuckRules(
      Path buildFile, ImmutableList.Builder<Map<String, Object>> builder, Environment env) {
    for (Description<?> description : options.getDescriptions()) {
      String basePath =
          Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
              .map(MorePaths::pathWithUnixSeparators)
              .orElse("");
      String name = Description.getBuildRuleType(description).getName();
      env.setup(name, newRuleDefinition(description, basePath, builder));
    }
  }

  /**
   * Create a Skylark definition for the {@code ruleClass} rule.
   *
   * <p>This makes functions like @{code java_library} available in build files. All they do is
   * capture passed attribute values in a map and adds them to the {@code ruleRegistry}.
   *
   * @param ruleClass The name of the rule to to define.
   * @param basePath The base path of the build file.
   * @param ruleRegistry The registry of invoked rules with corresponding values.
   * @return Skylark function to handle the Buck rule.
   */
  private BuiltinFunction newRuleDefinition(
      Description<?> ruleClass,
      String basePath,
      ImmutableList.Builder<Map<String, Object>> ruleRegistry) {
    String name = Description.getBuildRuleType(ruleClass).getName();
    return new BuiltinFunction(
        name, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV, /*isRule=*/ true) {

      @SuppressWarnings({"unused"})
      public Runtime.NoneType invoke(
          Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException, InterruptedException {
        ImmutableMap.Builder<String, Object> builder =
            ImmutableMap.<String, Object>builder()
                .put("buck.base_path", basePath)
                .put("buck.type", name);
        ImmutableMap<String, ParamInfo> allParamInfo =
            CoercedTypeCache.INSTANCE.getAllParamInfo(
                typeCoercerFactory, ruleClass.getConstructorArgType());
        populateAttributes(kwargs, builder, allParamInfo);
        throwOnMissingRequiredAttribute(kwargs, allParamInfo);
        ruleRegistry.add(builder.build());
        return Runtime.NONE;
      }
    };
  }

  /**
   * Validates attributes passed to the rule and in case any required attribute is not provided,
   * throws an {@link IllegalArgumentException}.
   *
   * @param kwargs The keyword arguments passed to the rule.
   * @param allParamInfo The mapping from build rule attributes to their information.
   */
  private void throwOnMissingRequiredAttribute(
      Map<String, Object> kwargs, ImmutableMap<String, ParamInfo> allParamInfo) {
    for (Map.Entry<String, ParamInfo> paramInfoEntry : allParamInfo.entrySet()) {
      String pythonName = paramInfoEntry.getValue().getPythonName();
      if (!paramInfoEntry.getValue().isOptional() && !kwargs.containsKey(pythonName)) {
        throw new IllegalArgumentException(pythonName + " is expected but not provided");
      }
    }
  }

  /**
   * Populates provided {@code builder} with values from {@code kwargs} assuming {@code ruleClass}
   * as the target {@link Description} class.
   *
   * @param kwargs The keyword arguments and their values passed to rule function in build file.
   * @param builder The map builder used for storing extracted attributes and their values.
   * @param allParamInfo The parameter information for every build rule attribute.
   */
  private void populateAttributes(
      Map<String, Object> kwargs,
      ImmutableMap.Builder<String, Object> builder,
      ImmutableMap<String, ParamInfo> allParamInfo) {
    for (Map.Entry<String, Object> kwargEntry : kwargs.entrySet()) {
      String paramName =
          CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, kwargEntry.getKey());
      if (!allParamInfo.containsKey(paramName)
          && !(IMPLICIT_ATTRIBUTES.contains(kwargEntry.getKey()))) {
        throw new IllegalArgumentException(kwargEntry.getKey() + " is not a recognized attribute");
      }
      if (Runtime.NONE.equals(kwargEntry.getValue())) {
        continue;
      }
      builder.put(paramName, kwargEntry.getValue());
    }
  }

  @Override
  public void reportProfile() throws IOException {
    // TODO(ttsugrii): implement
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    // nothing to do
  }
}