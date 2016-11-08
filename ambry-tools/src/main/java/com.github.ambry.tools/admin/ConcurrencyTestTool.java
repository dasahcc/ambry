/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.tools.admin;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.ClusterMapManager;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.ByteBufferAsyncWritableChannel;
import com.github.ambry.commons.ByteBufferReadableStreamChannel;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.config.NetworkConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobData;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.BlobType;
import com.github.ambry.messageformat.MessageFormatException;
import com.github.ambry.messageformat.MessageFormatFlags;
import com.github.ambry.messageformat.MessageFormatRecord;
import com.github.ambry.network.NetworkClient;
import com.github.ambry.network.NetworkClientFactory;
import com.github.ambry.network.NetworkMetrics;
import com.github.ambry.network.Port;
import com.github.ambry.network.PortType;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.protocol.DeleteRequest;
import com.github.ambry.protocol.DeleteResponse;
import com.github.ambry.protocol.GetOptions;
import com.github.ambry.protocol.GetRequest;
import com.github.ambry.protocol.GetResponse;
import com.github.ambry.protocol.PartitionRequestInfo;
import com.github.ambry.protocol.PutRequest;
import com.github.ambry.protocol.PutResponse;
import com.github.ambry.router.Callback;
import com.github.ambry.router.FutureResult;
import com.github.ambry.router.GetBlobOptions;
import com.github.ambry.router.GetBlobResult;
import com.github.ambry.router.ReadableStreamChannel;
import com.github.ambry.router.Router;
import com.github.ambry.router.RouterErrorCode;
import com.github.ambry.router.RouterException;
import com.github.ambry.router.RouterFactory;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.ByteBufferOutputStream;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.SystemTime;
import com.github.ambry.utils.Utils;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.rmi.UnexpectedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.ambry.utils.Utils.getRandomLong;


/**
 * Concurrency Test Tool is used to test any end point (server/frontend) for concurrent GETs and PUTs in a mixed type of
 * workload(puts and gets). Also, it issues GET of the same blob N no of times and ensures we get the same blob everytime.
 * This tool supports testing of Ambry Server and Router.
 *
 * Caution: We might need to be cautious about OOM errors as this tool holds all the blobs(that were injected) in memory
 * injected until the GETs have reached the threshold for the a particular blob.
 *
 * Supported features
 * - Parallel Puts and Gets
 * - Concurrent GETs for the same blobs
 * - This also means that we will get occasional bursts of GETs
 *
 * TODO: Metrics
 *
 * Future work:
 * - Adding delay between concurrent GETs for same blobs
 * - Adding delay for GETs to start so that the file cache will only have recently uploaded blobs(and not all)
 *
 * Example :
 * Router
 *  java -cp ambry.jar com.github.ambry.tools.admin.ConcurrencyTestTool --hardwareLayout [HardwareLayoutFile]
 *  --partitionLayout [PartitionLayoutFile] --putGetHelperFactory com.github.ambry.tools.admin.RouterPutGetHelperFactory
 *  --maxParallelPutCount 2 --parallelGetCount 4 --burstCountForGet 4 --maxGetCountPerBlob 10
 *  --totalPutBlobCount 1000 --maxBlobSizeInBytes 1000 --minBlobSizeInBytes 100 --routerPropsFilePath [Path to router props
 *  file] --deleteOnExit true
 *
 * Server
 *  java -cp ambry.jar com.github.ambry.tools.admin.ConcurrencyTestTool --hardwareLayout [HardwareLayoutFile]
 *  --partitionLayout [PartitionLayoutFile] --putGetHelperFactory com.github.ambry.tools.admin.RouterPutGetHelperFactory
 *  --maxParallelPutCount 2 --parallelGetCount 4 --burstCountForGet 4 --maxGetCountPerBlob 10
 *  --totalPutBlobCount 1000 --maxBlobSizeInBytes 1000 --minBlobSizeInBytes 100 --hostName [HostName] --port [PortNo]
 *  --deleteOnExit true
 *
 *  Few other options that might be useful:
 * --sleepTimeBetweenBatchGetsInMs long : Time to sleep in ms between batch Gets
 * --sleepTimeBetweenBatchPutsInMs long : Time to sleep in ms between batch Puts
 * --burstThreadCountForPut int         : No of concurrent PUTs by each producer thread
 */
public class ConcurrencyTestTool {
  private static final Logger logger = LoggerFactory.getLogger(ConcurrencyTestTool.class);
  private static final String ROUTER_PUT_GET_HELPER = "com.github.ambry.tools.admin.RouterPutGetHelperFactory";
  private static final String SERVER_PUT_GET_HELPER = "com.github.ambry.tools.admin.ServerPutGetHelperFactory";

  public static void main(String args[])
      throws Exception {
    InvocationOptions options = new InvocationOptions(args);
    Properties properties = Utils.loadProps(options.routerPropsFilePath);
    ClusterMap clusterMap = new ClusterMapManager(options.hardwareLayoutFilePath, options.partitionLayoutFilePath,
        new ClusterMapConfig(new VerifiableProperties(properties)));

    PutGetHelperFactory putGetHelperFactory = null;
    if (options.putGetHelperFactoryStr.equals(ROUTER_PUT_GET_HELPER)) {
      putGetHelperFactory =
          Utils.getObj(options.putGetHelperFactoryStr, properties, clusterMap, options.maxBlobSizeInBytes,
              options.minBlobSizeInBytes);
    } else if (options.putGetHelperFactoryStr.equals(SERVER_PUT_GET_HELPER)) {
      putGetHelperFactory =
          Utils.getObj(options.putGetHelperFactoryStr, properties, options.hostName, options.port, clusterMap,
              options.maxBlobSizeInBytes, options.minBlobSizeInBytes);
    }
    PutGetHelper putGetHelper = putGetHelperFactory.getPutGetHelper();
    ConcurrencyTestTool concurrencyTestTool = new ConcurrencyTestTool();
    concurrencyTestTool.startTest(putGetHelper, options.maxParallelPutCount, options.parallelGetCount,
        options.totalPutBlobCount, options.maxGetCountPerBlob, options.burstCountForGet,
        options.maxFailuresPerPutBatchToStopPuts, options.maxFailuresPerGetBatchToStopGets, options.deleteOnExit,
        options.sleepTimeBetweenBatchPutsInMs, options.sleepTimeBetweenBatchGetsInMs);
  }

