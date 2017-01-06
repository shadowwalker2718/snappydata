/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
/*
 * Portions of code adapted from Spark's BroadcastHashJoinExec having
 * license as below.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.joins

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode, GenerateUnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.{AttributeSet, BindReferences, BoundReference, Expression}
import org.apache.spark.sql.catalyst.plans.{ExistenceJoin, Inner, JoinType, LeftAnti, LeftOuter, LeftSemi, RightOuter}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.execution.{CodegenSupport, SparkPlan}
import org.apache.spark.sql.sources.IndexScan

@DeveloperApi
class IndexJoin(_leftKeys: Seq[Expression],
    _rightKeys: Seq[Expression],
    _buildSide: BuildSide,
    _condition: Option[Expression],
    _joinType: JoinType,
    _left: SparkPlan,
    _right: SparkPlan,
    _leftSizeInBytes: BigInt,
    _rightSizeInBytes: BigInt,
    _replicatedTableJoin: Boolean,
    val indexScan: IndexScan)
    extends LocalJoin(_leftKeys, _rightKeys, _buildSide, _condition, _joinType,
      _left, _right, _leftSizeInBytes, _rightSizeInBytes, _replicatedTableJoin) {

  @transient private var indexTerm: String = _

  override def productElement(n: Int): Any =
    if (n < 10) super.productElement(n) else indexScan

  override def productArity: Int = 11

  override def copy(leftKeys: Seq[Expression],
      rightKeys: Seq[Expression],
      buildSide: BuildSide,
      condition: Option[Expression],
      joinType: JoinType,
      left: SparkPlan,
      right: SparkPlan,
      leftSizeInBytes: BigInt,
      rightSizeInBytes: BigInt,
      replicatedTableJoin: Boolean): SparkPlan = new IndexJoin(leftKeys,
    rightKeys, buildSide, condition, joinType, left, right, leftSizeInBytes,
    rightSizeInBytes, replicatedTableJoin, indexScan)

  override def nodeName: String = "IndexJoin"

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"))

  override def usedInputs: AttributeSet = references

  override def canConsume(plan: SparkPlan): Boolean = false

  private def indexPlan = streamedPlan

  private def iteratePlan = buildPlan

  private def iterationKeys = buildSideKeys

  override def doProduce(ctx: CodegenContext): String = {
    // stream side is where the index is while iteration needs to
    // be done on the buildSide
    val indexObj = ctx.addReferenceObj("indexScan", indexScan)
    val indexClass = indexScan.getClass.getName
    // final stack variable to help reduce virtual calls
    indexTerm = ctx.freshName("index")
    s"""
       |final $indexClass $indexTerm = $indexObj;
       |${iteratePlan.asInstanceOf[CodegenSupport].produce(ctx, this)}
    """.stripMargin
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode],
      row: ExprCode): String = {
    joinType match {
      case Inner => innerJoin(ctx, input, indexTerm)
      case LeftOuter | RightOuter => outerJoin(ctx, input, indexTerm)
      case LeftSemi => semiJoin(ctx, input, indexTerm)
      case LeftAnti => antiJoin(ctx, input, indexTerm)
      case _: ExistenceJoin => existenceJoin(ctx, input, indexTerm)
      case x => throw new IllegalArgumentException(
        s"IndexJoin should not take $x as the JoinType")
    }
  }

  /**
   * Returns the code for generating join key for iteration side,
   * and expression of whether the key has any null in it or not.
   */
  // A more efficient SpecificMutableRow can be used to reduce copying
  // in UnsafeRows (e.g. of strings) but this case is only for row tables
  // while for column tables a different implementation will be used
  private def genIterationSideJoinKey(
      ctx: CodegenContext,
      input: Seq[ExprCode]): (ExprCode, String) = {
    ctx.currentVars = input
    // generate the join key as UnsafeRow
    val ev = GenerateUnsafeProjection.createCode(ctx, iterationKeys)
    (ev, s"${ev.value}.anyNull()")
  }

  /**
   * Generates the code for variable of index side.
   */
  private def genIndexSideVars(ctx: CodegenContext,
      matched: String): Seq[ExprCode] = {
    ctx.currentVars = null
    ctx.INPUT_ROW = matched
    indexPlan.output.zipWithIndex.map { case (a, i) =>
      val ev = BoundReference(i, a.dataType, a.nullable).genCode(ctx)
      if (joinType == Inner) {
        ev
      } else {
        // the variables are needed even there is no matched rows
        val isNull = ctx.freshName("isNull")
        val value = ctx.freshName("value")
        val code =
          s"""
             |boolean $isNull = true;
             |${ctx.javaType(a.dataType)} $value = ${ctx.defaultValue(a.dataType)};
             |if ($matched != null) {
             |  ${ev.code}
             |  $isNull = ${ev.isNull};
             |  $value = ${ev.value};
             |}
         """.stripMargin
        ExprCode(code, isNull, value)
      }
    }
  }

  /**
   * Generate the (non-equi) condition used to filter joined rows.
   * This is used in Inner, Left Semi and Left Anti joins.
   */
  private def getJoinCondition(
      ctx: CodegenContext,
      input: Seq[ExprCode],
      anti: Boolean = false): (String, String, Seq[ExprCode]) = {
    val matched = ctx.freshName("matched")
    val indexVars = genIndexSideVars(ctx, matched)
    val checkCondition = if (condition.isDefined) {
      val expr = condition.get
      // evaluate the variables from index side that used by condition
      val eval = evaluateRequiredVariables(indexPlan.output,
        indexVars, expr.references)
      // filter the output via condition
      ctx.currentVars = input ++ indexVars
      val ev = BindReferences.bindReference(expr,
        iteratePlan.output ++ indexPlan.output).genCode(ctx)
      val skipRow = if (!anti) {
        s"${ev.isNull} || !${ev.value}"
      } else {
        s"!${ev.isNull} && ${ev.value}"
      }
      s"""
         |$eval
         |${ev.code}
         |if ($skipRow) continue;
       """.stripMargin
    } else if (anti) {
      "continue;"
    } else {
      ""
    }
    (matched, checkCondition, indexVars)
  }

  /**
   * Generates the code for Inner join.
   */
  private def innerJoin(ctx: CodegenContext, input: Seq[ExprCode],
      indexTerm: String): String = {
    val (keyEv, anyNull) = genIterationSideJoinKey(ctx, input)
    val (matched, checkCondition, indexVars) = getJoinCondition(ctx, input)
    val numOutput = metricTerm(ctx, "numOutputRows")

    val resultVars = buildSide match {
      case BuildLeft => input ++ indexVars
      case BuildRight => indexVars ++ input
    }
    if (indexScan.keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |InternalRow $matched = $anyNull ? null: $indexTerm.getValue(${keyEv.value});
         |if ($matched == null) continue;
         |$checkCondition
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, resultVars)}
       """.stripMargin
    } else {
      ctx.copyResult = true
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[InternalRow]].getName
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$indexTerm.get(${keyEv.value});
         |if ($matches == null) continue;
         |while ($matches.hasNext()) {
         |  InternalRow $matched = $matches.next();
         |  $checkCondition
         |  $numOutput.${metricAdd("1")};
         |  ${consume(ctx, resultVars)}
         |}
       """.stripMargin
    }
  }


  /**
   * Generates the code for left or right outer join.
   */
  private def outerJoin(ctx: CodegenContext,
      input: Seq[ExprCode], indexTerm: String): String = {
    val (keyEv, anyNull) = genIterationSideJoinKey(ctx, input)
    val matched = ctx.freshName("matched")
    val indexVars = genIndexSideVars(ctx, matched)
    val numOutput = metricTerm(ctx, "numOutputRows")

    // filter the output via condition
    val conditionPassed = ctx.freshName("conditionPassed")
    val checkCondition = if (condition.isDefined) {
      val expr = condition.get
      // evaluate the variables from index side that used by condition
      val eval = evaluateRequiredVariables(indexPlan.output,
        indexVars, expr.references)
      ctx.currentVars = input ++ indexVars
      val ev = BindReferences.bindReference(expr,
        iteratePlan.output ++ indexPlan.output).genCode(ctx)
      s"""
         |boolean $conditionPassed = true;
         |${eval.trim}
         |${ev.code}
         |if ($matched != null) {
         |  $conditionPassed = !${ev.isNull} && ${ev.value};
         |}
       """.stripMargin
    } else {
      s"final boolean $conditionPassed = true;"
    }

    val resultVars = buildSide match {
      case BuildLeft => input ++ indexVars
      case BuildRight => indexVars ++ input
    }
    if (indexScan.keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |InternalRow $matched = $anyNull ? null: $indexTerm.getValue(${keyEv.value});
         |${checkCondition.trim}
         |if (!$conditionPassed) {
         |  $matched = null;
         |  // reset the variables those are already evaluated.
         |  ${indexVars.filter(_.code == "").map(v => s"${v.isNull} = true;").mkString("\n")}
         |}
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, resultVars)}
       """.stripMargin

    } else {
      ctx.copyResult = true
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[InternalRow]].getName
      val found = ctx.freshName("found")
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$indexTerm.get(${keyEv.value});
         |boolean $found = false;
         |// the last iteration of this loop is to emit an empty row if there is no matched rows.
         |while ($matches != null && $matches.hasNext() || !$found) {
         |  InternalRow $matched = $matches != null && $matches.hasNext() ?
         |    $matches.next() : null;
         |  ${checkCondition.trim}
         |  if (!$conditionPassed) continue;
         |  $found = true;
         |  $numOutput.${metricAdd("1")};
         |  ${consume(ctx, resultVars)}
         |}
       """.stripMargin
    }
  }

  /**
   * Generates the code for left semi join.
   */
  private def semiJoin(ctx: CodegenContext,
      input: Seq[ExprCode], indexTerm: String): String = {
    val (keyEv, anyNull) = genIterationSideJoinKey(ctx, input)
    val (matched, checkCondition, _) = getJoinCondition(ctx, input)
    val numOutput = metricTerm(ctx, "numOutputRows")
    if (indexScan.keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |InternalRow $matched = $anyNull ? null: $indexTerm.getValue(${keyEv.value});
         |if ($matched == null) continue;
         |$checkCondition
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, input)}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[InternalRow]].getName
      val found = ctx.freshName("found")
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$indexTerm.get(${keyEv.value});
         |if ($matches == null) continue;
         |boolean $found = false;
         |while (!$found && $matches.hasNext()) {
         |  InternalRow $matched = $matches.next();
         |  $checkCondition
         |  $found = true;
         |}
         |if (!$found) continue;
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, input)}
       """.stripMargin
    }
  }

  /**
   * Generates the code for anti join.
   */
  private def antiJoin(ctx: CodegenContext,
      input: Seq[ExprCode], indexTerm: String): String = {
    val uniqueKeyCodePath = indexScan.keyIsUnique
    val (keyEv, anyNull) = genIterationSideJoinKey(ctx, input)
    val (matched, checkCondition, _) = getJoinCondition(ctx, input, uniqueKeyCodePath)
    val numOutput = metricTerm(ctx, "numOutputRows")

    if (uniqueKeyCodePath) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// Check if the key has nulls.
         |if (!($anyNull)) {
         |  // Check for a match in IndexScan.
         |  InternalRow $matched = $indexTerm.getValue(${keyEv.value});
         |  if ($matched != null) {
         |    // Evaluate the condition.
         |    $checkCondition
         |  }
         |}
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, input)}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[InternalRow]].getName
      val found = ctx.freshName("found")
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// Check if the key has nulls.
         |if (!($anyNull)) {
         |  // Check for a match in IndexScan.
         |  $iteratorCls $matches = ($iteratorCls)$indexTerm.get(${keyEv.value});
         |  if ($matches != null) {
         |    // Evaluate the condition.
         |    boolean $found = false;
         |    while (!$found && $matches.hasNext()) {
         |      InternalRow $matched = $matches.next();
         |      $checkCondition
         |      $found = true;
         |    }
         |    if ($found) continue;
         |  }
         |}
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, input)}
       """.stripMargin
    }
  }

  /**
   * Generates the code for existence join.
   */
  private def existenceJoin(ctx: CodegenContext,
      input: Seq[ExprCode], indexTerm: String): String = {
    val (keyEv, anyNull) = genIterationSideJoinKey(ctx, input)
    val numOutput = metricTerm(ctx, "numOutputRows")
    val existsVar = ctx.freshName("exists")

    val matched = ctx.freshName("matched")
    val indexVars = genIndexSideVars(ctx, matched)
    val checkCondition = if (condition.isDefined) {
      val expr = condition.get
      // evaluate the variables from index side that used by condition
      val eval = evaluateRequiredVariables(indexPlan.output,
        indexVars, expr.references)
      // filter the output via condition
      ctx.currentVars = input ++ indexVars
      val ev = BindReferences.bindReference(expr,
        iteratePlan.output ++ indexPlan.output).genCode(ctx)
      s"""
         |$eval
         |${ev.code}
         |$existsVar = !${ev.isNull} && ${ev.value};
       """.stripMargin
    } else {
      s"$existsVar = true;"
    }

    val resultVar = input ++ Seq(ExprCode("", "false", existsVar))
    if (indexScan.keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |InternalRow $matched = $anyNull ? null: $indexTerm.getValue(${keyEv.value});
         |boolean $existsVar = false;
         |if ($matched != null) {
         |  $checkCondition
         |}
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, resultVar)}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[InternalRow]].getName
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from IndexScan
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$indexTerm.get(${keyEv.value});
         |boolean $existsVar = false;
         |if ($matches != null) {
         |  while (!$existsVar && $matches.hasNext()) {
         |    InternalRow $matched = $matches.next();
         |    $checkCondition
         |  }
         |}
         |$numOutput.${metricAdd("1")};
         |${consume(ctx, resultVar)}
       """.stripMargin
    }
  }
}