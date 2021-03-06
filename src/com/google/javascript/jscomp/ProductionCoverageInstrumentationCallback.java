/*
 * Copyright 2020 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.collect.ImmutableMap;
import com.google.debugging.sourcemap.Base64VLQ;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Instrument production coverage for javascript. This type of coverage will instrument different
 * levels of source code such as function and branch instrumentation. This instrumentation differs
 * from the previous implementations ({@link CoverageInstrumentationCallback} and {@link
 * BranchCoverageInstrumentationCallback}) in that it is properly optimized and obfuscated so that
 * it can be run on client browsers with the goal of better detecting dead code. The callback will
 * instrument with a function call which is provided in the source code as opposed to an array.
 */
@GwtIncompatible
final class ProductionCoverageInstrumentationCallback
    extends NodeTraversal.AbstractPostOrderCallback {

  // TODO(psokol): Make this dynamic so that instrumentation does not rely on hardcoded files
  private static final String INSTRUMENT_CODE_FUNCTION_NAME = "instrumentCode";
  private static final String INSTRUMENT_CODE_FILE_NAME = "InstrumentCode.js";

  /**
   * The compiler runs an earlier pass that combines all modules and renames them appropriately.
   * This constant represents what the INSTRUMENT_CODE_FILE_NAME module will be renamed to by the
   * compiler and this will be used to make the correct call to INSTRUMENT_CODE_FUNCTION_NAME.
   */
  private static final String MODULE_RENAMING = "module$exports$instrument$code";

  /**
   * INSTRUMENT_CODE_FILE_NAME will contain an instance of the instrumentCode class and that
   * instance name is stored in this constant.
   */
  private static final String INSTRUMENT_CODE_INSTANCE = "instrumentCodeInstance";

  private final AbstractCompiler compiler;
  private final ParameterMapping parameterMapping;
  boolean visitedInstrumentCodeFile = false;

  /** Stores the name of the current function that encapsulates the node being instrumented */
  private String cachedFunctionName = "Anonymous";

  public ProductionCoverageInstrumentationCallback(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.parameterMapping = new ParameterMapping();
  }

  @Override
  public void visit(NodeTraversal traversal, Node node, Node parent) {
    String fileName = traversal.getSourceName();
    String sourceFileName = node.getSourceFileName();

    // If origin of node is not from sourceFile, do not instrument. This typically occurs when
    // polyfill code is injected into the sourceFile AST and this check avoids instrumenting it. We
    // avoid instrumentation as this callback does not distinguish between sourceFile code and
    // injected code and can result in an error.
    if (!Objects.equals(fileName, sourceFileName)) {
      return;
    }

    // If Source File INSTRUMENT_CODE_FILE_NAME has not yet been visited, do not instrument as
    // the instrument function has not yet been defined and any call made to it will result in an
    // error in the compiled JS code.
    if (!visitedInstrumentCodeFile || sourceFileName.endsWith(INSTRUMENT_CODE_FILE_NAME)) {
      if (sourceFileName.endsWith(INSTRUMENT_CODE_FILE_NAME)) {
        visitedInstrumentCodeFile = true;
      }
      return;
    }

    if (node.isFunction()) {
      cachedFunctionName = NodeUtil.getBestLValueName(NodeUtil.getBestLValue(node));
      instrumentCode(traversal, node.getLastChild(), cachedFunctionName);
    }
  }

  /**
   * Iterate over all collected block nodes within a Script node and add a new child to the front of
   * each block node which is the instrumentation Node
   *
   * @param traversal The node traversal context which maintains information such as fileName being
   *     traversed
   * @param block The block node to be instrumented instrumented
   * @param fnName The function name that encapsulates the current node block being instrumented
   */
  private void instrumentCode(NodeTraversal traversal, Node block, String fnName) {
    block.addChildToFront(newInstrumentationNode(traversal, block, fnName));
    compiler.reportChangeToEnclosingScope(block);
  }

  /**
   * Create a function call to the Instrument Code function with properly encoded parameters. The
   * instrumented function call will be of the following form:
   * MODULE_RENAMING.INSTRUMENT_CODE_INSTANCE.INSTRUMENT_CODE_FUNCTION_NAME(param1, param2). This
   * with the given constants evaluates to:
   * module$exports$instrument$code.instrumentCodeInstance.instrumentCode(encodedParam, lineNum);
   *
   * @param traversal The context of the current traversal.
   * @param node The block node to be instrumented.
   * @param fnName The function name that the node exists within.
   * @return The newly constructed function call node.
   */
  private Node newInstrumentationNode(NodeTraversal traversal, Node node, String fnName) {

    String type = "Type.FUNCTION";

    String encodedParam = parameterMapping.getEncodedParam(traversal.getSourceName(), fnName, type);

    Node innerProp = IR.getprop(IR.name(MODULE_RENAMING), IR.string(INSTRUMENT_CODE_INSTANCE));
    Node outerProp = IR.getprop(innerProp, IR.string(INSTRUMENT_CODE_FUNCTION_NAME));
    Node functionCall = IR.call(outerProp, IR.string(encodedParam), IR.number(node.getLineno()));
    Node exprNode = IR.exprResult(functionCall);

    return exprNode.useSourceInfoIfMissingFromForTree(node);
  }

  public VariableMap getInstrumentationMapping() {
    return parameterMapping.getParamMappingAsVariableMap();
  }

  /**
   * A class the maintains a mapping of unique identifiers to parameter values. It also generates
   * unique identifiers by creating a counter starting form 0 and increments this value when
   * assigning a new unique identifier. It converts the mapping to a VariableMap as well so that it
   * can later be saved as a file.
   */
  private static final class ParameterMapping {

    // Values are stored as a mapping of the String of the encoded array indices to a String of the
    // encoded uniqueIdentifier. This is so we can check if an encoded param has already been
    // defined so that we do not create a duplicate. (Ex. Key: ACA (Base64 VLQ encoding of [0,1,0]),
    // Value: C (Base64 VLQ encoding of the uniqueIdentifier). This map will later be inversed so
    // that it is printed in the following form: C:ACA.
    private final Map<String, String> paramValueEncodings;

    // Values are stored as a mapping of String to Integers so that we can lookup the index of the
    // encoded (file|function|type) name and also check if it is present in constant time. These
    // mappings are added to the VariableMap once instrumentation is complete.
    private final Map<String, Integer> fileNameToIndex;
    private final Map<String, Integer> functionNameToIndex;
    private final Map<String, Integer> typeToIndex;

    private long nextUniqueIdentifier;

    ParameterMapping() {
      nextUniqueIdentifier = 0;

      paramValueEncodings = new HashMap<>();

      // A LinkedHashMap is used so that when keys are printed, keySet() will obtain them in the
      // insertion order which corroborates to the index. This helps to avoid the need of sorting
      // by the Integer value and is more convenient.
      fileNameToIndex = new LinkedHashMap<>();
      functionNameToIndex = new LinkedHashMap<>();
      typeToIndex = new LinkedHashMap<>();
    }

    private String getEncodedParam(String fileName, String functionName, String type) {

      fileNameToIndex.putIfAbsent(fileName, fileNameToIndex.size());
      functionNameToIndex.putIfAbsent(functionName, functionNameToIndex.size());
      typeToIndex.putIfAbsent(type, typeToIndex.size());

      StringBuilder sb = new StringBuilder();

      try {
        Base64VLQ.encode(sb, fileNameToIndex.get(fileName));
        Base64VLQ.encode(sb, functionNameToIndex.get(functionName));
        Base64VLQ.encode(sb, typeToIndex.get(type));
      } catch (IOException e) {
        throw new AssertionError(e);
      }

      String encodedParam = sb.toString();

      if (!paramValueEncodings.containsKey(encodedParam)) {
        long uniqueIdentifier = generateUniqueIdentifier();
        if (uniqueIdentifier > Integer.MAX_VALUE) {
          throw new ArithmeticException(
              "Unique Identifier exceeds value of Integer.MAX_VALUE, could not encode with Base 64"
                  + " VLQ");
        }

        sb = new StringBuilder();

        try {
          Base64VLQ.encode(sb, Math.toIntExact(uniqueIdentifier));
        } catch (IOException e) {
          throw new AssertionError(e);
        }

        paramValueEncodings.put(encodedParam, sb.toString());
      }

      return paramValueEncodings.get(encodedParam);
    }

    private long generateUniqueIdentifier() {
      nextUniqueIdentifier++;
      return nextUniqueIdentifier;
    }

    private VariableMap getParamMappingAsVariableMap() {
      // Array names are given a " " (space) prefix since when writing to file, VariableMap.java
      // sorts the map by key values. This space will place the arrays at the top of the file.
      // The key and value entry are put in this order because the map will be inversed.
      paramValueEncodings.put(fileNameToIndex.keySet().toString(), " FileNames");
      paramValueEncodings.put(functionNameToIndex.keySet().toString(), " FunctionNames");
      paramValueEncodings.put(typeToIndex.keySet().toString(), " Types");

      VariableMap preInversedMap = new VariableMap(paramValueEncodings);
      ImmutableMap<String, String> inversedMap = preInversedMap.getNewNameToOriginalNameMap();
      return new VariableMap(inversedMap);
    }
  }
}
