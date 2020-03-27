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

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.IOException;

/**
 * Decompress data from Gzip format.
 *
 * Note that this class is only a wrapper of {@link GzipCompressorInputStream}.
 * For efficiency, one should rewrite this class.
 */
public class GzipBlockDecompressor extends AbstractBlockDecompressor {
	private ReusableByteArrayInputStream srcStream;

	@Override
	public int decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) throws InsufficientBufferException, DataCorruptionException{
		try {
			if (srcStream == null) {
				srcStream = new ReusableByteArrayInputStream(src, srcOff, srcLen);
			} else {
				srcStream.reuse(src, srcOff, srcLen);
			}
			GzipCompressorInputStream decompressStream = new GzipCompressorInputStream(srcStream);

			int decompressedLen = decompressStream.read(dst, dstOff, dst.length - dstOff);
			srcStream.close();
			decompressStream.close();

			if (decompressedLen < 0) {
				throw new DataCorruptionException("Fail to decompress, decompressedLen: " + decompressedLen);
			}

			return decompressedLen;
		} catch (IOException e) {
			throw new DataCorruptionException(e);
		}
	}
}
