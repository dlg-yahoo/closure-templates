/*
 * Copyright 2010 Google Inc.
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

package com.google.template.soy.soytree;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.basetree.SyntaxVersion;

import junit.framework.TestCase;


/**
 * Unit tests for CallNode.
 *
 */
public class CallNodeTest extends TestCase {


  /** Escaping list of directive names. */
  private static final ImmutableList<String> NO_ESCAPERS = ImmutableList.of();

  public void testCommandText() throws SoySyntaxException {

    checkCommandText("function=\"bar.foo\"");
    checkCommandText("foo");
    checkCommandText(".foo data=\"all\"");
    checkCommandText("name=\".baz\" data=\"$x\"", ".baz data=\"$x\"");

    try {
      checkCommandText(".foo.bar data=\"$x\"");
      fail();
    } catch (SoySyntaxException e) {
      // Test passes.
    }
  }


  public void testSetEscapingDirectiveNames() throws SoySyntaxException {
    CallBasicNode callNode = new CallBasicNode.Builder(0, SourceLocation.UNKNOWN)
        .commandText(".foo")
        .buildAndThrowIfInvalid();
    assertEquals(ImmutableList.<String>of(), callNode.getEscapingDirectiveNames());
    callNode.setEscapingDirectiveNames(ImmutableList.of("hello", "world"));
    assertEquals(ImmutableList.of("hello", "world"), callNode.getEscapingDirectiveNames());
    callNode.setEscapingDirectiveNames(ImmutableList.of("bye", "world"));
    assertEquals(ImmutableList.of("bye", "world"), callNode.getEscapingDirectiveNames());
  }


  private void checkCommandText(String commandText) {
    checkCommandText(commandText, commandText);
  }


  private void checkCommandText(String commandText, String expectedCommandText) {

    CallBasicNode callNode = new CallBasicNode.Builder(0, SourceLocation.UNKNOWN)
        .commandText(commandText)
        .buildAndThrowIfInvalid();
    if (callNode.getCalleeName() == null) {
      callNode.setCalleeName("testNamespace" + callNode.getSrcCalleeName());
    }

    boolean useV1FunctionAttrForCalleeName
        = !callNode.couldHaveSyntaxVersionAtLeast(SyntaxVersion.V2_0);

    CallBasicNode normCallNode = new CallBasicNode.Builder(0, SourceLocation.UNKNOWN)
        .calleeName(callNode.getCalleeName())
        .sourceCalleeName(callNode.getSrcCalleeName())
        .useV1FunctionAttrForCalleeName(useV1FunctionAttrForCalleeName)
        .isPassingData(callNode.isPassingData())
        .isPassingAllData(callNode.isPassingAllData())
        .dataExpr(callNode.getDataExpr())
        .userSuppliedPlaceholderName(callNode.getUserSuppliedPhName())
        .syntaxVersionBound(callNode.getSyntaxVersionBound())
        .escapingDirectiveNames(NO_ESCAPERS)
        .buildAndThrowIfInvalid();

    assertEquals(expectedCommandText, normCallNode.getCommandText());

    assertEquals(callNode.getSyntaxVersionBound(), normCallNode.getSyntaxVersionBound());
    assertEquals(callNode.getCalleeName(), normCallNode.getCalleeName());
    assertEquals(callNode.isPassingData(), normCallNode.isPassingData());
    assertEquals(callNode.isPassingAllData(), normCallNode.isPassingAllData());
    assertEquals(callNode.getDataExpr(), normCallNode.getDataExpr());
  }

}
