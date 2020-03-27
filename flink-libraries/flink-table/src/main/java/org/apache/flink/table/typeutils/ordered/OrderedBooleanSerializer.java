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

package org.apache.flink.table.typeutils.ordered;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.base.TypeSerializerSingleton;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.table.typeutils.ordered.OrderedBytes.Order;

import java.io.IOException;

/**
 * A serializer for boolean. The serialized value maintains the sort order of the original value.
 */
@Internal
public final class OrderedBooleanSerializer extends TypeSerializerSingleton<Boolean> {

	private static final long serialVersionUID = 1L;

	public static final OrderedBooleanSerializer
		ASC_INSTANCE = new OrderedBooleanSerializer(Order.ASCENDING);

	public static final OrderedBooleanSerializer
		DESC_INSTANCE = new OrderedBooleanSerializer(Order.DESCENDING);

	private static final Boolean FALSE = Boolean.FALSE;

	private static final OrderedBytes orderedBytes = new OrderedBytes();

	private final Order ord;

	private OrderedBooleanSerializer(Order ord) {
		this.ord = ord;
	}

	@Override
	public boolean isImmutableType() {
		return true;
	}

	@Override
	public Boolean createInstance() {
		return FALSE;
	}

	@Override
	public Boolean copy(Boolean from) {
		return from;
	}

	@Override
	public Boolean copy(Boolean from, Boolean reuse) {
		return from;
	}

	@Override
	public int getLength() {
		return 4;
	}

	@Override
	public void serialize(Boolean record, DataOutputView target) throws IOException {
		orderedBytes.encodeByte(target, (byte) (record ? 1 : 0), ord);
	}

	@Override
	public Boolean deserialize(DataInputView source) throws IOException {
		int v = orderedBytes.decodeByte(source, ord);
		return v != 0;
	}

	@Override
	public Boolean deserialize(Boolean reuse, DataInputView source) throws IOException {
		return deserialize(source);
	}

	@Override
	public void copy(DataInputView source, DataOutputView target) throws IOException {
		orderedBytes.encodeByte(target, orderedBytes.decodeByte(source, ord), ord);
	}

	@Override
	public boolean canEqual(Object obj) {
		return obj instanceof OrderedBooleanSerializer;
	}
}