  /**
   * Initializes and starts the concurrent test tool.
   * @param putGetHelper the {@link PutGetHelper} to be used for PUTs and GETs
   * @param maxParallelPutCount maximum number of parallel puts per phase
   * @param parallelGetCount the number of parallel Gets per phase
   * @param totalPutCount total number of blobs to be uploaded
   * @param maxGetCountPerBlob total number of times a particular blob can be fetched before its deleted
   * @param burstCountGet Burst count for GETs. Each GET consumer thread will fetch a blob these many no of
   *                         times simultaneously
   * @param maxFailuresPerPutBatchToStopPuts Maximum allowable failures per Put batch in order to continue with PUTs
   * @param maxFailuresPerGetBatchToStopGets Maximum allowable failures per Get batch in order to continue with GETs
   * @param deleteOnExit Deletes the blobs once GETs have reached a threshold on every blob
   * @param sleepTimeBetweenBatchPutsInMs Time to sleep in ms between batch Puts per Producer thread
   * @param sleepTimeBetweenBatchGetsInMs Time to sleep in ms between batch Gets per Consumer thread
   * @throws InterruptedException
   */
  public void startTest(PutGetHelper putGetHelper, int maxParallelPutCount, int parallelGetCount, int totalPutCount,
      int maxGetCountPerBlob, int burstCountGet, int maxFailuresPerPutBatchToStopPuts,
      int maxFailuresPerGetBatchToStopGets, boolean deleteOnExit, long sleepTimeBetweenBatchPutsInMs,
      long sleepTimeBetweenBatchGetsInMs)
      throws InterruptedException {
    AtomicInteger currentPutCount = new AtomicInteger(0);
    AtomicInteger getCompletedCount = new AtomicInteger(0);
    AtomicBoolean putComplete = new AtomicBoolean(false);
    // Creating shared queue for put Thread and get Thread
    ArrayList<BlobAccessInfo> sharedQueue = new ArrayList<>();
    BlockingQueue<String> deletedBlobIds = new LinkedBlockingQueue<>();
    final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();

    logger.trace("Initiating Put thread ");
    // Creating PutThread
    Thread putThread = new Thread(
        new PutThread(putGetHelper, sharedQueue, totalPutCount, currentPutCount, maxParallelPutCount,
            maxFailuresPerPutBatchToStopPuts, putComplete, sleepTimeBetweenBatchPutsInMs, threadLocalRandom));

    logger.trace("Initiating Get thread ");
    // Creating Get thread
    Thread getThread = new Thread(
        new GetThread(putGetHelper, sharedQueue, maxGetCountPerBlob, currentPutCount, getCompletedCount, burstCountGet,
            parallelGetCount, putComplete, maxFailuresPerGetBatchToStopGets, sleepTimeBetweenBatchGetsInMs,
            deleteOnExit, threadLocalRandom, deletedBlobIds));

    Thread deleteThread = null;
    if (deleteOnExit) {
      logger.trace("Initiating Delete thread ");
      DeleteThread deleteBlobThread = new DeleteThread(putGetHelper, totalPutCount, deletedBlobIds);
      deleteThread = new Thread(deleteBlobThread);
      deleteThread.start();
    }

    logger.trace("Starting Put thread ");
    putThread.start();
    logger.trace("Starting Get thread ");
    getThread.start();

    putThread.join();
    getThread.join();

    if (deleteThread != null) {
      logger.trace("Starting Delete thread ");
      deleteThread.join();
    }

    logger.trace("Invoking shutdown after completing the test suite ");
    // shutting down the PutGetHelper class to release any resources held up
    putGetHelper.shutdown();
  }

  /**
   * Put threads to assist in producing blobs or uploading blobs to ambry
   */
  class PutThread implements Runnable {
    private final PutGetHelper putHelper;
    private final ArrayList<BlobAccessInfo> blobAccessInfos;
    private final int totalPutCount;
    private final AtomicInteger currentPutCount;
    private final int maxParallelRequest;
    private final int maxFailuresPerPutBatchToStopPuts;
    private AtomicBoolean putComplete;
    private final long sleepTimeBetweenBatchPutsInMs;
    private final ThreadLocalRandom threadLocalRandom;

    public PutThread(PutGetHelper putHelper, ArrayList<BlobAccessInfo> blobAccessInfos, final int totalPutCount,
        AtomicInteger currentPutCount, int maxParallelRequest, int maxFailuresPerPutBatchToStopPuts,
        AtomicBoolean putComplete, long sleepTimeBetweenBatchPutsInMs, ThreadLocalRandom threadLocalRandom) {
      this.putHelper = putHelper;
      this.blobAccessInfos = blobAccessInfos;
      this.totalPutCount = totalPutCount;
      this.currentPutCount = currentPutCount;
      this.maxParallelRequest = maxParallelRequest;
      this.maxFailuresPerPutBatchToStopPuts = maxFailuresPerPutBatchToStopPuts;
      this.putComplete = putComplete;
      this.sleepTimeBetweenBatchPutsInMs = sleepTimeBetweenBatchPutsInMs;
      this.threadLocalRandom = threadLocalRandom;
    }

    @Override
    public void run() {
      while (currentPutCount.get() < totalPutCount) {
        try {
          int burstCount = threadLocalRandom.nextInt(maxParallelRequest) + 1;
          logger.trace("PutThread producing " + burstCount + " times ");
          final CountDownLatch countDownLatch = new CountDownLatch(burstCount);
          final AtomicInteger failureCount = new AtomicInteger(0);
          for (int j = 0; j < burstCount; j++) {
            Callback<Pair<String, byte[]>> callback = new Callback<Pair<String, byte[]>>() {
              @Override
              public void onCompletion(Pair<String, byte[]> result, Exception exception) {
                if (exception == null) {
                  if (currentPutCount.get() < totalPutCount) {
                    blobAccessInfos.add(new BlobAccessInfo(result.getFirst(), result.getSecond()));
                    currentPutCount.incrementAndGet();
                  }
                } else {
                  logger.error("PutBlob failed with " + exception.getStackTrace());
                  failureCount.incrementAndGet();
                }
                countDownLatch.countDown();
              }
            };
            FutureResult futureResult = new FutureResult();
            putHelper.putBlob(futureResult, callback);
          }
          countDownLatch.await();
          if (failureCount.get() > maxFailuresPerPutBatchToStopPuts) {
            logger.error(failureCount.get() + "  failures during this batch, hence exiting the Put Thread");
            break;
          }
          Thread.sleep(threadLocalRandom.nextLong(sleepTimeBetweenBatchPutsInMs));
        } catch (InterruptedException ex) {
          logger.error("InterruptedException in putThread " + ex.getStackTrace());
        }
      }
      putComplete.set(true);
      logger.info(
          "Exiting Producer. Current Put Count " + currentPutCount.get() + ", total Put Count " + totalPutCount);
    }
  }

  /**
   * Record to hold information about a blob along with its content and its reference count
   */
  class BlobAccessInfo {
    String blobId;
    AtomicInteger referenceCount;
    AtomicInteger burstGetReferenceCount;
    byte[] blobContent;

    public BlobAccessInfo(String blobId, byte[] blobContent) {
      this.blobId = blobId;
      this.referenceCount = new AtomicInteger(0);
      this.burstGetReferenceCount = new AtomicInteger(0);
      this.blobContent = blobContent;
    }
  }

  /**
   * Get threads to assist in consuming blobs from ambry and verifying its content
   */
  class GetThread implements Runnable {
    private final PutGetHelper getHelper;
    private final ArrayList<BlobAccessInfo> blobAccessInfos;
    private final int maxGetCountPerBlob;
    private final AtomicInteger getCompletedCount;
    private final AtomicInteger totalPutCount;
    private final int maxBurstCountPerblob;
    private final int parallelGetCount;
    private final boolean deleteOnExit;
    private final AtomicBoolean putComplete;
    private final int maxFailuresPerGetBatch;
    private final long sleepTimeBetweenBatchGetsInMs;
    private final ThreadLocalRandom threadLocalRandom;
    private final BlockingQueue<String> deleteBlobIds;

