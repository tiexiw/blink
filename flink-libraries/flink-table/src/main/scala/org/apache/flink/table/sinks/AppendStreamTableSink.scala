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

package org.apache.flink.table.sinks

import org.apache.flink.streaming.api.datastream.{DataStream, DataStreamSink}
import org.apache.flink.table.api.Table

/**
  * Defines an external [[TableSink]] to emit streaming [[Table]] with only insert changes.
  *
  * If the [[Table]] is also modified by update or delete changes, a
  * [[org.apache.flink.table.api.TableException]] will be thrown.
  *
  * @tparam T Type of [[DataStream]] that this [[TableSink]] expects and supports.
  */
trait AppendStreamTableSink[T] extends StreamTableSink[T] {

  /** Emits the DataStream. */
  def emitDataStream(dataStream: DataStream[T]): DataStreamSink[_]
}
