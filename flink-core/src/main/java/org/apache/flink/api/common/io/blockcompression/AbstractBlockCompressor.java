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

package org.apache.flink.api.common.io.blockcompression;

import java.nio.ByteBuffer;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * A compressor which compresses a whole byte array each time.
 * It will read from and write to byte arrays given from the outside, reducing copy time.
 */
public abstract class AbstractBlockCompressor {
	private byte[] reuseSrcHeapBuff;
	private byte[] reuseDstHeapBuff;

	public abstract int getMaxCompressedSize(int srcSize);

	public int getMaxCompressedSize(byte[] src) {
		return getMaxCompressedSize(src.length);
	}

	/**
	 * Compresses data from source byte buffer and writes result to destination byte buffer.
	 * Source data starts from {@link ByteBuffer#position()}, the length of source data is {@link ByteBuffer#remaining()}.
	 * Destination data starts from {@link ByteBuffer#position()}.
	 * @throws InsufficientBufferException destination buffer is not enough, user may allocate larger memory and retry.
	 */
	public int compress(ByteBuffer src, ByteBuffer dst) throws InsufficientBufferException {
		return compress(src, 0, src.remaining(), dst, 0);
	}

	/**
	 * Compresses data from source byte buffer and writes result to destination byte buffer.
	 * Source data starts from ({@link ByteBuffer#position()} + {@code srcOff}), the length of source data is {@code srcLen}.
	 * Destination data starts from {@link ByteBuffer#position() + {@code dstOff}}.
	 */
	public int compress(ByteBuffer src, int srcOff, int srcLen, ByteBuffer dst, int dstOff) throws InsufficientBufferException {
		checkArgument(srcOff >= 0 && dstOff >= 0);

		// Source data starts from (src.position() + srcOff)
		if (srcOff > 0) {
			src.position(src.position() + srcOff);
		}

		// Destination data starts from (dst.position() + dstOff)
		if (dstOff > 0) {
			dst.position(dst.position() + dstOff);
		}

		byte[] srcArr;
		int srcArrOff;
		if (src.hasArray()) {
			srcArr = src.array();
			srcArrOff = src.arrayOffset() + src.position();
			src.position(src.position() + srcLen);
		} else {
			if (reuseSrcHeapBuff == null || reuseSrcHeapBuff.length < srcLen) {
				reuseSrcHeapBuff = new byte[srcLen];
			}
			srcArr = reuseSrcHeapBuff;
			srcArrOff = 0;
			src.get(srcArr, 0, srcLen);
		}

		byte[] dstArr;
		int dstArrOff;
		if (dst.hasArray()) {
			dstArr = dst.array();
			dstArrOff = dst.arrayOffset() + dst.position();
		} else {
			int len = dst.capacity() - dst.position();
			if (reuseDstHeapBuff == null || reuseDstHeapBuff.length < len) {
				reuseDstHeapBuff = new byte[len];
			}
			dstArr = reuseDstHeapBuff;
			dstArrOff = 0;
		}

		int compressedLen = compress(srcArr, srcArrOff, srcLen, dstArr, dstArrOff);

		if (dst.hasArray()) {
			dst.position(dst.position() + compressedLen);
		} else {
			dst.put(dstArr, dstArrOff, compressedLen);
		}

		return compressedLen;
	}

	public int compress(byte[] src, byte[] dst) throws InsufficientBufferException {
		return compress(src, 0, src.length, dst, 0);
	}

	public abstract int compress(
			byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) throws InsufficientBufferException;
}