    public GetThread(PutGetHelper getHelper, ArrayList<BlobAccessInfo> blobAccessInfos, int maxGetCountPerBlob,
        final AtomicInteger totalPutCount, AtomicInteger getCompletedCount, int maxBurstCountPerBlob,
        int parallelGetCount, AtomicBoolean putComplete, int maxFailuresPerGetBatch, long sleepTimeBetweenBatchGetsInMs,
        boolean deleteOnExit, ThreadLocalRandom threadLocalRandom, BlockingQueue<String> deleteBlobIds) {
      this.getHelper = getHelper;
      this.blobAccessInfos = blobAccessInfos;
      this.maxGetCountPerBlob = maxGetCountPerBlob;
      this.totalPutCount = totalPutCount;
      this.parallelGetCount = parallelGetCount;
      this.getCompletedCount = getCompletedCount;
      this.maxBurstCountPerblob = maxBurstCountPerBlob;
      this.deleteOnExit = deleteOnExit;
      this.putComplete = putComplete;
      this.maxFailuresPerGetBatch = maxFailuresPerGetBatch;
      this.sleepTimeBetweenBatchGetsInMs = sleepTimeBetweenBatchGetsInMs;
      this.threadLocalRandom = threadLocalRandom;
      this.deleteBlobIds = deleteBlobIds;
    }

    @Override
    public void run() {
      while (!putComplete.get() || getCompletedCount.get() < totalPutCount.get()) {
        try {
          int size = blobAccessInfos.size();
          if (size > 0) {
            List<Pair<BlobAccessInfo, Integer>> blobInfoBurstCountPairList = new ArrayList<>();
            List<String> toBeDeletedBlobsPerBatch = new ArrayList<>();
            int blobCountPerBatch = Math.min(size, parallelGetCount);
            for (int i = 0; i < blobCountPerBatch; i++) {
              size = blobAccessInfos.size();
              BlobAccessInfo blobAccessInfo = blobAccessInfos.get(threadLocalRandom.nextInt(size));
              if (blobAccessInfo != null) {
                int burstCount = threadLocalRandom.nextInt(maxBurstCountPerblob) + 1;
                blobInfoBurstCountPairList.add(new Pair(blobAccessInfo, burstCount));
                blobAccessInfo.referenceCount.addAndGet(burstCount);
                if (blobAccessInfo.referenceCount.get() >= maxGetCountPerBlob) {
                  blobAccessInfos.remove(blobAccessInfo);
                  getCompletedCount.incrementAndGet();
                  toBeDeletedBlobsPerBatch.add(blobAccessInfo.blobId);
                }
              }
            }

            final CountDownLatch countDownLatchForBatch = new CountDownLatch(blobCountPerBatch);
            final AtomicInteger failureCount = new AtomicInteger(0);
            for (Pair<BlobAccessInfo, Integer> blobAccessInfoBurstCountPair : blobInfoBurstCountPairList) {
              int burstCount = blobAccessInfoBurstCountPair.getSecond();
              final BlobAccessInfo blobAccessInfo = blobAccessInfoBurstCountPair.getFirst();
              logger.trace("Get Thread fetching " + blobAccessInfo.blobId + " " + burstCount + " times ");
              final CountDownLatch countDownLatch = new CountDownLatch(burstCount);
              for (int j = 0; j < burstCount; j++) {
                Callback<Void> callback = new Callback<Void>() {
                  @Override
                  public void onCompletion(Void result, Exception exception) {
                    countDownLatch.countDown();
                    blobAccessInfo.burstGetReferenceCount.decrementAndGet();
                    if (countDownLatch.getCount() == 0) {
                      countDownLatchForBatch.countDown();
                    }
                    if (exception != null) {
                      logger.error("Get and validation of " + blobAccessInfo.blobId + " failed with "
                          + exception.getStackTrace());
                      failureCount.incrementAndGet();
                    }
                  }
                };
                FutureResult futureResult = new FutureResult();
                blobAccessInfo.burstGetReferenceCount.incrementAndGet();
                getHelper.getBlobAndValidate(blobAccessInfo.blobId, blobAccessInfo.blobContent,
                    getHelper.getErrorCodeForNoError(), futureResult, callback);
              }
            }
            countDownLatchForBatch.await();
            if (deleteOnExit) {
              deleteBlobIds.addAll(toBeDeletedBlobsPerBatch);
            }
            if (failureCount.get() > maxFailuresPerGetBatch) {
              logger.error(failureCount.get() + "  failures during this batch, hence exiting the GetThread");
              break;
            }
          }
          Thread.sleep(threadLocalRandom.nextLong(sleepTimeBetweenBatchGetsInMs));
        } catch (InterruptedException ex) {
          logger.error("InterruptedException in GetThread " + ex.getStackTrace());
        }
      }
      logger.info("Exiting GetThread ");
    }
  }

  /**
   * Thread used for deleting blobs from ambry
   */
  class DeleteThread implements Runnable {
    private final PutGetHelper deleteConsumerJob;
    private final int putCount;
    private AtomicInteger deletedCount = new AtomicInteger(0);
    private BlockingQueue<String> deleteBlobIds;
    private CountDownLatch countDownLatch;

    public DeleteThread(PutGetHelper deleteConsumerJob, int putCount, BlockingQueue<String> deleteBlobIds) {
      this.putCount = putCount;
      this.deleteConsumerJob = deleteConsumerJob;
      this.deleteBlobIds = deleteBlobIds;
      this.countDownLatch = new CountDownLatch(putCount);
    }

    public void run() {
      while (deletedCount.get() < putCount) {
        try {
          final String blobIdStr = deleteBlobIds.poll(50, TimeUnit.MILLISECONDS);
          if (blobIdStr != null) {
            deletedCount.incrementAndGet();
            logger.trace("Deleting blob " + blobIdStr);
            FutureResult futureResult = new FutureResult();
            Callback<Void> callback = new Callback<Void>() {
              @Override
              public void onCompletion(Void result, Exception exception) {
                logger.trace("Deletion completed for " + blobIdStr);
                countDownLatch.countDown();
                if (exception != null) {
                  logger.error("Deletion of " + blobIdStr + " failed with " + exception.getStackTrace());
                }
              }
            };
            deleteConsumerJob.deleteBlobAndValidate(blobIdStr, deleteConsumerJob.getErrorCodeForNoError(), futureResult,
                callback);
          }
        } catch (InterruptedException e) {
          logger.error(" Interrupted Exception thrown in , exception " + e.getStackTrace());
        }
      }
      try {
        countDownLatch.await();
      } catch (InterruptedException e) {
        logger.error(
            " Interrupted Exception thrown while waiting for deletion to complete , exception " + e.getStackTrace());
      }
    }
  }

  /**
   * An interface to assist in uploading blobs to ambry, fetching and deleting the same from ambry
   */
  interface PutGetHelper<T> {
    /**
     * Request to uplaod a blob to ambry
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     * @return a {@link FutureResult} which will contain the result eventually
     */
    void putBlob(FutureResult<T> futureResult, Callback<T> callback);

    /**
     * Request to fetch the blob pertaining to the {@code blobId} passed in and verify for its content
     * @param blobId the blobId the of that blob that needs to be fetched
     * @param blobContent the content of the blob that needs to be verified against
     * @param expectedErrorCode the expected error code for the Get call
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     */
    void getBlobAndValidate(String blobId, byte[] blobContent, T expectedErrorCode, FutureResult<T> futureResult,
        Callback<T> callback);

