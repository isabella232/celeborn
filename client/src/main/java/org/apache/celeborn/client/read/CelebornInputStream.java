/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.client.read;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import scala.Tuple2;

import com.google.common.util.concurrent.Uninterruptibles;
import io.netty.buffer.ByteBuf;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.celeborn.client.ShuffleClient;
import org.apache.celeborn.client.compress.Decompressor;
import org.apache.celeborn.common.CelebornConf;
import org.apache.celeborn.common.exception.CelebornIOException;
import org.apache.celeborn.common.network.client.TransportClientFactory;
import org.apache.celeborn.common.protocol.*;
import org.apache.celeborn.common.unsafe.Platform;
import org.apache.celeborn.common.util.ExceptionMaker;
import org.apache.celeborn.common.util.Utils;

public abstract class CelebornInputStream extends InputStream {
  private static final Logger logger = LoggerFactory.getLogger(CelebornInputStream.class);

  public static CelebornInputStream create(
      CelebornConf conf,
      TransportClientFactory clientFactory,
      String shuffleKey,
      ArrayList<PartitionLocation> locations,
      ArrayList<PbStreamHandler> streamHandlers,
      int[] attempts,
      int attemptNumber,
      int startMapIndex,
      int endMapIndex,
      ConcurrentHashMap<String, Long> fetchExcludedWorkers,
      ShuffleClient shuffleClient,
      int appShuffleId,
      int shuffleId,
      int partitionId,
      ExceptionMaker exceptionMaker,
      MetricsCallback metricsCallback)
      throws IOException {
    if (locations == null || locations.size() == 0) {
      return emptyInputStream;
    } else {
      return new CelebornInputStreamImpl(
          conf,
          clientFactory,
          shuffleKey,
          locations,
          streamHandlers,
          attempts,
          attemptNumber,
          startMapIndex,
          endMapIndex,
          fetchExcludedWorkers,
          shuffleClient,
          appShuffleId,
          shuffleId,
          partitionId,
          exceptionMaker,
          metricsCallback);
    }
  }

  public static CelebornInputStream empty() {
    return emptyInputStream;
  }

  private static final CelebornInputStream emptyInputStream =
      new CelebornInputStream() {
        @Override
        public int read() throws IOException {
          return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          return -1;
        }

        @Override
        public int totalPartitionsToRead() {
          return 0;
        }

        @Override
        public int partitionsRead() {
          return 0;
        }
      };

  public abstract int totalPartitionsToRead();

  public abstract int partitionsRead();

  private static final class CelebornInputStreamImpl extends CelebornInputStream {
    private static final Random RAND = new Random();

    private final CelebornConf conf;
    private final TransportClientFactory clientFactory;
    private final String shuffleKey;
    private ArrayList<PartitionLocation> locations;
    private ArrayList<PbStreamHandler> streamHandlers;
    private int[] attempts;
    private final int attemptNumber;
    private final int startMapIndex;
    private final int endMapIndex;

    private Map<Integer, Set<Integer>> batchesRead = new HashMap<>();

    private byte[] compressedBuf;
    private byte[] rawDataBuf;
    private Decompressor decompressor;

    private ByteBuf currentChunk;
    private boolean firstChunk = true;
    private PartitionReader currentReader;
    private final int fetchChunkMaxRetry;
    private int fetchChunkRetryCnt = 0;
    int retryWaitMs;
    private int fileIndex;
    private int position;
    private int limit;

    private MetricsCallback callback;

    // mapId, attemptId, batchId, size
    private final int BATCH_HEADER_SIZE = 4 * 4;
    private final byte[] sizeBuf = new byte[BATCH_HEADER_SIZE];
    private LongAdder skipCount = new LongAdder();
    private final boolean rangeReadFilter;
    private final boolean enabledReadLocalShuffle;
    private final String localHostAddress;

    private boolean pushReplicateEnabled;
    private boolean fetchExcludeWorkerOnFailureEnabled;
    private boolean shuffleCompressionEnabled;
    private long fetchExcludedWorkerExpireTimeout;
    private ConcurrentHashMap<String, Long> fetchExcludedWorkers;

    private boolean containLocalRead = false;
    private ShuffleClient shuffleClient;
    private int appShuffleId;
    private int shuffleId;
    private int partitionId;
    private ExceptionMaker exceptionMaker;
    private boolean closed = false;

