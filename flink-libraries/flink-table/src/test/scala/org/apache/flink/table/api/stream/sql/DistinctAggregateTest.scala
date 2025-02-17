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
package org.apache.flink.table.api.stream.sql

import org.apache.flink.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.util.{StreamTableTestUtil, TableTestBase}
import org.junit.{Ignore, Test}

class DistinctAggregateTest extends TableTestBase {

  private val streamUtil: StreamTableTestUtil = streamTestUtil()
  streamUtil.addTable[(Int, String, Long)]("MyTable", 'a, 'b, 'c, 'rowtime.rowtime)

  @Test
  def testDistinct(): Unit = {
    val sql = "SELECT DISTINCT a, b, c FROM MyTable"
    streamUtil.verifyPlan(sql)
  }

  // TODO: this query should be optimized to only have a single StreamExecGroupAggregate
  // TODO: reopen this until FLINK-7144 fixed
  @Ignore
  @Test
  def testDistinctAfterAggregate(): Unit = {
    val sql = "SELECT DISTINCT a FROM MyTable GROUP BY a, b, c"
    streamUtil.verifyPlan(sql)
  }

  @Test
  def testDistinctAggregate(): Unit = {
    val sqlQuery = "SELECT " +
      "  c, SUM(DISTINCT a), SUM(a), COUNT(DISTINCT b) " +
      "FROM MyTable " +
      "GROUP BY c "

    streamUtil.verifyPlan(sqlQuery)
  }

  @Test
  def testDistinctAggregateOnTumbleWindow(): Unit = {
    val sqlQuery = "SELECT COUNT(DISTINCT a), " +
      "  SUM(a) " +
      "FROM MyTable " +
      "GROUP BY TUMBLE(rowtime, INTERVAL '15' MINUTE) "

    streamUtil.verifyPlan(sqlQuery)
  }

  @Test
  def testMultiDistinctAggregateSameFieldOnHopWindow(): Unit = {
    val sqlQuery = "SELECT COUNT(DISTINCT a), " +
      "  SUM(DISTINCT a), " +
      "  MAX(DISTINCT a) " +
      "FROM MyTable " +
      "GROUP BY HOP(rowtime, INTERVAL '15' MINUTE, INTERVAL '1' HOUR) "

    streamUtil.verifyPlan(sqlQuery)
  }

  @Test
  def testDistinctAggregateWithGroupingOnSessionWindow(): Unit = {
    val sqlQuery = "SELECT a, " +
      "  COUNT(a), " +
      "  SUM(DISTINCT c) " +
      "FROM MyTable " +
      "GROUP BY a, SESSION(rowtime, INTERVAL '15' MINUTE) "

    streamUtil.verifyPlan(sqlQuery)
  }

}