    /**
     * Deletes a blob from ambry pertaining to the {@code blobId} passed in and verifies that subsequent Get fails
     * @param blobId the blobId the of that blob that needs to be fetched
     * @param expectedErrorCode the expected error code for the Delete call
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     */
    void deleteBlobAndValidate(String blobId, T expectedErrorCode, FutureResult<T> futureResult, Callback<T> callback);

    /**
     * Returns the default Error code that is expected for a Get or a Delete call
     * @return the default error code that is expected for a Get or a Delete call
     */
    T getErrorCodeForNoError();

    void shutdown()
        throws InterruptedException;
  }

  /**
   * An implementation of {@link PutGetHelper} for an Ambry Router
   */
  static class RouterPutGetHelper implements PutGetHelper<RouterErrorCode> {
    private final int maxBlobSize;
    private final int minBlobSize;
    private final Router router;
    private final ThreadLocalRandom localRandom;

    public RouterPutGetHelper(Properties properties, ClusterMap clusterMap, String routerFactoryClass, int maxBlobSize,
        int minBlobSize)
        throws Exception {
      VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
      RouterFactory routerFactory =
          Utils.getObj(routerFactoryClass, verifiableProperties, clusterMap, new LoggingNotificationSystem());
      router = routerFactory.getRouter();
      this.maxBlobSize = maxBlobSize;
      this.minBlobSize = minBlobSize;
      this.localRandom = ThreadLocalRandom.current();
    }

    /**
     * {@inheritDoc}
     * Uploads a blob to the server directly and returns the blobId along with the content
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     * @return a {@link FutureResult} which will contain the result eventually
     */
    @Override
    public void putBlob(final FutureResult futureResult, final Callback callback) {
      int randomNum = localRandom.nextInt((maxBlobSize - minBlobSize) + 1) + minBlobSize;
      final byte[] blob = new byte[randomNum];
      byte[] usermetadata = new byte[new Random().nextInt(1024)];
      BlobProperties props = new BlobProperties(randomNum, "test");
      try {
        final long startTime = SystemTime.getInstance().nanoseconds();
        ByteBufferReadableStreamChannel putChannel = new ByteBufferReadableStreamChannel(ByteBuffer.wrap(blob));
        router.putBlob(props, usermetadata, putChannel, new Callback<String>() {
          @Override
          public void onCompletion(String result, Exception exception) {
            long latencyPerBlob = SystemTime.getInstance().nanoseconds() - startTime;
            logger.trace(" Time taken to put blob id " + result + " in ms " + latencyPerBlob / SystemTime.NsPerMs
                + " for blob of size " + blob.length);
            Exception exceptionToReturn = null;
            Pair<String, byte[]> toReturn = null;
            if (result != null) {
              toReturn = new Pair(result, blob);
            } else {
              exceptionToReturn = exception;
            }
            futureResult.done(toReturn, exceptionToReturn);
            if (callback != null) {
              callback.onCompletion(toReturn, exceptionToReturn);
            }
          }
        });
      } catch (Exception e) {
        futureResult.done(null, e);
        if (callback != null) {
          callback.onCompletion(null, e);
        }
      }
    }

    /**
     * {@inheritDoc}
     * Request to fetch the blob from the server directly pertaining to the {@code blobId} passed in and verify for
     * its content
     * @param blobIdStr the (string representation of the) blobId of that blob that needs to be fetched
     * @param blobContent the content of the blob that needs to be verified against
     * @param expectedErrorCode the expected error code for the Get call
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     */
    @Override
    public void getBlobAndValidate(final String blobIdStr, final byte[] blobContent,
        final RouterErrorCode expectedErrorCode, final FutureResult futureResult, final Callback callback) {
      try {
        final Long startTimeGetBlob = SystemTime.getInstance().nanoseconds();
        router.getBlob(blobIdStr, new GetBlobOptions(), new Callback<GetBlobResult>() {
          @Override
          public void onCompletion(GetBlobResult getBlobResult, Exception exception) {
            long latencyPerBlob = SystemTime.getInstance().nanoseconds() - startTimeGetBlob;
            logger.trace("Time taken to get blob " + blobIdStr + " in ms " + latencyPerBlob / SystemTime.NsPerMs);
            Exception exceptionToReturn = null;
            if (getBlobResult != null) {
              logger.trace("OnCompletion returned for blob " + blobIdStr + " with non null result ");
              try {
                Thread compareThread = new Thread(
                    new CompareRunnable(blobIdStr, blobContent, getBlobResult.getBlobDataChannel(), futureResult,
                        callback));
                (Utils.newThread(compareThread, true)).start();
              } catch (Exception e) {
                futureResult.done(null, exceptionToReturn);
                if (callback != null) {
                  callback.onCompletion(null, exceptionToReturn);
                }
              }
            } else {
              logger.trace("OnCompletion returned for blob " + blobIdStr + " with exception ");
              if (exception instanceof RouterException) {
                RouterException routerException = (RouterException) exception;
                if (expectedErrorCode != null) {
                  if (!expectedErrorCode.equals(routerException.getErrorCode())) {
                    logger.error("Error code mismatch. Expected " + expectedErrorCode + ", actual "
                        + routerException.getErrorCode());
                    exceptionToReturn = exception;
                  }
                } else {
                  logger.trace("RouterException thrown with error code " + routerException.getErrorCode() + " "
                      + "when no exception was expected for " + blobIdStr);
                  exceptionToReturn = exception;
                }
              } else {
                logger.trace("UnknownException thrown for " + blobIdStr);
                exceptionToReturn = exception;
              }
              futureResult.done(null, exceptionToReturn);
              if (callback != null) {
                callback.onCompletion(null, exceptionToReturn);
              }
            }
          }
        });
      } catch (Exception e) {
        futureResult.done(null, e);
        if (callback != null) {
          callback.onCompletion(null, e);
        }
      }
    }

    /**
     * Class to compare the getBlob output
     */
    class CompareRunnable implements Runnable {
      private final String blobId;
      private final byte[] putContent;
      private final ReadableStreamChannel readableStreamChannel;
      private final FutureResult futureResult;
      private final Callback callback;

      CompareRunnable(String blobId, byte[] putContent, ReadableStreamChannel readableStreamChannel,
          FutureResult futureResult, Callback callback) {
        this.blobId = blobId;
        this.putContent = putContent;
        this.readableStreamChannel = readableStreamChannel;
        this.futureResult = futureResult;
        this.callback = callback;
      }