    CelebornInputStreamImpl(
        CelebornConf conf,
        TransportClientFactory clientFactory,
        String shuffleKey,
        ArrayList<PartitionLocation> locations,
        ArrayList<PbStreamHandler> streamHandlers,
        int[] attempts,
        int attemptNumber,
        int startMapIndex,
        int endMapIndex,
        ConcurrentHashMap<String, Long> fetchExcludedWorkers,
        ShuffleClient shuffleClient,
        int appShuffleId,
        int shuffleId,
        int partitionId,
        ExceptionMaker exceptionMaker,
        MetricsCallback metricsCallback)
        throws IOException {
      this.conf = conf;
      this.clientFactory = clientFactory;
      this.shuffleKey = shuffleKey;
      this.locations = locations;
      if (streamHandlers != null && streamHandlers.size() == locations.size()) {
        this.streamHandlers = streamHandlers;
      }
      this.attempts = attempts;
      this.attemptNumber = attemptNumber;
      this.startMapIndex = startMapIndex;
      this.endMapIndex = endMapIndex;
      this.rangeReadFilter = conf.shuffleRangeReadFilterEnabled();
      this.enabledReadLocalShuffle = conf.enableReadLocalShuffleFile();
      this.localHostAddress = Utils.localHostName(conf);
      this.pushReplicateEnabled = conf.clientPushReplicateEnabled();
      this.fetchExcludeWorkerOnFailureEnabled = conf.clientFetchExcludeWorkerOnFailureEnabled();
      this.shuffleCompressionEnabled =
          !conf.shuffleCompressionCodec().equals(CompressionCodec.NONE);
      this.fetchExcludedWorkerExpireTimeout = conf.clientFetchExcludedWorkerExpireTimeout();
      this.fetchExcludedWorkers = fetchExcludedWorkers;

      if (conf.clientPushReplicateEnabled()) {
        fetchChunkMaxRetry = conf.clientFetchMaxRetriesForEachReplica() * 2;
      } else {
        fetchChunkMaxRetry = conf.clientFetchMaxRetriesForEachReplica();
      }
      this.retryWaitMs = conf.networkIoRetryWaitMs(TransportModuleConstants.DATA_MODULE);
      this.callback = metricsCallback;
      this.exceptionMaker = exceptionMaker;
      this.partitionId = partitionId;
      this.appShuffleId = appShuffleId;
      this.shuffleId = shuffleId;
      this.shuffleClient = shuffleClient;

      boolean chunkPrefetchEnabled = conf.clientChunkPrefetchEnabled();
      moveToNextReader(chunkPrefetchEnabled);
      if (chunkPrefetchEnabled) {
        init();
        firstChunk = false;
      }
    }

    private boolean skipLocation(int startMapIndex, int endMapIndex, PartitionLocation location) {
      if (!rangeReadFilter) {
        return false;
      }
      if (endMapIndex == Integer.MAX_VALUE) {
        return false;
      }
      RoaringBitmap bitmap = location.getMapIdBitMap();
      if (bitmap == null && location.hasPeer()) {
        bitmap = location.getPeer().getMapIdBitMap();
      }
      for (int i = startMapIndex; i < endMapIndex; i++) {
        if (bitmap.contains(i)) {
          return false;
        }
      }
      return true;
    }

    private Tuple2<PartitionLocation, PbStreamHandler> nextReadableLocation() {
      int locationCount = locations.size();
      if (fileIndex >= locationCount) {
        return null;
      }
      PartitionLocation currentLocation = locations.get(fileIndex);
      while (skipLocation(startMapIndex, endMapIndex, currentLocation)) {
        skipCount.increment();
        fileIndex++;
        if (fileIndex == locationCount) {
          return null;
        }
        currentLocation = locations.get(fileIndex);
      }

      fetchChunkRetryCnt = 0;

      return new Tuple2(
          currentLocation, streamHandlers == null ? null : streamHandlers.get(fileIndex));
    }

    private void moveToNextReader(boolean fetchChunk) throws IOException {
      if (currentReader != null) {
        currentReader.close();
        currentReader = null;
      }
      Tuple2<PartitionLocation, PbStreamHandler> currentLocation = nextReadableLocation();
      if (currentLocation == null) {
        return;
      }
      currentReader = createReaderWithRetry(currentLocation._1, currentLocation._2);
      fileIndex++;
      while (!currentReader.hasNext()) {
        currentReader.close();
        currentReader = null;
        currentLocation = nextReadableLocation();
        if (currentLocation == null) {
          return;
        }
        currentReader = createReaderWithRetry(currentLocation._1, currentLocation._2);
        fileIndex++;
      }
      if (fetchChunk) {
        currentChunk = getNextChunk();
      }
    }

