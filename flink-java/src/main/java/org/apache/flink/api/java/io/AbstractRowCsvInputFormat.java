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

package org.apache.flink.api.java.io;

import org.apache.flink.api.common.io.ParseException;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.core.fs.Path;
import org.apache.flink.types.parser.FieldParser;

import java.io.IOException;
import java.util.Arrays;

/**
 * Base input format that reads csv into row.
 */
public abstract class AbstractRowCsvInputFormat<T> extends CsvInputFormat<T> implements ResultTypeQueryable<T> {

	protected int arity;
	protected TypeInformation[] fieldTypeInfos;
	private int[] fieldPosMap;
	private boolean emptyColumnAsNull;
	private long limit;
	private long currentConsumeRowCount;

	public AbstractRowCsvInputFormat(
		Path filePath, TypeInformation[] fieldTypeInfos, String lineDelimiter,
		String fieldDelimiter, int[] selectedFields, boolean emptyColumnAsNull,
		long limit) {

		super(filePath);
		this.arity = fieldTypeInfos.length;
		if (arity == 0) {
			throw new IllegalArgumentException("At least one field must be specified");
		}
		if (arity != selectedFields.length) {
			throw new IllegalArgumentException("Number of field types and selected fields must be the same");
		}

		this.fieldTypeInfos = fieldTypeInfos;
		this.fieldPosMap = toFieldPosMap(selectedFields);
		this.emptyColumnAsNull = emptyColumnAsNull;
		this.limit = limit;
		this.currentConsumeRowCount = 0L;

		boolean[] fieldsMask = toFieldMask(selectedFields);

		setDelimiter(lineDelimiter);
		setFieldDelimiter(fieldDelimiter);
		setFieldsGeneric(fieldsMask, extractTypeClasses(fieldTypeInfos));
	}

	@Override
	public T nextRecord(T record) throws IOException {
		T ret = super.nextRecord(record);
		if (ret != null) {
			currentConsumeRowCount++;
		}
		return ret;
	}

	@Override
	public boolean reachedEnd() {
		return super.reachedEnd() || currentConsumeRowCount >= limit;
	}

	private static Class<?>[] extractTypeClasses(TypeInformation[] fieldTypes) {
		Class<?>[] classes = new Class<?>[fieldTypes.length];
		for (int i = 0; i < fieldTypes.length; i++) {
			classes[i] = fieldTypes[i].getTypeClass();
		}
		return classes;
	}

	private static boolean[] toFieldMask(int[] selectedFields) {
		int maxField = 0;
		for (int selectedField : selectedFields) {
			maxField = Math.max(maxField, selectedField);
		}
		boolean[] mask = new boolean[maxField + 1];
		Arrays.fill(mask, false);

		for (int selectedField : selectedFields) {
			mask[selectedField] = true;
		}
		return mask;
	}

	private static int[] toFieldPosMap(int[] selectedFields) {
		int[] fieldIdxs = Arrays.copyOf(selectedFields, selectedFields.length);
		Arrays.sort(fieldIdxs);

		int[] fieldPosMap = new int[selectedFields.length];
		for (int i = 0; i < selectedFields.length; i++) {
			int pos = Arrays.binarySearch(fieldIdxs, selectedFields[i]);
			fieldPosMap[pos] = i;
		}

		return fieldPosMap;
	}

	@Override
	protected boolean parseRecord(Object[] holders, byte[] bytes, int offset, int numBytes) throws ParseException {
		byte[] fieldDelimiter = this.getFieldDelimiter();
		boolean[] fieldIncluded = this.fieldIncluded;

		int startPos = offset;
		int limit = offset + numBytes;

		int field = 0;
		int output = 0;
		while (field < fieldIncluded.length) {

			// check valid start position
			if (startPos > limit || (startPos == limit && field != fieldIncluded.length - 1)) {
				if (isLenient()) {
					return false;
				} else {
					throw new ParseException("Row too short: " + new String(bytes, offset, numBytes, getCharset()));
				}
			}

			if (fieldIncluded[field]) {
				// parse field
				FieldParser<Object> parser = (FieldParser<Object>) this.getFieldParsers()[fieldPosMap[output]];
				int latestValidPos = startPos;

				startPos = parser.resetErrorStateAndParse(
					bytes,
					startPos,
					limit,
					fieldDelimiter,
					holders[fieldPosMap[output]],
					emptyColumnAsNull);

				if (!isLenient() && (parser.getErrorState() != FieldParser.ParseErrorState.NONE)) {
					// the error state EMPTY_COLUMN is ignored
					if (parser.getErrorState() != FieldParser.ParseErrorState.EMPTY_COLUMN) {
						throw new ParseException(String.format("Parsing error for column %1$s of row '%2$s' originated by %3$s: %4$s.",
							field, new String(bytes, offset, numBytes), parser.getClass().getSimpleName(), parser.getErrorState()));
					}
				}
				// check parse result:
				// the result is null if it is invalid
				// or empty with emptyColumnAsNull enabled
				if (startPos < 0 ||
					(emptyColumnAsNull && (parser.getErrorState().equals(FieldParser.ParseErrorState.EMPTY_COLUMN)))) {
					holders[fieldPosMap[output]] = null;
					startPos = skipFields(bytes, latestValidPos, limit, fieldDelimiter);
				} else {
					holders[fieldPosMap[output]] = parser.getLastResult();
				}
				output++;
			} else {
				// skip field
				startPos = skipFields(bytes, startPos, limit, fieldDelimiter);
			}

			// check if something went wrong
			if (startPos < 0) {
				throw new ParseException(String.format("Unexpected parser position for column %1$s of row '%2$s'",
					field, new String(bytes, offset, numBytes)));
			}
			else if (startPos == limit
				&& field != fieldIncluded.length - 1
				&& !FieldParser.endsWithDelimiter(bytes, startPos - 1, fieldDelimiter)) {
				// We are at the end of the record, but not all fields have been read
				// and the end is not a field delimiter indicating an empty last field.
				if (isLenient()) {
					return false;
				} else {
					throw new ParseException("Row too short: " + new String(bytes, offset, numBytes));
				}
			}

			field++;
		}
		return true;
	}
}