      public void run() {
        ByteBuffer putContentBuf = ByteBuffer.wrap(putContent);
        ByteBufferAsyncWritableChannel getChannel = new ByteBufferAsyncWritableChannel();
        Future<Long> readIntoFuture = readableStreamChannel.readInto(getChannel, null);
        final int bytesToRead = putContentBuf.remaining();
        int readBytes = 0;
        Exception exceptionToReturn = null;
        try {
          do {
            ByteBuffer buf = null;
            buf = getChannel.getNextChunk();
            int bufLength = buf.remaining();
            if (readBytes + bufLength > bytesToRead) {
              exceptionToReturn = new IllegalStateException(
                  "total content read" + (readBytes + bufLength) + " should not be greater than "
                      + "length of put content " + bytesToRead + " for " + blobId);
            }
            while (buf.hasRemaining()) {
              if (putContentBuf.get() != buf.get()) {
                exceptionToReturn = new IllegalStateException("Get and Put blob content mis-match for " + blobId);
              }
              readBytes++;
            }
            getChannel.resolveOldestChunk(null);
          } while (readBytes < bytesToRead);
          if (readBytes != readIntoFuture.get()) {
            exceptionToReturn = new IllegalStateException(
                "The returned length in the future " + readIntoFuture.get() + " didn't match the "
                    + "length of data written " + readBytes + " for " + blobId);
          }
          if (getChannel.getNextChunk(0) != null) {
            exceptionToReturn = new IllegalStateException(
                "More data found in the channel after reading all the required bytes for " + blobId);
          }
          readIntoFuture.get();
        } catch (InterruptedException e) {
          exceptionToReturn = e;
        } catch (ExecutionException e) {
          exceptionToReturn = e;
        } finally {
          futureResult.done(null, exceptionToReturn);
          if (callback != null) {
            callback.onCompletion(null, exceptionToReturn);
          }
        }
      }
    }

    /**
     * {@inheritDoc}
     * Request to delete the blob from the server directly pertaining to the {@code blobId} passed in and verify that
     * the subsequent get fails with {@link RouterErrorCode#BlobDeleted}
     * @param blobId the blobId the of that blob that needs to be fetched
     * @param expectedErrorCode the expected error code for the Delete call
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     */
    @Override
    public void deleteBlobAndValidate(final String blobId, final RouterErrorCode expectedErrorCode,
        final FutureResult futureResult, final Callback callback) {

      try {
        final Long startTimeGetBlob = SystemTime.getInstance().nanoseconds();
        router.deleteBlob(blobId, new Callback<Void>() {
          @Override
          public void onCompletion(Void result, Exception exception) {
            long latencyPerBlob = SystemTime.getInstance().nanoseconds() - startTimeGetBlob;
            logger.trace(" Time taken to delete blob " + blobId + " in ms " + latencyPerBlob / SystemTime.NsPerMs);
            final AtomicReference<Exception> exceptionToReturn = new AtomicReference<Exception>();
            if (exception == null) {
              logger.trace("Deletion of " + blobId + " succeeded. Issuing Get to confirm the deletion ");
              FutureResult<GetBlobResult> getBlobResultFutureResult = new FutureResult<GetBlobResult>();
              getBlobAndValidate(blobId, null, RouterErrorCode.BlobDeleted, getBlobResultFutureResult,
                  new Callback<GetBlobResult>() {
                    @Override
                    public void onCompletion(GetBlobResult getBlobResult, Exception exception) {
                      if (getBlobResult != null) {
                        exceptionToReturn.set(new IllegalStateException(
                            "Get of a deleted blob " + blobId + " should have failed with "
                                + RouterErrorCode.BlobDeleted));
                      } else {
                        if (exception != null) {
                          exceptionToReturn.set(exception);
                        }
                      }
                    }
                  });
            } else {
              if (exception instanceof RouterException) {
                RouterException routerException = (RouterException) exception;
                if (expectedErrorCode != null) {
                  if (!expectedErrorCode.equals(routerException.getErrorCode())) {
                    logger.trace("Error code mismatch. Expected " + expectedErrorCode + ", actual "
                        + routerException.getErrorCode());
                    exceptionToReturn.set(exception);
                  }
                } else {
                  logger.trace(
                      "RouterException thrown for deletion of " + blobId + " " + routerException.getErrorCode());
                  exceptionToReturn.set(exception);
                }
              } else {
                logger.trace("UnknownException thrown for deletion of " + blobId + " " + exception.getStackTrace());
                exceptionToReturn.set(exception);
              }
            }
            futureResult.done(null, exceptionToReturn.get());
            if (callback != null) {
              callback.onCompletion(null, exceptionToReturn.get());
            }
          }
        });
      } catch (Exception e) {
        logger.error(" Unknown Exception thrown when deleting blob " + blobId + ", " + e.getStackTrace());
        futureResult.done(null, e);
        if (callback != null) {
          callback.onCompletion(null, e);
        }
      }
    }

    /**
     * {@inheritDoc}
     * Returns the default Error code that is expected for a Get call or a delete call
     * @return the default error code that is expected for a Get call or a delete call
     */
    @Override
    public RouterErrorCode getErrorCodeForNoError() {
      return null;
    }

