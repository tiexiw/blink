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

package org.apache.flink.table.functions.sql

import java.util

import com.google.common.collect.ImmutableList
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.sql._
import org.apache.calcite.sql.`type`._

/**
  * INCR_SUM is an aggregator which returns the sum of the values which
  * go into it like SUM. It differs in that the modified monotonicity of
  * INCR_SUM is INCREASING, while that of SUM should be inferred using
  * extra information.
 */
class SqlIncrSumAggFunction
  extends SqlAggFunction(
    "INCR_SUM",
    null,
    SqlKind.SUM,
    ReturnTypes.AGG_SUM,
    null,
    OperandTypes.NUMERIC,
    SqlFunctionCategory.NUMERIC,
    false,
    false) {

  override def getParameterTypes(typeFactory: RelDataTypeFactory): util.List[RelDataType] =
    ImmutableList.of(
      typeFactory.createTypeWithNullability(
        typeFactory.createSqlType(SqlTypeName.ANY),true))

  override def getReturnType(typeFactory: RelDataTypeFactory): RelDataType =
    typeFactory.createTypeWithNullability(
      typeFactory.createSqlType(SqlTypeName.ANY), true)

  override def unwrap[T](clazz: Class[T]): T = if (clazz eq classOf[SqlSplittableAggFunction]) {
    clazz.cast(SqlSplittableAggFunction.CountSplitter.INSTANCE)
  } else {
    super.unwrap(clazz)
  }
}
