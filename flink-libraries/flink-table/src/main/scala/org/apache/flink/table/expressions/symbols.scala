/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.expressions

import org.apache.calcite.avatica.util.{TimeUnit, TimeUnitRange}
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.fun.SqlTrimFunction
import org.apache.calcite.tools.RelBuilder
import org.apache.flink.table.api.types.{DataTypes, InternalType}
import org.apache.flink.table.functions.sql.ScalarSqlFunctions
import org.apache.flink.table.plan.logical.LogicalExprVisitor

import scala.language.{existentials, implicitConversions}

/**
  * General expression class to represent a symbol.
  */
case class SymbolExpression(symbol: TableSymbol) extends LeafExpression {

  override private[flink] def resultType: InternalType =
    throw new UnsupportedOperationException("This should not happen. A symbol has no result type.")

  def toExpr: SymbolExpression = this // triggers implicit conversion

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    // dirty hack to pass Java enums to Java from Scala
    val enum = symbol.enum.asInstanceOf[Enum[T] forSome { type T <: Enum[T] }]
    relBuilder.getRexBuilder.makeFlag(enum)
  }

  override def toString: String = s"${symbol.symbols}.${symbol.name}"

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

case class Proctime() extends LeafExpression {

  override private[flink] def resultType = DataTypes.PROCTIME_INDICATOR

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    relBuilder.call(ScalarSqlFunctions.PROCTIME)
  }

  override def accept[T](logicalExprVisitor: LogicalExprVisitor[T]): T =
    logicalExprVisitor.visit(this)
}

/**
  * Symbol that wraps a Calcite symbol in form of a Java enum.
  */
trait TableSymbol {
  def symbols: TableSymbols
  def name: String
  def enum: Enum[_]
}

/**
  * Enumeration of symbols.
  */
abstract class TableSymbols extends Enumeration {

  class TableSymbolValue(e: Enum[_]) extends Val(e.name()) with TableSymbol {
    override def symbols: TableSymbols = TableSymbols.this

    override def enum: Enum[_] = e

    override def name: String = toString()
  }

  protected final def Value(enum: Enum[_]): TableSymbolValue = new TableSymbolValue(enum)

  implicit def symbolToExpression(symbol: TableSymbolValue): SymbolExpression =
    SymbolExpression(symbol)

}

/**
  * Units for working with time intervals.
  */
object TimeIntervalUnit extends TableSymbols {

  type TimeIntervalUnit = TableSymbolValue

  val YEAR = Value(TimeUnitRange.YEAR)
  val YEAR_TO_MONTH = Value(TimeUnitRange.YEAR_TO_MONTH)
  val QUARTER = Value(TimeUnitRange.QUARTER)
  val MONTH = Value(TimeUnitRange.MONTH)
  val WEEK = Value(TimeUnitRange.WEEK)
  val DAY = Value(TimeUnitRange.DAY)
  val DAY_TO_HOUR = Value(TimeUnitRange.DAY_TO_HOUR)
  val DAY_TO_MINUTE = Value(TimeUnitRange.DAY_TO_MINUTE)
  val DAY_TO_SECOND = Value(TimeUnitRange.DAY_TO_SECOND)
  val HOUR = Value(TimeUnitRange.HOUR)
  val HOUR_TO_MINUTE = Value(TimeUnitRange.HOUR_TO_MINUTE)
  val HOUR_TO_SECOND = Value(TimeUnitRange.HOUR_TO_SECOND)
  val MINUTE = Value(TimeUnitRange.MINUTE)
  val MINUTE_TO_SECOND = Value(TimeUnitRange.MINUTE_TO_SECOND)
  val SECOND = Value(TimeUnitRange.SECOND)

}

/**
  * Units for working with time points.
  */
object TimePointUnit extends TableSymbols {

  type TimePointUnit = TableSymbolValue

  val YEAR = Value(TimeUnit.YEAR)
  val MONTH = Value(TimeUnit.MONTH)
  val DAY = Value(TimeUnit.DAY)
  val HOUR = Value(TimeUnit.HOUR)
  val MINUTE = Value(TimeUnit.MINUTE)
  val SECOND = Value(TimeUnit.SECOND)
  val QUARTER = Value(TimeUnit.QUARTER)
  val WEEK = Value(TimeUnit.WEEK)
  val MILLISECOND = Value(TimeUnit.MILLISECOND)
  val MICROSECOND = Value(TimeUnit.MICROSECOND)

}

/**
  * Modes for trimming strings.
  */
object TrimMode extends TableSymbols {

  type TrimMode = TableSymbolValue

  val BOTH = Value(SqlTrimFunction.Flag.BOTH)
  val LEADING = Value(SqlTrimFunction.Flag.LEADING)
  val TRAILING = Value(SqlTrimFunction.Flag.TRAILING)

}