    /**
     * {@inheritDoc}
     * Request to clean up resources used by this helper class, i.e. NetworkClient in this context
     */
    @Override
    public void shutdown()
        throws InterruptedException {
      try {
        long startTime = System.currentTimeMillis();
        router.close();
        logger.info("Router shutdown took " + (System.currentTimeMillis() - startTime) + " ms");
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }


  /**
   * An implementation of {@link PutGetHelper} for an Ambry Server
   */
  static class ServerPutGetHelper implements PutGetHelper<ServerErrorCode> {
    private final String hostName;
    private final int port;
    private final ClusterMap clusterMap;
    private final int maxBlobSize;
    private final int minBlobSize;
    private final ThreadLocalRandom localRandom;
    private final NetworkClientUtils networkClientUtils;
    private final AtomicInteger correlationIdGenerator = new AtomicInteger(0);

    public ServerPutGetHelper(Properties properties, String hostName, int port, ClusterMap clusterMap, int maxBlobSize,
        int minBlobSize)
        throws Exception {
      VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
      NetworkClientFactory networkClientFactory =
          new NetworkClientFactory(new NetworkMetrics(new MetricRegistry()), new NetworkConfig(verifiableProperties),
              null, 20, 20, 10000, SystemTime.getInstance());
      NetworkClient networkClient = networkClientFactory.getNetworkClient();
      this.networkClientUtils = new NetworkClientUtils(networkClient, 50);
      this.hostName = hostName;
      this.port = port;
      this.clusterMap = clusterMap;
      this.maxBlobSize = maxBlobSize;
      this.minBlobSize = minBlobSize;
      this.localRandom = ThreadLocalRandom.current();
    }

    /**
     * {@inheritDoc}
     * Uploads a blob to the server directly and returns the blobId along with the content
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     * @return a {@link FutureResult} which will contain the result eventually
     */
    @Override
    public void putBlob(FutureResult futureResult, final Callback callback) {
      int randomNum = localRandom.nextInt((maxBlobSize - minBlobSize) + 1) + minBlobSize;
      final byte[] blob = new byte[randomNum];
      byte[] usermetadata = new byte[new Random().nextInt(1024)];
      BlobProperties props = new BlobProperties(randomNum, "test");
      int correlationId = -1;
      try {
        List<PartitionId> partitionIds = clusterMap.getWritablePartitionIds();
        int index = (int) getRandomLong(localRandom, partitionIds.size());
        PartitionId partitionId = partitionIds.get(index);
        final BlobId blobId = new BlobId(partitionId);
        correlationId = correlationIdGenerator.incrementAndGet();
        PutRequest putRequest =
            new PutRequest(correlationId, "ConcurrencyTest", blobId, props, ByteBuffer.wrap(usermetadata),
                ByteBuffer.wrap(blob), props.getBlobSize(), BlobType.DataBlob);
        final long startTime = SystemTime.getInstance().nanoseconds();
        List<NetworkClientUtils.RequestMetadata> requestMetadataList = new ArrayList<>();
        final NetworkClientUtils.RequestMetadata requestMetadata = new NetworkClientUtils.RequestMetadata<ByteBuffer>();
        requestMetadata.requestInfo = new RequestInfo(hostName, new Port(port, PortType.PLAINTEXT), putRequest);
        requestMetadata.futureResult = new FutureResult<ByteBuffer>();
        requestMetadata.correlationId = correlationId;
        requestMetadata.callback = new Callback<ByteBuffer>() {
          @Override
          public void onCompletion(ByteBuffer result, Exception exception) {
            long latencyPerBlob = SystemTime.getInstance().nanoseconds() - startTime;
            logger.trace(requestMetadata.correlationId + " Time taken to put blob id " + blobId + " in ms "
                + latencyPerBlob / SystemTime.NsPerMs + " for blob of size " + blob.length);
            Exception exceptionToReturn = null;
            Pair<String, byte[]> toReturn = null;
            if (result != null) {
              try {
                PutResponse putResponse = PutResponse.readFrom(new DataInputStream(new ByteBufferInputStream(result)));
                if (putResponse.getError() != ServerErrorCode.No_Error) {
                  exceptionToReturn = new UnexpectedException("error " + putResponse.getError());
                } else {
                  toReturn = new Pair<>(blobId.getID(), blob);
                }
              } catch (IOException e) {
                exceptionToReturn = e;
              }
            } else {
              exceptionToReturn = exception;
            }
            requestMetadata.futureResult.done(toReturn, exceptionToReturn);
            if (callback != null) {
              callback.onCompletion(toReturn, exceptionToReturn);
            }
          }
        };
        requestMetadataList.add(requestMetadata);
        networkClientUtils.poll(requestMetadataList);
      } catch (Exception e) {
        logger.error(correlationId + " Unknown Exception thrown when putting blob " + e.getStackTrace());
        futureResult.done(null, e);
        if (callback != null) {
          callback.onCompletion(null, e);
        }
      }
    }

    /**
     * {@inheritDoc}
     * Request to fetch the blob from the server directly pertaining to the {@code blobId} passed in and verify for
     * its content
     * @param blobIdStr the (string representation of the) blobId of that blob that needs to be fetched
     * @param blobContent the content of the blob that needs to be verified against
     * @param expectedErrorCode the expected error code for the Get call
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     */
    @Override
    public void getBlobAndValidate(final String blobIdStr, final byte[] blobContent,
        final ServerErrorCode expectedErrorCode, FutureResult futureResult, final Callback callback) {
      final BlobData[] blobData = {null};
      BlobId blobId = null;
      int correlationId = -1;
      try {
        blobId = new BlobId(blobIdStr, clusterMap);
        ArrayList<BlobId> blobIds = new ArrayList<BlobId>();
        blobIds.add(blobId);
        ArrayList<PartitionRequestInfo> partitionRequestInfoList = new ArrayList<PartitionRequestInfo>();
        PartitionRequestInfo partitionRequestInfo = new PartitionRequestInfo(blobId.getPartition(), blobIds);
        partitionRequestInfoList.add(partitionRequestInfo);
        correlationId = correlationIdGenerator.incrementAndGet();
        GetRequest getRequest =
            new GetRequest(correlationId, "ConcurrencyTest", MessageFormatFlags.Blob, partitionRequestInfoList,
                GetOptions.None);
        final Long startTimeGetBlob = SystemTime.getInstance().nanoseconds();
        List<NetworkClientUtils.RequestMetadata> requestInfoList = new ArrayList<>();
        final NetworkClientUtils.RequestMetadata requestMetadata = new NetworkClientUtils.RequestMetadata<ByteBuffer>();
        requestMetadata.requestInfo = new RequestInfo(hostName, new Port(port, PortType.PLAINTEXT), getRequest);
        requestMetadata.futureResult = new FutureResult<ByteBuffer>();
        requestMetadata.correlationId = correlationId;
        requestMetadata.callback = new Callback<ByteBuffer>() {
          @Override
          public void onCompletion(ByteBuffer result, Exception exception) {
            long latencyPerBlob = SystemTime.getInstance().nanoseconds() - startTimeGetBlob;
            Exception exceptionToReturn = null;
            if (result != null) {
              try {
                GetResponse getResponse =
                    GetResponse.readFrom(new DataInputStream(new ByteBufferInputStream(result)), clusterMap);
                if (getResponse.getError() == ServerErrorCode.No_Error) {
                  ServerErrorCode serverErrorCode = getResponse.getPartitionResponseInfoList().get(0).getErrorCode();
                  if (serverErrorCode == ServerErrorCode.No_Error) {
                    blobData[0] = MessageFormatRecord.deserializeBlob(getResponse.getInputStream());
                    long sizeRead = 0;
                    byte[] outputBuffer = new byte[(int) blobData[0].getSize()];
                    ByteBufferOutputStream streamOut = new ByteBufferOutputStream(ByteBuffer.wrap(outputBuffer));
                    while (sizeRead < blobData[0].getSize()) {
                      streamOut.write(blobData[0].getStream().read());
                      sizeRead++;
                    }
                    boolean blobcontentMatched = Arrays.equals(blobContent, outputBuffer);
                    if (!blobcontentMatched) {
                      logger.error(requestMetadata.correlationId + " Blob content mismatch for " + blobIdStr + " from "
                          + hostName + ":" + port);
                    }
                  } else if (expectedErrorCode.equals(serverErrorCode)) {
                    logger.trace(
                        requestMetadata.correlationId + " Get of blob " + blobIdStr + " throws " + expectedErrorCode
                            + " as expected ");
                  } else {
                    logger.error(requestMetadata.correlationId + " Get of blob " + blobIdStr
                        + " failed with unexpected Store level error code " + serverErrorCode + ", expected "
                        + expectedErrorCode);
                    exceptionToReturn = new IllegalStateException(
                        requestMetadata.correlationId + " Get of " + blobIdStr + " throws " + serverErrorCode
                            + " different from what is expected  " + expectedErrorCode);
                  }
                } else {
                  logger.error(requestMetadata.correlationId + " Get of blob " + blobIdStr
                      + " failed with unexpected Server Level error code " + getResponse.getError() + ", expected "
                      + expectedErrorCode);
                  exceptionToReturn = new IllegalStateException(
                      requestMetadata.correlationId + " Get of " + blobIdStr + " throws " + getResponse.getError()
                          + " different from what is expected  " + expectedErrorCode);
                }
                logger.trace(requestMetadata.correlationId + " Time taken to get blob " + blobIdStr + " in ms "
                    + latencyPerBlob / SystemTime.NsPerMs);
              } catch (IOException e) {
                exceptionToReturn = e;
              } catch (MessageFormatException e) {
                exceptionToReturn = e;
              }
            } else {
              exceptionToReturn = exception;
            }
            requestMetadata.futureResult.done(null, exceptionToReturn);
            if (callback != null) {
              callback.onCompletion(null, exceptionToReturn);
            }
          }
        };
        requestInfoList.add(requestMetadata);
        networkClientUtils.poll(requestInfoList);
      } catch (Exception e) {
        logger.error(
            correlationId + " Unknown Exception thrown when getting blob " + blobId + ", " + e.getStackTrace());
        futureResult.done(null, e);
        if (callback != null) {
          callback.onCompletion(null, e);
        }
      }
    }

    /**
     * {@inheritDoc}
     * Request to delete the blob from the server directly pertaining to the {@code blobId} passed in and verify that
     * the subsequent get fails with {@link ServerErrorCode#Blob_Deleted}
     * @param blobId the blobId the of that blob that needs to be fetched
     * @param expectedErrorCode the expected error code for the Delete call
     * @param futureResult the {@link FutureResult} where the result/exception will be set on completion
     * @param callback the {@link Callback} that will be called on completion
     */
    @Override
    public void deleteBlobAndValidate(final String blobId, final ServerErrorCode expectedErrorCode,
        final FutureResult futureResult, final Callback callback) {
      int correlationId = -1;
      try {
        correlationId = correlationIdGenerator.incrementAndGet();
        DeleteRequest deleteRequest =
            new DeleteRequest(correlationId, "ConcurrencyTest", new BlobId(blobId, clusterMap));
        final Long startTimeDeleteBlob = SystemTime.getInstance().nanoseconds();
        List<NetworkClientUtils.RequestMetadata> requestMetadataList = new ArrayList<>();
        final NetworkClientUtils.RequestMetadata requestMetadata = new NetworkClientUtils.RequestMetadata<>();
        requestMetadata.requestInfo = new RequestInfo(hostName, new Port(port, PortType.PLAINTEXT), deleteRequest);
        requestMetadata.futureResult = new FutureResult<ByteBuffer>();
        requestMetadata.correlationId = correlationId;
        requestMetadata.callback = new Callback<ByteBuffer>() {
          @Override
          public void onCompletion(ByteBuffer result, Exception exception) {
            long latencyPerBlob = SystemTime.getInstance().nanoseconds() - startTimeDeleteBlob;
            logger.trace(
                requestMetadata.correlationId + " Delete of  " + blobId + " took " + latencyPerBlob / SystemTime.NsPerMs
                    + " ms");
            Exception exceptionToReturn = null;
            if (result != null) {
              try {
                DeleteResponse deleteResponse =
                    DeleteResponse.readFrom(new DataInputStream(new ByteBufferInputStream(result)));
                if (deleteResponse.getError() != ServerErrorCode.No_Error) {
                  logger.error(requestMetadata.correlationId + " Deletion of " + blobId + " failed with an exception "
                      + deleteResponse.getError());
                  exceptionToReturn = new IllegalStateException(
                      requestMetadata.correlationId + " Deletion of " + blobId + " failed with an exception "
                          + deleteResponse.getError());
                } else {
                  logger.trace(requestMetadata.correlationId + " Deletion of " + blobId + " succeeded ");
                  FutureResult futureResultForGet = new FutureResult();
                  Callback callbackForGet = new Callback() {
                    @Override
                    public void onCompletion(Object result, Exception exception) {
                      futureResult.done(null, exception);
                      if (callback != null) {
                        callback.onCompletion(null, exception);
                      }
                    }
                  };
                  getBlobAndValidate(blobId, null, ServerErrorCode.Blob_Deleted, futureResultForGet, callbackForGet);
                }
              } catch (IOException e) {
                exceptionToReturn = e;
              }
            } else {
              exceptionToReturn = exception;
            }
            futureResult.done(null, exceptionToReturn);
            if (callback != null) {
              callback.onCompletion(null, exceptionToReturn);
            }
          }
        };
        requestMetadataList.add(requestMetadata);
        networkClientUtils.poll(requestMetadataList);
      } catch (Exception e) {
        logger.error(correlationId + " Unknown Exception thrown when deleting " + blobId + ", " + e.getStackTrace());
        futureResult.done(null, e);
        if (callback != null) {
          callback.onCompletion(null, e);
        }
      }
    }

    /**
     * {@inheritDoc}
     * Returns the default Error code that is expected for a Get call or a delete call
     * @return the default error code that is expected for a Get call or a delete call
     */
    @Override
    public ServerErrorCode getErrorCodeForNoError() {
      return ServerErrorCode.No_Error;
    }

    /**
     * {@inheritDoc}
     * Request to clean up resources used by this helper class, i.e. NetworkClient in this context
     */
    @Override
    public void shutdown()
        throws InterruptedException {
      networkClientUtils.close();
    }
  }

  private static class InvocationOptions {
    public final String hardwareLayoutFilePath;
    public final String partitionLayoutFilePath;
    public final String routerPropsFilePath;
    public final int maxParallelPutCount;
    public final int parallelGetCount;
    public final int burstCountForGet;
    public final int totalPutBlobCount;
    public final int maxGetCountPerBlob;
    public final int maxBlobSizeInBytes;
    public final int minBlobSizeInBytes;
    public final int maxFailuresPerPutBatchToStopPuts;
    public final int maxFailuresPerGetBatchToStopGets;
    public final long sleepTimeBetweenBatchPutsInMs;
    public final long sleepTimeBetweenBatchGetsInMs;

    public final String hostName;
    public final int port;
    public final String putGetHelperFactoryStr;
    public final boolean deleteOnExit;
    public final boolean enabledVerboseLogging;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Parses the arguments provided and extracts them into variables that can be retrieved through APIs.
     * @param args the command line argument list.
     * @throws InstantiationException if all required arguments were not provided.
     * @throws IOException if help text could not be printed.
     */
    public InvocationOptions(String args[])
        throws InstantiationException, IOException {
      OptionParser parser = new OptionParser();
      ArgumentAcceptingOptionSpec<String> hardwareLayoutFilePath =
          parser.accepts("hardwareLayoutFilePath", "Path to hardware layout file")
              .withRequiredArg()
              .describedAs("hardwareLayoutFilePath")
              .ofType(String.class);
      ArgumentAcceptingOptionSpec<String> partitionLayoutFilePath =
          parser.accepts("partitionLayoutFilePath", "Path to partition layout file")
              .withRequiredArg()
              .describedAs("partitionLayoutFilePath")
              .ofType(String.class);
      ArgumentAcceptingOptionSpec<String> routerPropsFilePathOpt =
          parser.accepts("routerPropsFilePath", "Path to router properties file")
              .withRequiredArg()
              .describedAs("routerPropsFilePath")
              .ofType(String.class);
      ArgumentAcceptingOptionSpec<String> putGetHelperFactoryOpt =
          parser.accepts("putGetHelperFactory", "The reference to PutGetHelperFactory class")
              .withOptionalArg()
              .defaultsTo("com.github.ambry.tools.admin.RouterPutGetHelperFactory")
              .ofType(String.class);
      ArgumentAcceptingOptionSpec<Integer> maxParallelPutCountOpt =
          parser.accepts("maxParallelPutCount", "Maximum number of parallel puts")
              .withOptionalArg()
              .describedAs("maxParallelPutCount")
              .ofType(Integer.class)
              .defaultsTo(1);
      ArgumentAcceptingOptionSpec<Integer> parallelGetCountOpt =
          parser.accepts("parallelGetCount", "Total number of parallel gets")
              .withOptionalArg()
              .describedAs("parallelGetCount")
              .ofType(Integer.class)
              .defaultsTo(1);
      ArgumentAcceptingOptionSpec<Integer> burstCountForGetOpt =
          parser.accepts("burstCountForGet", "Total number of burst gets per blob")
              .withOptionalArg()
              .describedAs("burstCountForGet")
              .ofType(Integer.class)
              .defaultsTo(1);
      ArgumentAcceptingOptionSpec<Integer> totalPutBlobCountOpt =
          parser.accepts("totalPutBlobCount", "Total number of blobs to be produced")
              .withOptionalArg()
              .describedAs("totalPutBlobCount")
              .ofType(Integer.class)
              .defaultsTo(10);
      ArgumentAcceptingOptionSpec<Integer> maxGetCountPerBlobOpt =
          parser.accepts("maxGetCountPerBlob", "Max number of times a blob has to be fetched and verified")
              .withOptionalArg()
              .describedAs("maxGetCountPerBlob")
              .ofType(Integer.class)
              .defaultsTo(1);
      ArgumentAcceptingOptionSpec<Integer> maxBlobSizeInBytesOpt =
          parser.accepts("maxBlobSizeInBytes", "Maximum size of blobs to be uploaded")
              .withOptionalArg()
              .describedAs("maxBlobSizeInBytes")
              .ofType(Integer.class)
              .defaultsTo(100);
      ArgumentAcceptingOptionSpec<Integer> minBlobSizeInBytesOpt =
          parser.accepts("minBlobSizeInBytes", "Minimum size of blobs to be uploaded")
              .withOptionalArg()
              .describedAs("minBlobSizeInBytes")
              .ofType(Integer.class)
              .defaultsTo(1);
      ArgumentAcceptingOptionSpec<Integer> maxFailuresPerPutBatchToStopPutsOpt =
          parser.accepts("maxFailuresPerPutBatchToStopPuts", "Maximum failures per Put batch to stop PUTs")
              .withOptionalArg()
              .describedAs("maxFailuresPerPutBatchToStopPuts")
              .ofType(Integer.class)
              .defaultsTo(2);
      ArgumentAcceptingOptionSpec<Integer> maxFailuresPerGetBatchToStopGetsOpt =
          parser.accepts("maxFailuresPerGetBatchToStopGets", "Maximum failures per Get batch to stop GETs")
              .withOptionalArg()
              .describedAs("maxFailuresPerGetBatchToStopGets")
              .ofType(Integer.class)
              .defaultsTo(2);
      ArgumentAcceptingOptionSpec<Integer> sleepTimeBetweenBatchPutsInMsOpt =
          parser.accepts("sleepTimeBetweenBatchPutsInMs", "Time to sleep in ms between batch puts")
              .withOptionalArg()
              .describedAs("sleepTimeBetweenBatchPutsInMs")
              .ofType(Integer.class)
              .defaultsTo(50);
      ArgumentAcceptingOptionSpec<Integer> sleepTimeBetweenBatchGetsInMsOpt =
          parser.accepts("sleepTimeBetweenBatchGetsInMs", "Time to sleep in ms between batch Gets")
              .withOptionalArg()
              .describedAs("sleepTimeBetweenBatchGetsInMs")
              .ofType(Integer.class)
              .defaultsTo(100);
      ArgumentAcceptingOptionSpec<String> hostNameOpt =
          parser.accepts("hostName", "The hostname against which requests are to be made")
              .withOptionalArg()
              .describedAs("hostName")
              .defaultsTo("localhost")
              .ofType(String.class);
      ArgumentAcceptingOptionSpec<Integer> portNumberOpt =
          parser.accepts("port", "The port number to be used while contacing the host")
              .withOptionalArg()
              .describedAs("port")
              .ofType(Integer.class)
              .defaultsTo(6667);
      ArgumentAcceptingOptionSpec<Boolean> deleteOnExitOpt =
          parser.accepts("deleteOnExit", "Delete blobs on exiting the test")
              .withOptionalArg()
              .describedAs("deleteOnExit")
              .ofType(Boolean.class)
              .defaultsTo(true);
      ArgumentAcceptingOptionSpec<Boolean> enableVerboseLoggingOpt =
          parser.accepts("enableVerboseLogging", "Enables verbose logging if set to true")
              .withOptionalArg()
              .describedAs("enableVerboseLogging")
              .ofType(Boolean.class)
              .defaultsTo(false);

      ArrayList<OptionSpec<?>> requiredArgs = new ArrayList<>();
      requiredArgs.add(hardwareLayoutFilePath);
      requiredArgs.add(partitionLayoutFilePath);

      OptionSet options = parser.parse(args);
      if (hasRequiredOptions(requiredArgs, options)) {
        this.hardwareLayoutFilePath = options.valueOf(hardwareLayoutFilePath);
        logger.trace("Hardware layout file path: {}", this.hardwareLayoutFilePath);
        this.partitionLayoutFilePath = options.valueOf(partitionLayoutFilePath);
        logger.trace("Partition layout file path: {}", this.partitionLayoutFilePath);
      } else {
        parser.printHelpOn(System.err);
        throw new InstantiationException("Did not receive all required arguments for starting RestServer");
      }
      this.routerPropsFilePath = options.valueOf(routerPropsFilePathOpt);
      this.maxParallelPutCount = options.valueOf(maxParallelPutCountOpt);
      this.parallelGetCount = options.valueOf(parallelGetCountOpt);
      this.burstCountForGet = options.valueOf(burstCountForGetOpt);
      this.totalPutBlobCount = options.valueOf(totalPutBlobCountOpt);
      this.maxGetCountPerBlob = options.valueOf(maxGetCountPerBlobOpt);
      this.maxBlobSizeInBytes = options.valueOf(maxBlobSizeInBytesOpt);
      this.minBlobSizeInBytes = options.valueOf(minBlobSizeInBytesOpt);
      this.maxFailuresPerPutBatchToStopPuts = options.valueOf(maxFailuresPerPutBatchToStopPutsOpt);
      this.maxFailuresPerGetBatchToStopGets = options.valueOf(maxFailuresPerGetBatchToStopGetsOpt);
      this.sleepTimeBetweenBatchPutsInMs = options.valueOf(sleepTimeBetweenBatchPutsInMsOpt);
      this.sleepTimeBetweenBatchGetsInMs = options.valueOf(sleepTimeBetweenBatchGetsInMsOpt);
      this.hostName = options.valueOf(hostNameOpt);
      this.port = options.valueOf(portNumberOpt);
      this.putGetHelperFactoryStr = options.valueOf(putGetHelperFactoryOpt);
      this.deleteOnExit = options.valueOf(deleteOnExitOpt);
      this.enabledVerboseLogging = options.valueOf(enableVerboseLoggingOpt);
    }

    /**
     * Checks if all required arguments are present. Prints the ones that are not.
     * @param requiredArgs the list of required arguments.
     * @param options the list of received options.
     * @return whether required options are present.
     */
    private boolean hasRequiredOptions(ArrayList<OptionSpec<?>> requiredArgs, OptionSet options) {
      boolean haveAll = true;
      for (OptionSpec opt : requiredArgs) {
        if (!options.has(opt)) {
          System.err.println("Missing required argument " + opt);
          haveAll = false;
        }
      }
      return haveAll;
    }
  }
}
