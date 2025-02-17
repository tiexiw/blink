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

import java.lang.{Boolean => JBool}

import org.apache.flink.streaming.api.datastream.{DataStream, DataStreamSink}
import org.apache.flink.table.api.Table

/**
  * Defines an external [[TableSink]] to emit a streaming [[Table]] with insert, update, and delete
  * changes. The [[Table]] must be have unique key fields (atomic or composite) or be append-only.
  *
  * If the [[Table]] does not have a unique key and is not append-only, a
  * [[org.apache.flink.table.api.TableException]] will be thrown.
  *
  * The unique key of the table is configured by the [[BaseUpsertStreamTableSink#setKeyFields()]]
  * method.
  *
  * If the table is append-only, all messages will have a true flag and must be interpreted
  * as insertions.
  *
  * @tparam T Type of records that this [[TableSink]] expects and supports.
  */
trait BaseUpsertStreamTableSink[T] extends StreamTableSink[T] {

  /**
    * Configures the unique key fields of the [[Table]] to write.
    * The method is called after [[TableSink.configure()]].
    *
    * The keys array might be empty, if the table consists of a single (updated) record.
    * If the table does not have a key and is append-only, the keys attribute is null.
    *
    * @param keys the field names of the table's keys, an empty array if the table has a single
    *             row, and null if the table is append-only and has no key.
    */
  def setKeyFields(keys: Array[String]): Unit

  /**
    * Specifies whether the [[Table]] to write is append-only or not.
    *
    * @param isAppendOnly true if the table is append-only, false otherwise.
    */
  def setIsAppendOnly(isAppendOnly: JBool): Unit

  /** Emits the DataStream. */
  def emitDataStream(dataStream: DataStream[T]): DataStreamSink[_]
}