    private void excludeFailedLocation(PartitionLocation location, Exception e) {
      if (pushReplicateEnabled && fetchExcludeWorkerOnFailureEnabled && isCriticalCause(e)) {
        fetchExcludedWorkers.put(location.hostAndFetchPort(), System.currentTimeMillis());
      }
    }

    private boolean isExcluded(PartitionLocation location) {
      Long timestamp = fetchExcludedWorkers.get(location.hostAndFetchPort());
      if (timestamp == null) {
        return false;
      } else if (System.currentTimeMillis() - timestamp > fetchExcludedWorkerExpireTimeout) {
        fetchExcludedWorkers.remove(location.hostAndFetchPort());
        return false;
      } else if (location.getPeer() != null) {
        Long peerTimestamp = fetchExcludedWorkers.get(location.getPeer().hostAndFetchPort());
        // To avoid both replicate locations is excluded, if peer add to excluded list earlier,
        // change to try peer.
        if (peerTimestamp == null || peerTimestamp < timestamp) {
          return true;
        } else {
          return false;
        }
      } else {
        return true;
      }
    }

    private boolean isCriticalCause(Exception e) {
      boolean rpcTimeout =
          e instanceof IOException
              && e.getCause() != null
              && e.getCause() instanceof TimeoutException;
      boolean connectException =
          e instanceof CelebornIOException
              && e.getMessage() != null
              && (e.getMessage().startsWith("Connecting to")
                  || e.getMessage().startsWith("Failed to"));
      boolean fetchChunkTimeout =
          e instanceof CelebornIOException
              && e.getCause() != null
              && e.getCause() instanceof IOException;
      return connectException || rpcTimeout || fetchChunkTimeout;
    }

    private PartitionReader createReaderWithRetry(
        PartitionLocation location, PbStreamHandler pbStreamHandler) throws IOException {
      Exception lastException = null;
      while (fetchChunkRetryCnt < fetchChunkMaxRetry) {
        try {
          if (isExcluded(location)) {
            throw new CelebornIOException("Fetch data from excluded worker! " + location);
          }
          return createReader(location, pbStreamHandler, fetchChunkRetryCnt, fetchChunkMaxRetry);
        } catch (Exception e) {
          lastException = e;
          excludeFailedLocation(location, e);
          fetchChunkRetryCnt++;
          if (location.hasPeer()) {
            // fetchChunkRetryCnt % 2 == 0 means both replicas have been tried,
            // so sleep before next try.
            if (fetchChunkRetryCnt % 2 == 0) {
              Uninterruptibles.sleepUninterruptibly(retryWaitMs, TimeUnit.MILLISECONDS);
            }
            logger.warn(
                "CreatePartitionReader failed {}/{} times for location {}, change to peer",
                fetchChunkRetryCnt,
                fetchChunkMaxRetry,
                location,
                e);
            location = location.getPeer();
          } else {
            logger.warn(
                "CreatePartitionReader failed {}/{} times for location {}, retry the same location",
                fetchChunkRetryCnt,
                fetchChunkMaxRetry,
                location,
                e);
            Uninterruptibles.sleepUninterruptibly(retryWaitMs, TimeUnit.MILLISECONDS);
          }
        }
      }
      throw new CelebornIOException("createPartitionReader failed! " + location, lastException);
    }

