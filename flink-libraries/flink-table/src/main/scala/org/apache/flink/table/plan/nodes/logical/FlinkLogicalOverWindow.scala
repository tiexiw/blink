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

package org.apache.flink.table.plan.nodes.logical

import org.apache.flink.table.api.ValidationException
import org.apache.flink.table.errorcode.TableErrors
import org.apache.flink.table.plan.metadata.FlinkRelMetadataQuery
import org.apache.flink.table.plan.nodes.FlinkConventions
import org.apache.flink.table.plan.util.OverAggregateUtil

import org.apache.calcite.plan._
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.convert.ConverterRule
import org.apache.calcite.rel.core.Window
import org.apache.calcite.rel.logical.LogicalWindow
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.sql.SqlRankFunction

import java.util.{List => JList}

import scala.collection.JavaConversions._

class FlinkLogicalOverWindow(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    input: RelNode,
    windowConstants: JList[RexLiteral],
    rowType: RelDataType,
    windowGroups: JList[Window.Group])
  extends Window(cluster, traitSet, input, windowConstants, rowType, windowGroups)
  with FlinkLogicalRel {

  override def copy(traitSet: RelTraitSet, inputs: JList[RelNode]): RelNode = {
    new FlinkLogicalOverWindow(
      cluster,
      traitSet,
      inputs.get(0),
      windowConstants,
      getRowType,
      windowGroups)
  }

  override def isDeterministic: Boolean = OverAggregateUtil.isDeterministic(windowGroups)
}

class FlinkLogicalOverWindowConverter
    extends ConverterRule(
      classOf[LogicalWindow],
      Convention.NONE,
      FlinkConventions.LOGICAL,
      "FlinkLogicalOverWindowConverter") {

  override def convert(rel: RelNode): RelNode = {
    val window = rel.asInstanceOf[LogicalWindow]
    val traitSet = FlinkRelMetadataQuery.traitSet(rel).replace(FlinkConventions.LOGICAL).simplify()
    val newInput = RelOptRule.convert(window.getInput, FlinkConventions.LOGICAL)

    window.groups.foreach { group =>
      val orderKeySize = group.orderKeys.getFieldCollations.size()
      group.aggCalls.foreach { winAggCall =>
        if (orderKeySize == 0 && winAggCall.op.isInstanceOf[SqlRankFunction]) {
          throw new ValidationException(TableErrors.INST.sqlOverRankWithoutOrderByInvalid())
        }
      }
    }

    new FlinkLogicalOverWindow(
      rel.getCluster,
      traitSet,
      newInput,
      window.constants,
      window.getRowType,
      window.groups)
  }
}

object FlinkLogicalOverWindow {
  val CONVERTER = new FlinkLogicalOverWindowConverter
}
