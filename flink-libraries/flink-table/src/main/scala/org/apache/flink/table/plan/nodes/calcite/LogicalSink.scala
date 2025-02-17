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

package org.apache.flink.table.plan.nodes.calcite

import java.util

import org.apache.flink.table.sinks.TableSink

import org.apache.calcite.plan.{Convention, RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.RelNode

import scala.collection.JavaConversions._

final class LogicalSink(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    input: RelNode,
    sink: TableSink[_],
    sinkName: String)
  extends Sink(cluster, traitSet, input, sink, sinkName) {

  override def copy(traitSet: RelTraitSet, inputs: util.List[RelNode]): RelNode = {
    new LogicalSink(cluster, traitSet, inputs.head, sink, sinkName)
  }

}

object LogicalSink {

  def create(input: RelNode,
      sink: TableSink[_],
      sinkName: String): LogicalSink = {
    val traits = input.getCluster.traitSetOf(Convention.NONE)
    new LogicalSink(input.getCluster, traits, input, sink, sinkName)
  }
}
