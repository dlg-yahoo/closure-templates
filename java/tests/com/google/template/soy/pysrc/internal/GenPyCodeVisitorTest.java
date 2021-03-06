/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.pysrc.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.template.soy.pysrc.SoyPySrcOptions;
import com.google.template.soy.pysrc.internal.GenPyExprsVisitor.GenPyExprsVisitorFactory;
import com.google.template.soy.shared.SharedTestUtils;
import com.google.template.soy.shared.internal.GuiceSimpleScope;
import com.google.template.soy.shared.restricted.ApiCallScopeBindingAnnotations.BidiIsRtlFn;
import com.google.template.soy.shared.restricted.ApiCallScopeBindingAnnotations.RuntimePath;
import com.google.template.soy.shared.restricted.ApiCallScopeBindingAnnotations.TranslationPyModuleName;
import com.google.template.soy.soytree.SoyNode;

import junit.framework.TestCase;

import java.util.List;

/**
 * Unit tests for GenPyCodeVisitor.
 *
 * <p>TODO(dcphillips): Add non-inlined 'if' test after adding LetNode support.
 *
 */
public final class GenPyCodeVisitorTest extends TestCase {

  private static final Injector INJECTOR = Guice.createInjector(new PySrcModule());

  private static final LocalVariableStack LOCAL_VAR_EXPRS = new LocalVariableStack();

  private static final String EXPECTED_PYFILE_START =
      "# coding=utf-8\n"
      + "\"\"\" This file was automatically generated from no-path.\n"
      + "Please don't edit this file by hand.\n"
      + "\n"
      + "Templates in namespace boo.foo.\n"
      + "\"\"\"\n"
      + "\n"
      + "from __future__ import unicode_literals\n"
      + "import math\n"
      + "import random\n"
      + "from example.runtime import bidi\n"
      + "from example.runtime import directives\n"
      + "from example.runtime import runtime\n"
      + "from example.runtime import sanitize\n"
      + "\n"
      + "\n"
      + "SOY_NAMESPACE = 'boo.foo'\n"
      + "try:\n"
      + "  str = unicode\n"
      + "except NameError:\n"
      + "  pass\n"
      + "\n";

  private GenPyCodeVisitor genPyCodeVisitor;


  @Override protected void setUp() {
    // Default to empty values for the bidi and translation configs.
    setupGenPyCodeVisitor("", "");
  }

  public void testSoyFile() {
    String soyCode = "{namespace boo.foo autoescape=\"strict\"}\n";

    assertGeneratedPyFile(soyCode, EXPECTED_PYFILE_START);

    // TODO(dcphillips): Add external template dependency import test once templates are supported.
  }

  public void testBidiConfiguration() {
    setupGenPyCodeVisitor("example.bidi.fn", "");

    String soyCode = "{namespace boo.foo autoescape=\"strict\"}\n";

    String exptectedBidiConfig = "from example import bidi as external_bidi\n";

    assertInGeneratedPyFile(soyCode, exptectedBidiConfig);
  }

  public void testTranslationConfiguration() {
    setupGenPyCodeVisitor("", "example.translate.SimpleTranslator");

    String soyCode = "{namespace boo.foo autoescape=\"strict\"}\n";

    String exptectedTranslationConfig = "from example.translate import SimpleTranslator\n"
        + "translator_impl = SimpleTranslator()\n";

    assertInGeneratedPyFile(soyCode, exptectedTranslationConfig);
  }

  public void testBlankTemplate() {
    String soyCode = "{namespace boo.foo autoescape=\"strict\"}\n"
        + "{template .helloWorld kind=\"html\"}\n"
        + "{/template}\n";

    String expectedPyCode = EXPECTED_PYFILE_START + "\n\n"
        + "def helloWorld(opt_data=None, opt_ijData=None):\n"
        + "  output = []\n"
        + "  return sanitize.SanitizedHtml(''.join(output))\n";
    assertGeneratedPyFile(soyCode, expectedPyCode);
  }

  public void testSimpleTemplate() {
    String soyCode = "{namespace boo.foo autoescape=\"strict\"}\n"
        + "{template .helloWorld kind=\"html\"}\n"
        + "  Hello World!\n"
        + "{/template}\n";

    String expectedPyCode = EXPECTED_PYFILE_START + "\n\n"
        + "def helloWorld(opt_data=None, opt_ijData=None):\n"
        + "  output = []\n"
        + "  output.append('Hello World!')\n"
        + "  return sanitize.SanitizedHtml(''.join(output))\n";
    assertGeneratedPyFile(soyCode, expectedPyCode);
  }

