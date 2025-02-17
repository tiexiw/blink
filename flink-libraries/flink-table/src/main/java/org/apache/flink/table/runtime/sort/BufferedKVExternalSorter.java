/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.	See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.	You may obtain a copy of the License at
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.sort;

import org.apache.flink.api.common.io.blockcompression.BlockCompressionFactory;
import org.apache.flink.api.common.io.blockcompression.BlockCompressionFactoryLoader;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.disk.iomanager.FileIOChannel;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.operators.sort.IndexedSorter;
import org.apache.flink.runtime.operators.sort.QuickSort;
import org.apache.flink.table.api.TableConfigOptions;
import org.apache.flink.table.dataformat.BinaryRow;
import org.apache.flink.table.runtime.util.AbstractChannelWriterOutputView;
import org.apache.flink.table.runtime.util.ChannelWithMeta;
import org.apache.flink.table.runtime.util.FileChannelUtil;
import org.apache.flink.table.runtime.util.MemorySegmentPool;
import org.apache.flink.table.typeutils.BinaryRowSerializer;
import org.apache.flink.util.MutableObjectIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sorter for buffered input in the form of Key-Value Style.
 * First, sort and spill buffered inputs.
 * Second, merge disk outputs and return iterator.
 */
public class BufferedKVExternalSorter {

	private static final Logger LOG = LoggerFactory.getLogger(BufferedKVExternalSorter.class);

	private volatile boolean closed = false;

	private final NormalizedKeyComputer nKeyComputer;
	private final RecordComparator comparator;
	private final BinaryRowSerializer keySerializer;
	private final BinaryRowSerializer valueSerializer;
	private final IndexedSorter sorter;

	private final BinaryKVExternalMerger merger;

	private final IOManager ioManager;
	private final int maxNumFileHandles;
	private final FileIOChannel.Enumerator enumerator;
	private final List<ChannelWithMeta> channelIDs = new ArrayList<>();
	private final SpillChannelManager channelManager;

	private int pageSize;

	//metric
	private long numSpillFiles;
	private long spillInBytes;
	private long spillInCompressedBytes;

	private final boolean compressionEnable;
	private final BlockCompressionFactory compressionCodecFactory;
	private final int compressionBlockSize;

	public BufferedKVExternalSorter(
			IOManager ioManager,
			BinaryRowSerializer keySerializer,
			BinaryRowSerializer valueSerializer,
			NormalizedKeyComputer nKeyComputer,
			RecordComparator comparator,
			int pageSize,
			Configuration conf) throws IOException {
		this.keySerializer = keySerializer;
		this.valueSerializer = valueSerializer;
		this.nKeyComputer = nKeyComputer;
		this.comparator = comparator;
		this.pageSize = pageSize;
		this.sorter = new QuickSort();
		this.maxNumFileHandles = conf.getInteger(TableConfigOptions.SQL_EXEC_SORT_FILE_HANDLES_MAX_NUM);
		this.compressionEnable = conf.getBoolean(TableConfigOptions.SQL_EXEC_SPILL_COMPRESSION_ENABLED);
		this.compressionCodecFactory = this.compressionEnable
			? BlockCompressionFactoryLoader.createBlockCompressionFactory(conf.getString(
				TableConfigOptions.SQL_EXEC_SPILL_COMPRESSION_CODEC), conf)
			: null;
		this.compressionBlockSize = conf.getInteger(TableConfigOptions.SQL_EXEC_SPILL_COMPRESSION_BLOCK_SIZE);
		this.ioManager = ioManager;
		this.enumerator = this.ioManager.createChannelEnumerator();
		this.channelManager = new SpillChannelManager();
		this.merger = new BinaryKVExternalMerger(
				ioManager, pageSize,
				maxNumFileHandles, channelManager,
				keySerializer, valueSerializer, comparator,
				compressionEnable,
			compressionCodecFactory,
				compressionBlockSize);
	}

	public MutableObjectIterator<Tuple2<BinaryRow, BinaryRow>> getKVIterator() throws IOException {
		// 1. merge if more than maxNumFile
		// merge channels until sufficient file handles are available
		List<ChannelWithMeta> channelIDs = this.channelIDs;
		while (!closed && channelIDs.size() > this.maxNumFileHandles) {
			channelIDs = merger.mergeChannelList(channelIDs);
		}

		// 2. final merge
		List<FileIOChannel> openChannels = new ArrayList<>();
		BinaryMergeIterator<Tuple2<BinaryRow, BinaryRow>> iterator =
				merger.getMergingIterator(channelIDs, openChannels);
		channelManager.addOpenChannels(openChannels);

		return iterator;
	}

	public void sortAndSpill(
			ArrayList<MemorySegment> recordBufferSegments,
			long numElements,
			MemorySegmentPool pool) throws IOException {

		// 1. sort buffer
		BinaryKVInMemorySortBuffer buffer =
				BinaryKVInMemorySortBuffer.createBuffer(
						nKeyComputer, keySerializer, valueSerializer, comparator,
						recordBufferSegments, numElements, pool);
		this.sorter.sort(buffer);

		// 2. spill
		FileIOChannel.ID channel = enumerator.next();
		channelManager.addChannel(channel);

		AbstractChannelWriterOutputView output = null;
		int bytesInLastBuffer;
		int blockCount;
		try {
			numSpillFiles++;
			output = FileChannelUtil.createOutputView(ioManager, channel, compressionEnable,
					compressionCodecFactory, compressionBlockSize, pageSize);
			buffer.writeToOutput(output);
			spillInBytes += output.getNumBytes();
			spillInCompressedBytes += output.getNumCompressedBytes();
			bytesInLastBuffer = output.close();
			blockCount = output.getBlockCount();
			LOG.info("here spill the {}th kv external buffer data with {} bytes and {} compressed bytes",
					numSpillFiles, spillInBytes, spillInCompressedBytes);
		} catch (IOException e) {
			if (output != null) {
				output.closeAndDelete();
			}
			throw e;
		}
		channelIDs.add(new ChannelWithMeta(channel, blockCount, bytesInLastBuffer));
	}

	public void close() {
		if (closed) {
			return;
		}
		// mark as closed
		closed = true;
		merger.close();
		channelManager.close();
	}
}