    private ByteBuf getNextChunk() throws IOException {
      while (fetchChunkRetryCnt < fetchChunkMaxRetry) {
        try {
          if (isExcluded(currentReader.getLocation())) {
            throw new CelebornIOException(
                "Fetch data from excluded worker! " + currentReader.getLocation());
          }
          return currentReader.next();
        } catch (Exception e) {
          excludeFailedLocation(currentReader.getLocation(), e);
          fetchChunkRetryCnt++;
          currentReader.close();
          if (fetchChunkRetryCnt == fetchChunkMaxRetry) {
            logger.warn("Fetch chunk fail exceeds max retry {}", fetchChunkRetryCnt, e);
            throw new CelebornIOException(
                "Fetch chunk failed for "
                    + fetchChunkRetryCnt
                    + " times for location "
                    + currentReader.getLocation(),
                e);
          } else {
            if (currentReader.getLocation().hasPeer()) {
              logger.warn(
                  "Fetch chunk failed {}/{} times for location {}, change to peer",
                  fetchChunkRetryCnt,
                  fetchChunkMaxRetry,
                  currentReader.getLocation(),
                  e);
              // fetchChunkRetryCnt % 2 == 0 means both replicas have been tried,
              // so sleep before next try.
              if (fetchChunkRetryCnt % 2 == 0) {
                Uninterruptibles.sleepUninterruptibly(retryWaitMs, TimeUnit.MILLISECONDS);
              }
              currentReader = createReaderWithRetry(currentReader.getLocation().getPeer(), null);
            } else {
              logger.warn(
                  "Fetch chunk failed {}/{} times for location {}",
                  fetchChunkRetryCnt,
                  fetchChunkMaxRetry,
                  currentReader.getLocation(),
                  e);
              Uninterruptibles.sleepUninterruptibly(retryWaitMs, TimeUnit.MILLISECONDS);
              currentReader = createReaderWithRetry(currentReader.getLocation(), null);
            }
          }
        }
      }
      throw new CelebornIOException("Fetch chunk failed! " + currentReader.getLocation());
    }

    private PartitionReader createReader(
        PartitionLocation location,
        PbStreamHandler pbStreamHandler,
        int fetchChunkRetryCnt,
        int fetchChunkMaxRetry)
        throws IOException, InterruptedException {
      if (!location.hasPeer()) {
        logger.debug("Partition {} has only one partition replica.", location);
      } else if (pbStreamHandler == null && attemptNumber % 2 == 1) {
        location = location.getPeer();
        logger.debug("Read peer {} for attempt {}.", location, attemptNumber);
      }
      logger.debug("Create reader for location {}", location);

      StorageInfo storageInfo = location.getStorageInfo();
      switch (storageInfo.getType()) {
        case HDD:
        case SSD:
        case MEMORY:
          if (enabledReadLocalShuffle
              && location.getHost().equals(localHostAddress)
              && storageInfo.getType() != StorageInfo.Type.MEMORY) {
            logger.debug("Read local shuffle file {}", localHostAddress);
            containLocalRead = true;
            return new LocalPartitionReader(
                conf, shuffleKey, location, clientFactory, startMapIndex, endMapIndex, callback);
          } else {
            return new WorkerPartitionReader(
                conf,
                shuffleKey,
                location,
                pbStreamHandler,
                clientFactory,
                startMapIndex,
                endMapIndex,
                fetchChunkRetryCnt,
                fetchChunkMaxRetry,
                callback);
          }
        case S3:
        case HDFS:
          return new DfsPartitionReader(
              conf, shuffleKey, location, clientFactory, startMapIndex, endMapIndex, callback);
        default:
          throw new CelebornIOException(
              String.format("Unknown storage info %s to read location %s", storageInfo, location));
      }
    }

    @Override
    public int read() throws IOException {
      if (position < limit) {
        int b = rawDataBuf[position];
        position++;
        return b & 0xFF;
      }

      if (!fillBuffer()) {
        return -1;
      }

      if (position >= limit) {
        return read();
      } else {
        int b = rawDataBuf[position];
        position++;
        return b & 0xFF;
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (b == null) {
        throw new NullPointerException();
      } else if (off < 0 || len < 0 || len > b.length - off) {
        throw new IndexOutOfBoundsException();
      } else if (len == 0) {
        return 0;
      }

      int readBytes = 0;
      while (readBytes < len) {
        while (position >= limit) {
          if (!fillBuffer()) {
            return readBytes > 0 ? readBytes : -1;
          }
        }

        int bytesToRead = Math.min(limit - position, len - readBytes);
        System.arraycopy(rawDataBuf, position, b, off + readBytes, bytesToRead);
        position += bytesToRead;
        readBytes += bytesToRead;
      }

      return readBytes;
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        int locationsCount = locations.size();
        logger.debug(
            "AppShuffleId {}, shuffleId {}, partitionId {}, total location count {}, read {}, skip {}",
            appShuffleId,
            shuffleId,
            partitionId,
            locationsCount,
            locationsCount - skipCount.sum(),
            skipCount.sum());
        if (currentChunk != null) {
          logger.debug("Release chunk {}", currentChunk);
          currentChunk.release();
          currentChunk = null;
        }
        if (currentReader != null) {
          logger.debug("Closing reader");
          currentReader.close();
          currentReader = null;
        }
        if (containLocalRead) {
          ShuffleClient.printReadStats(logger);
        }

        compressedBuf = null;
        rawDataBuf = null;
        batchesRead = null;
        locations = null;
        attempts = null;
        decompressor = null;
        fetchExcludedWorkers = null;

        closed = true;
      }
    }

