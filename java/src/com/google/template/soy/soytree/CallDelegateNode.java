/*
 * Copyright 2011 Google Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.template.soy.base.ErrorManager;
import com.google.template.soy.base.ErrorManagerImpl;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.exprparse.ExprParseUtils;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.internal.base.Pair;
import com.google.template.soy.soytree.CommandTextAttributesParser.Attribute;
import com.google.template.soy.soytree.defn.TemplateParam;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;


/**
 * Node representing a call to a delegate template.
 *
 * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class CallDelegateNode extends CallNode {


  /**
   * Private helper class used by constructors. Encapsulates all the info derived from the command
   * text.
   */
  @Immutable
  private static class CommandTextInfo extends CallNode.CommandTextInfo {

    public final String delCalleeName;
    @Nullable public final ExprRootNode<?> delCalleeVariantExpr;
    public final Boolean allowsEmptyDefault;

    public CommandTextInfo(
        String commandText, String delCalleeName, @Nullable ExprRootNode<?> delCalleeVariantExpr,
        Boolean allowsEmptyDefault, boolean isPassingData, @Nullable ExprRootNode<?> dataExpr,
        @Nullable String userSuppliedPlaceholderName) {
      super(commandText, isPassingData, dataExpr, userSuppliedPlaceholderName, null);
      this.delCalleeName = delCalleeName;
      this.delCalleeVariantExpr = delCalleeVariantExpr;
      this.allowsEmptyDefault = allowsEmptyDefault;
    }
  }

  /** Pattern for a callee name not listed as an attribute name="...". */
  private static final Pattern NONATTRIBUTE_CALLEE_NAME =
      Pattern.compile("^ (?! name=\") [.\\w]+ (?= \\s | $)", Pattern.COMMENTS);

  /** Parser for the command text. */
  private static final CommandTextAttributesParser ATTRIBUTES_PARSER =
      new CommandTextAttributesParser("delcall",
          new Attribute("name", Attribute.ALLOW_ALL_VALUES, null),
          new Attribute("variant", Attribute.ALLOW_ALL_VALUES, null),
          new Attribute("data", Attribute.ALLOW_ALL_VALUES, null),
          new Attribute("allowemptydefault", Attribute.BOOLEAN_VALUES, null));


  /** The name of the delegate template being called. */
  private final String delCalleeName;

  /** The variant expression for the delegate being called, or null. */
  @Nullable private final ExprRootNode<?> delCalleeVariantExpr;

  /** User-specified value of whether this delegate call defaults to empty string if there's no
   *  active implementation, or null if the attribute is not specified. */
  private Boolean allowsEmptyDefault;

  /**
   * The list of params that need to be type checked when this node is run on a per delegate basis.
   * All the params that could be statically verified will be checked up front (by the
   * {@code CheckCallingParamTypesVisitor}), this list contains the params that could not be
   * statically checked.
   *
   * <p>NOTE:This list will be a subset of the params of the callee, not a subset of the params
   * passed from this caller.
   */
  private ImmutableMap<TemplateDelegateNode, ImmutableList<TemplateParam>>
      paramsToRuntimeCheckByDelegate;


  public static final class Builder {

    public static final CallDelegateNode ERROR = new Builder(-1, SourceLocation.UNKNOWN)
        .commandText("error.error")
        .buildAndThrowIfInvalid(); // guaranteed to be valid

    private final int id;
    private final SourceLocation sourceLocation;

    private boolean allowEmptyDefault;
    private boolean isPassingData;
    private boolean isPassingAllData;
    private ImmutableList<String> escapingDirectiveNames = ImmutableList.of();

    @Nullable private String commandText;
    @Nullable private String delCalleeName;
    @Nullable private ExprRootNode<?> delCalleeVariantExpr;
    @Nullable private ExprRootNode<?> dataExpr;
    @Nullable private String userSuppliedPlaceholderName;

    public Builder(int id, SourceLocation sourceLocation) {
      this.id = id;
      this.sourceLocation = sourceLocation;
    }

    public Builder allowEmptyDefault(boolean allowEmptyDefault) {
      this.allowEmptyDefault = allowEmptyDefault;
      return this;
    }

    public Builder commandText(String commandText) {
      this.commandText = commandText;
      return this;
    }

    public Builder dataExpr(ExprRootNode<?> dataExpr) {
      this.dataExpr = dataExpr;
      return this;
    }

    public Builder delCalleeName(String delCalleeName) {
      this.delCalleeName = delCalleeName;
      return this;
    }

    public Builder delCalleeVariantExpr(ExprRootNode<?> delCalleeVariantExpr) {
      this.delCalleeVariantExpr = delCalleeVariantExpr;
      return this;
    }

    public Builder escapingDirectiveNames(ImmutableList<String> escapingDirectiveNames) {
      this.escapingDirectiveNames = escapingDirectiveNames;
      return this;
    }

    public Builder isPassingData(boolean isPassingData) {
      this.isPassingData = isPassingData;
      return this;
    }

    public Builder isPassingAllData(boolean isPassingAllData) {
      this.isPassingAllData = isPassingAllData;
      return this;
    }

    public Builder userSuppliedPlaceholderName(String userSuppliedPlaceholderName) {
      this.userSuppliedPlaceholderName = userSuppliedPlaceholderName;
      return this;
    }

    public CallDelegateNode build(ErrorManager errorManager) {
      int prevNumErrors = errorManager.getErrors().size();
      CommandTextInfo commandTextInfo = commandText != null
          ? parseCommandText(errorManager)
          : buildCommandText();
      int newNumErrors = errorManager.getErrors().size();
      boolean ok = newNumErrors == prevNumErrors;
      if (ok) {
        CallDelegateNode callDelegateNode
            = new CallDelegateNode(id, commandTextInfo, escapingDirectiveNames);
        callDelegateNode.setSourceLocation(sourceLocation);
        return callDelegateNode;
      } else {
        return ERROR;
      }
    }

    /**
     * @throws SoySyntaxException if the data given to the Builder cannot be used to construct
     *     a {@link CallBasicNode}.
     * TODO(user): remove. The parser already has an ErrorManager. This method exists
     *     solely for higher layers (like visitors) that do not already have ErrorManagers.
     */
    public CallDelegateNode buildAndThrowIfInvalid() {
      ErrorManager errorManager = new ErrorManagerImpl();
      CallDelegateNode node = build(errorManager);
      if (!errorManager.getErrors().isEmpty()) {
        throw errorManager.getErrors().iterator().next();
      }
      return node;
    }

    private CommandTextInfo parseCommandText(ErrorManager errorManager) {
      String commandTextWithoutPhnameAttr = this.commandText;

      String commandText =
          commandTextWithoutPhnameAttr +
              ((userSuppliedPlaceholderName != null) ?
                  " phname=\"" + userSuppliedPlaceholderName + "\"" : "");

      // Handle callee name not listed as an attribute.
      Matcher ncnMatcher = NONATTRIBUTE_CALLEE_NAME.matcher(commandTextWithoutPhnameAttr);
      if (ncnMatcher.find()) {
        commandTextWithoutPhnameAttr
            = ncnMatcher.replaceFirst("name=\"" + ncnMatcher.group() + "\"");
      }

      Map<String, String> attributes =
          ATTRIBUTES_PARSER.parse(commandTextWithoutPhnameAttr, errorManager, sourceLocation);

      String delCalleeName = attributes.get("name");
      if (delCalleeName == null) {
        errorManager.report(SoySyntaxException.createWithMetaInfo(
            "The 'delcall' command text must contain the callee name (encountered command text \""
                + commandTextWithoutPhnameAttr + "\").", sourceLocation));
      }
      if (!BaseUtils.isDottedIdentifier(delCalleeName)) {
        errorManager.report(SoySyntaxException.createWithMetaInfo(
            "Invalid delegate name \"" + delCalleeName + "\" for 'delcall' command.",
            sourceLocation));
      }

      String variantExprText = attributes.get("variant");
      ExprRootNode<?> delCalleeVariantExpr;
      if (variantExprText == null) {
        delCalleeVariantExpr = null;
      } else {
        delCalleeVariantExpr = ExprParseUtils.parseExprElseThrowSoySyntaxException(
            variantExprText,
            String.format("Invalid variant expression \"%s\" in 'delcall'.", variantExprText));
        // If the variant is a fixed string, do a sanity check.
        if (delCalleeVariantExpr.getChild(0) instanceof StringNode) {
          String fixedVariantStr = ((StringNode) delCalleeVariantExpr.getChild(0)).getValue();
          if (!BaseUtils.isIdentifier(fixedVariantStr)) {
            errorManager.report(SoySyntaxException.createWithMetaInfo(
                "Invalid variant expression \"" + variantExprText + "\" in 'delcall'" +
                    " (variant expression must evaluate to an identifier).", sourceLocation));
          }
        }
      }

      Pair<Boolean, ExprRootNode<?>> dataAttrInfo =
          parseDataAttributeHelper(attributes.get("data"), commandText);

      String allowemptydefaultAttr = attributes.get("allowemptydefault");
      Boolean allowsEmptyDefault =
          (allowemptydefaultAttr == null) ? null : allowemptydefaultAttr.equals("true");

      return new CommandTextInfo(
          commandText, delCalleeName, delCalleeVariantExpr, allowsEmptyDefault, dataAttrInfo.first,
          dataAttrInfo.second, userSuppliedPlaceholderName);
    }

    private CommandTextInfo buildCommandText() {

      Preconditions.checkArgument(BaseUtils.isDottedIdentifier(delCalleeName));
      if (isPassingAllData) {
        Preconditions.checkArgument(isPassingData);
      }
      if (dataExpr != null) {
        Preconditions.checkArgument(isPassingData && ! isPassingAllData);
      }

      String commandText = "";
        commandText += delCalleeName;
      if (isPassingAllData) {
        commandText += " data=\"all\"";
      } else if (isPassingData) {
        assert dataExpr != null;  // suppress warnings
        commandText += " data=\"" + dataExpr.toSourceString() + '"';
      }
      if (userSuppliedPlaceholderName != null) {
        commandText += " phname=\"" + userSuppliedPlaceholderName + '"';
      }

      return new CommandTextInfo(
          commandText, delCalleeName, delCalleeVariantExpr, allowEmptyDefault, isPassingData,
          dataExpr, userSuppliedPlaceholderName);
    }
  }

  private CallDelegateNode(
      int id, CommandTextInfo commandTextInfo, ImmutableList<String> escapingDirectiveNames) {
    super(id, "delcall", commandTextInfo, escapingDirectiveNames);
    this.delCalleeName = commandTextInfo.delCalleeName;
    this.delCalleeVariantExpr = commandTextInfo.delCalleeVariantExpr;
    this.allowsEmptyDefault = commandTextInfo.allowsEmptyDefault;
  }


  /**
   * Copy constructor.
   * @param orig The node to copy.
   */
  @SuppressWarnings("ConstantConditions")  // for IntelliJ
  protected CallDelegateNode(CallDelegateNode orig) {
    super(orig);
    this.delCalleeName = orig.delCalleeName;
    this.delCalleeVariantExpr =
        (orig.delCalleeVariantExpr != null) ? orig.delCalleeVariantExpr.clone() : null;
    this.allowsEmptyDefault = orig.allowsEmptyDefault;
    this.paramsToRuntimeCheckByDelegate = orig.paramsToRuntimeCheckByDelegate;
  }


  @Override public Kind getKind() {
    return Kind.CALL_DELEGATE_NODE;
  }


  /** Returns the name of the delegate template being called. */
  public String getDelCalleeName() {
    return delCalleeName;
  }


  /** Returns the variant expression for the delegate being called, or null if it's a string. */
  @Nullable public ExprRootNode<?> getDelCalleeVariantExpr() {
    return delCalleeVariantExpr;
  }


  /** Sets allowsEmptyDefault to the given default value if it wasn't already user-specified. */
  public void maybeSetAllowsEmptyDefault(boolean defaultValueForAllowsEmptyDefault) {
    if (allowsEmptyDefault == null) {
      allowsEmptyDefault = defaultValueForAllowsEmptyDefault;
    }
  }

  /**
   * Sets the template params that require runtime type checking for each possible delegate target.
   */
  public void setParamsToRuntimeCheck(
      ImmutableMap<TemplateDelegateNode, ImmutableList<TemplateParam>> paramsToRuntimeCheck) {
    this.paramsToRuntimeCheckByDelegate = Preconditions.checkNotNull(paramsToRuntimeCheck);
  }

  @Override public Collection<TemplateParam> getParamsToRuntimeCheck(TemplateNode callee) {
    if (paramsToRuntimeCheckByDelegate == null) {
      return callee.getParams();
    }
    ImmutableList<TemplateParam> params = paramsToRuntimeCheckByDelegate.get(callee);
    if (params == null) {
      // The callee was not known when we performed static type checking.  Check all params.
      return callee.getParams();
    }
    return params;
  }

  /** Returns whether this delegate call defaults to empty string if there's no active impl. */
  public boolean allowsEmptyDefault() {
    Preconditions.checkState(allowsEmptyDefault != null);
    return allowsEmptyDefault;
  }


  @Override public List<ExprUnion> getAllExprUnions() {
    List<ExprUnion> allExprUnions = Lists.newArrayListWithCapacity(2);
    if (delCalleeVariantExpr != null) {
      allExprUnions.add(new ExprUnion(delCalleeVariantExpr));
    }
    allExprUnions.addAll(super.getAllExprUnions());
    return Collections.unmodifiableList(allExprUnions);
  }


  @Override public CallDelegateNode clone() {
    return new CallDelegateNode(this);
  }

}
