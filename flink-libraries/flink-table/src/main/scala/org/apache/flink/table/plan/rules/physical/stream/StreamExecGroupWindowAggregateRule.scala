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

package org.apache.flink.table.plan.rules.physical.stream

import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.convert.ConverterRule
import org.apache.flink.table.api.{TableConfig, TableException}
import org.apache.flink.table.expressions.ExpressionUtils.isRowtimeAttribute
import org.apache.flink.table.plan.`trait`.FlinkRelDistribution
import org.apache.flink.table.plan.nodes.FlinkConventions
import org.apache.flink.table.plan.nodes.physical.stream.StreamExecGroupWindowAggregate
import org.apache.flink.table.plan.nodes.logical.FlinkLogicalWindowAggregate
import org.apache.flink.table.plan.schema.BaseRowSchema
import org.apache.flink.table.plan.util.AggregateUtil.timeFieldIndex
import org.apache.flink.table.plan.util.EmitStrategy

import scala.collection.JavaConversions._

class StreamExecGroupWindowAggregateRule
  extends ConverterRule(
    classOf[FlinkLogicalWindowAggregate],
    FlinkConventions.LOGICAL,
    FlinkConventions.STREAM_PHYSICAL,
    "StreamExecGroupWindowAggregateRule") {

  override def matches(call: RelOptRuleCall): Boolean = {
    val agg: FlinkLogicalWindowAggregate = call.rel(0).asInstanceOf[FlinkLogicalWindowAggregate]

    // check if we have grouping sets
    val groupSets = agg.getGroupSets.size() != 1 || agg.getGroupSets.get(0) != agg.getGroupSet
    if (groupSets || agg.indicator) {
      throw new TableException("GROUPING SETS are currently not supported.")
    }

    !groupSets && !agg.indicator
  }

  override def convert(rel: RelNode): RelNode = {
    val agg: FlinkLogicalWindowAggregate = rel.asInstanceOf[FlinkLogicalWindowAggregate]
    val requiredDistribution = if (agg.getGroupCount != 0) {
      FlinkRelDistribution.hash(agg.getGroupSet.asList)
    } else {
      FlinkRelDistribution.SINGLETON
    }
    val requiredTraitSet = agg.getInput.getTraitSet.replace(
      FlinkConventions.STREAM_PHYSICAL).replace(requiredDistribution)
    val providedTraitSet = rel.getTraitSet.replace(FlinkConventions.STREAM_PHYSICAL)
    val convInput: RelNode = RelOptRule.convert(agg.getInput, requiredTraitSet)

    val config = rel.getCluster.getPlanner.getContext.unwrap(classOf[TableConfig])
    val emitStrategy = EmitStrategy(config, agg.getWindow)

    val inputTimestampIndex = if (isRowtimeAttribute(agg.getWindow.timeAttribute)) {
      timeFieldIndex(
        agg.getInput.getRowType,
        relBuilderFactory.create(rel.getCluster, null),
        agg.getWindow.timeAttribute)
    } else {
      -1
    }

    new StreamExecGroupWindowAggregate(
      agg.getWindow,
      agg.getNamedProperties,
      rel.getCluster,
      providedTraitSet,
      convInput,
      agg.getAggCallList,
      new BaseRowSchema(rel.getRowType),
      new BaseRowSchema(agg.getInput.getRowType),
      agg.getGroupSet.toArray,
      inputTimestampIndex,
      emitStrategy)
    }
  }

object StreamExecGroupWindowAggregateRule {
  val INSTANCE: RelOptRule = new StreamExecGroupWindowAggregateRule
}