  public void testForeach() {
    String soyCode =
        "{foreach $operand in $operands}\n"
        + "  {$operand}\n"
        + "{/foreach}\n";

    // There's no simple way to account for all instances of the id in these variables, so for now
    // we just hardcode '3'.
    String expectedPyCode =
        "operandList4 = opt_data.get('operands')\n"
        + "for operandIndex4, operandData4 in enumerate(operandList4):\n"
        + "  output.append(str(operandData4))\n";

    assertGeneratedPyCode(soyCode, expectedPyCode);

    soyCode =
        "{foreach $operand in $operands}\n"
        + "  {isFirst($operand)}\n"
        + "  {isLast($operand)}\n"
        + "  {index($operand)}\n"
        + "{/foreach}\n";

    expectedPyCode =
        "operandList6 = opt_data.get('operands')\n"
        + "for operandIndex6, operandData6 in enumerate(operandList6):\n"
        + "  output.extend([str(operandIndex6 == 0),"
                         + "str(operandIndex6 == len(operandList6) - 1),"
                         + "str(operandIndex6)])\n";

    assertGeneratedPyCode(soyCode, expectedPyCode);
  }

  public void testForeach_ifempty() {
    String soyCode =
        "{foreach $operand in $operands}\n"
        + "  {$operand}\n"
        + "{ifempty}\n"
        + "  {$foo}"
        + "{/foreach}\n";

    String expectedPyCode =
        "operandList5 = opt_data.get('operands')\n"
        + "if operandList5:\n"
        + "  for operandIndex5, operandData5 in enumerate(operandList5):\n"
        + "    output.append(str(operandData5))\n"
        + "else:\n"
        + "  output.append(str(opt_data.get('foo')))\n";

    assertGeneratedPyCode(soyCode, expectedPyCode);
  }


  // -----------------------------------------------------------------------------------------------
  // Test Utilities.


  private void assertGeneratedPyFile(String soyCode, String expectedPyCode) {
    assertThat(getGeneratedPyFile(soyCode)).isEqualTo(expectedPyCode);
  }

  private void assertInGeneratedPyFile(String soyCode, String expectedPyCode) {
    String generatedCode = getGeneratedPyFile(soyCode);
    assertTrue(generatedCode.contains(expectedPyCode));
  }

  private void assertGeneratedPyCode(String soyNodeCode, String expectedPyCode) {
    assertThat(getGeneratedPyCode(soyNodeCode)).isEqualTo(expectedPyCode);
  }

  private void setupGenPyCodeVisitor(String bidiIsRtlFn, String translationPyModuleName) {
    String runtimePath = "example.runtime";
    SoyPySrcOptions pySrcOptions = new SoyPySrcOptions(runtimePath, bidiIsRtlFn,
        translationPyModuleName);
    GuiceSimpleScope apiCallScope = SharedTestUtils.simulateNewApiCall(INJECTOR, null, null);
    apiCallScope.seed(SoyPySrcOptions.class, pySrcOptions);
    apiCallScope.seed(Key.get(String.class, RuntimePath.class), runtimePath);

    // Default to empty configuration for optional flags.
    apiCallScope.seed(Key.get(String.class, BidiIsRtlFn.class), bidiIsRtlFn);
    apiCallScope.seed(Key.get(String.class, TranslationPyModuleName.class),
        translationPyModuleName);
    genPyCodeVisitor = INJECTOR.getInstance(GenPyCodeVisitor.class);
  }

  /**
   * Generates Python code from the given soy code. Replaces ids with ### so that tests don't break
   * when ids change.
   *
   * @param soyFileContent The string represents a Soy file.
   */
  private String getGeneratedPyFile(String soyFileContent) {
    SoyNode node = SharedTestUtils.parseSoyFiles(soyFileContent).getParseTree();
    List<String> fileContents = genPyCodeVisitor.exec(node);
    return fileContents.get(0).replaceAll("\\([0-9]+", "(###");
  }

  /**
   * Generates Python code from the given soy code. The given piece of Soy code is wrapped in a
   * full body of a template.
   * Also replaces ids with ### so that tests don't break when ids change.
   *
   * @param soyCode The Soy code snippet.
   */
  private String getGeneratedPyCode(String soyCode) {
    SoyNode node = SharedTestUtils.getNode(SharedTestUtils.parseSoyCode(soyCode).getParseTree(), 0);

    // Setup the GenPyCodeVisitor's state before the node is visited.
    genPyCodeVisitor.pyCodeBuilder = new PyCodeBuilder();
    genPyCodeVisitor.pyCodeBuilder.pushOutputVar("output");
    genPyCodeVisitor.pyCodeBuilder.setOutputVarInited();
    genPyCodeVisitor.localVarExprs = LOCAL_VAR_EXPRS;
    genPyCodeVisitor.genPyExprsVisitor =
        INJECTOR.getInstance(GenPyExprsVisitorFactory.class).create(LOCAL_VAR_EXPRS);

    genPyCodeVisitor.visit(node); // note: we're calling visit(), not exec()

    return genPyCodeVisitor.pyCodeBuilder.getCode().replaceAll("\\([0-9]+", "(###");
  }
}
