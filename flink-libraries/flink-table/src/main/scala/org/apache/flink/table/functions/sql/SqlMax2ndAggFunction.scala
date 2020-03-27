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
import org.apache.calcite.sql.SqlSplittableAggFunction.SelfSplitter
import org.apache.calcite.sql._
import org.apache.calcite.sql.`type`._

class SqlMax2ndAggFunction extends SqlAggFunction(
  "MAX2ND",
  null.asInstanceOf[SqlIdentifier],
  SqlKind.OTHER_FUNCTION,
  ReturnTypes.ARG0_NULLABLE_IF_EMPTY,
  null.asInstanceOf[SqlOperandTypeInference],
  OperandTypes.COMPARABLE_ORDERED,
  SqlFunctionCategory.SYSTEM,
  false,
  false) {
  var argTypes: util.List[RelDataType] = ImmutableList.of();

  override def getParameterTypes(
      typeFactory: RelDataTypeFactory): util.List[RelDataType] = this.argTypes

  override def getReturnType(typeFactory: RelDataTypeFactory): RelDataType =
    this.argTypes.get(0).asInstanceOf[RelDataType]

  override def unwrap[T](clazz: Class[T]): T = if (clazz eq classOf[SqlSplittableAggFunction]) {
    clazz.cast(SelfSplitter.INSTANCE)
  } else {
    super.unwrap(
      clazz)
  }

}
