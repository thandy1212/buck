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

package com.facebook.buck.rules.keys;

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import java.util.Optional;

public class TestInputBasedRuleKeyFactory extends InputBasedRuleKeyFactory {

  public TestInputBasedRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      long inputSizeLimit) {
    super(
        ruleKeyFieldLoader, hashLoader, pathResolver, ruleFinder, inputSizeLimit, Optional.empty());
  }

  public TestInputBasedRuleKeyFactory(
      FileHashLoader hashLoader, SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    super(
        new RuleKeyFieldLoader(0),
        hashLoader,
        pathResolver,
        ruleFinder,
        Long.MAX_VALUE,
        Optional.empty());
  }
}