    private boolean moveToNextChunk() throws IOException {
      if (currentChunk != null) {
        currentChunk.release();
      }
      currentChunk = null;
      if (currentReader.hasNext()) {
        currentChunk = getNextChunk();
        return true;
      } else if (fileIndex < locations.size()) {
        moveToNextReader(true);
        return currentReader != null;
      }
      if (currentReader != null) {
        currentReader.close();
        currentReader = null;
      }
      return false;
    }

    private void init() {
      int bufferSize = conf.clientFetchBufferSize();

      if (shuffleCompressionEnabled) {
        int headerLen = Decompressor.getCompressionHeaderLength(conf);
        bufferSize += headerLen;
        compressedBuf = new byte[bufferSize];
        decompressor = Decompressor.getDecompressor(conf);
      }
      rawDataBuf = new byte[bufferSize];
    }

    private boolean fillBuffer() throws IOException {
      try {
        if (firstChunk && currentReader != null) {
          init();
          currentChunk = getNextChunk();
          firstChunk = false;
        }
        if (currentChunk == null) {
          close();
          return false;
        }

        boolean hasData = false;
        while (currentChunk.isReadable() || moveToNextChunk()) {
          currentChunk.readBytes(sizeBuf);
          int mapId = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET);
          int attemptId = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET + 4);
          int batchId = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET + 8);
          int size = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET + 12);

          if (shuffleCompressionEnabled) {
            if (size > compressedBuf.length) {
              compressedBuf = new byte[size];
            }

            currentChunk.readBytes(compressedBuf, 0, size);
          } else {
            if (size > rawDataBuf.length) {
              rawDataBuf = new byte[size];
            }

            currentChunk.readBytes(rawDataBuf, 0, size);
          }

          // de-duplicate
          if (attemptId == attempts[mapId]) {
            if (!batchesRead.containsKey(mapId)) {
              Set<Integer> batchSet = new HashSet<>();
              batchesRead.put(mapId, batchSet);
            }
            Set<Integer> batchSet = batchesRead.get(mapId);
            if (!batchSet.contains(batchId)) {
              batchSet.add(batchId);
              callback.incBytesRead(BATCH_HEADER_SIZE + size);
              if (shuffleCompressionEnabled) {
                // decompress data
                int originalLength = decompressor.getOriginalLen(compressedBuf);
                if (rawDataBuf.length < originalLength) {
                  rawDataBuf = new byte[originalLength];
                }
                limit = decompressor.decompress(compressedBuf, rawDataBuf, 0);
              } else {
                limit = size;
              }
              position = 0;
              hasData = true;
              break;
            } else {
              logger.debug(
                  "Skip duplicated batch: mapId {}, attemptId {}, batchId {}.",
                  mapId,
                  attemptId,
                  batchId);
            }
          }
        }

        return hasData;
      } catch (IOException e) {
        logger.error(
            "Failed to fill buffer from chunk. AppShuffleId {}, shuffleId {}, partitionId {}, location {}",
            appShuffleId,
            shuffleId,
            partitionId,
            currentReader.getLocation(),
            e);
        IOException ioe = e;
        if (exceptionMaker != null) {
          if (shuffleClient.reportShuffleFetchFailure(appShuffleId, shuffleId)) {
            /*
             * [[ExceptionMaker.makeException]], for spark applications with celeborn.client.spark.fetch.throwsFetchFailure enabled will result in creating
             * a FetchFailedException; and that will make the TaskContext as failed with shuffle fetch issues - see SPARK-19276 for more.
             * Given this, Celeborn can wrap the FetchFailedException with our CelebornIOException
             */
            ioe =
                new CelebornIOException(
                    exceptionMaker.makeFetchFailureException(
                        appShuffleId, shuffleId, partitionId, e));
          }
        }
        throw ioe;
      } catch (Exception e) {
        logger.error(
            "Failed to read data from chunk. AppShuffleId {}, shuffleId {}, partitionId {}, location {}",
            appShuffleId,
            shuffleId,
            partitionId,
            currentReader.getLocation(),
            e);
        throw e;
      }
    }

    @Override
    public int totalPartitionsToRead() {
      return locations.size();
    }

    @Override
    public int partitionsRead() {
      return fileIndex;
    }
  }
}
