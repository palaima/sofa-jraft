/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft.storage;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import com.alipay.sofa.jraft.FSMCaller;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.closure.LoadSnapshotClosure;
import com.alipay.sofa.jraft.closure.SaveSnapshotClosure;
import com.alipay.sofa.jraft.closure.SynchronizedClosure;
import com.alipay.sofa.jraft.core.DefaultJRaftServiceFactory;
import com.alipay.sofa.jraft.core.NodeImpl;
import com.alipay.sofa.jraft.core.TimerManager;
import com.alipay.sofa.jraft.entity.LocalFileMetaOutter;
import com.alipay.sofa.jraft.entity.RaftOutter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.option.CopyOptions;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.option.SnapshotExecutorOptions;
import com.alipay.sofa.jraft.rpc.RaftClientService;
import com.alipay.sofa.jraft.rpc.RpcContext;
import com.alipay.sofa.jraft.rpc.RpcRequestClosure;
import com.alipay.sofa.jraft.rpc.RpcRequests;
import com.alipay.sofa.jraft.rpc.RpcResponseClosure;
import com.alipay.sofa.jraft.rpc.impl.FutureImpl;
import com.alipay.sofa.jraft.storage.snapshot.Snapshot;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotExecutorImpl;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.local.LocalSnapshotMetaTable;
import com.alipay.sofa.jraft.storage.snapshot.local.LocalSnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.local.LocalSnapshotStorage;
import com.alipay.sofa.jraft.storage.snapshot.local.LocalSnapshotWriter;
import com.alipay.sofa.jraft.test.MockAsyncContext;
import com.alipay.sofa.jraft.test.TestUtils;
import com.alipay.sofa.jraft.util.Endpoint;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;

@RunWith(value = MockitoJUnitRunner.class)
public class SnapshotExecutorTest extends BaseStorageTest {
    private static final String    GROUP_ID = "group001";
    private SnapshotExecutorImpl   executor;
    @Mock
    private NodeImpl               node;
    @Mock
    private FSMCaller              fSMCaller;
    @Mock
    private LogManager             logManager;
    private Endpoint               addr;
    @Mock
    private RpcContext             asyncCtx;

    @Mock
    private RaftClientService      raftClientService;
    private String                 uri;
    private final String           hostPort = "localhost:8081";
    private final int              readerId = 99;
    private CopyOptions            copyOpts;
    private LocalSnapshotMetaTable table;
    private LocalSnapshotWriter    writer;
    private LocalSnapshotReader    reader;
    private RaftOptions            raftOptions;
    @Mock
    private LocalSnapshotStorage   snapshotStorage;
    private TimerManager           timerManager;

    @Override
    @Before
    public void setup() throws Exception {
        super.setup();
        this.timerManager = new TimerManager(5);
        this.raftOptions = new RaftOptions();
        this.writer = new LocalSnapshotWriter(this.path, this.snapshotStorage, this.raftOptions);
        this.reader = new LocalSnapshotReader(this.snapshotStorage, null, new Endpoint("localhost", 8081),
            this.raftOptions, this.path);

        Mockito.when(this.snapshotStorage.open()).thenReturn(this.reader);
        Mockito.when(this.snapshotStorage.create(true)).thenReturn(this.writer);

        this.table = new LocalSnapshotMetaTable(this.raftOptions);
        this.table.addFile("testFile", LocalFileMetaOutter.LocalFileMeta.newBuilder().setChecksum("test").build());
        this.table.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(1).setLastIncludedTerm(1).build());
        this.uri = "remote://" + this.hostPort + "/" + this.readerId;
        this.copyOpts = new CopyOptions();

        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setSnapshotUri(this.path);
        Mockito.when(this.node.getGroupId()).thenReturn(GROUP_ID);
        Mockito.when(this.node.getRaftOptions()).thenReturn(this.raftOptions);
        Mockito.when(this.node.getOptions()).thenReturn(nodeOptions);
        Mockito.when(this.node.getRpcService()).thenReturn(this.raftClientService);
        Mockito.when(this.node.getTimerManager()).thenReturn(this.timerManager);
        Mockito.when(this.node.getServiceFactory()).thenReturn(DefaultJRaftServiceFactory.newInstance());

        this.executor = new SnapshotExecutorImpl();
        final SnapshotExecutorOptions opts = new SnapshotExecutorOptions();
        opts.setFsmCaller(this.fSMCaller);
        opts.setInitTerm(0);
        opts.setNode(this.node);
        opts.setLogManager(this.logManager);

        this.addr = new Endpoint("localhost", 8081);
        opts.setAddr(this.addr);
        assertTrue(this.executor.init(opts));
    }

    @Override
    @After
    public void teardown() throws Exception {
        this.executor.shutdown();
        super.teardown();
        this.timerManager.shutdown();
    }

    @Test
    public void testRetryInstallSnapshot() throws Exception {
        final RpcRequests.InstallSnapshotRequest.Builder irb = RpcRequests.InstallSnapshotRequest.newBuilder();
        irb.setGroupId("test");
        irb.setPeerId(this.addr.toString());
        irb.setServerId("localhost:8080");
        irb.setUri("remote://localhost:8080/99");
        irb.setTerm(0);
        irb.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(1).setLastIncludedTerm(2));

        Mockito.when(this.raftClientService.connect(new Endpoint("localhost", 8080))).thenReturn(true);

        final FutureImpl<Message> future = new FutureImpl<>();
        final RpcRequests.GetFileRequest.Builder rb = RpcRequests.GetFileRequest.newBuilder().setReaderId(99)
            .setFilename(Snapshot.JRAFT_SNAPSHOT_META_FILE).setCount(Integer.MAX_VALUE).setOffset(0)
            .setReadPartly(true);

        //mock get metadata
        ArgumentCaptor<RpcResponseClosure> argument = ArgumentCaptor.forClass(RpcResponseClosure.class);

        final CountDownLatch retryLatch = new CountDownLatch(1);
        final CountDownLatch answerLatch = new CountDownLatch(1);
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(rb.build()),
                eq(this.copyOpts.getTimeoutMs()), argument.capture())).thenAnswer(new Answer<Future<Message>>() {
            AtomicInteger count = new AtomicInteger(0);

            @Override
            public Future<Message> answer(InvocationOnMock invocation) throws Throwable {
                if (count.incrementAndGet() == 1) {
                    retryLatch.countDown();
                    answerLatch.await();
                    Thread.sleep(1000);
                    return future;
                } else {
                    throw new IllegalStateException("shouldn't be called more than once");
                }
            }
        });

        final MockAsyncContext installContext = new MockAsyncContext();
        final MockAsyncContext retryInstallContext = new MockAsyncContext();
        TestUtils.runInThread(() -> SnapshotExecutorTest.this.executor.installSnapshot(irb.build(),
            RpcRequests.InstallSnapshotResponse.newBuilder(), new RpcRequestClosure(installContext)));

        Thread.sleep(500);
        retryLatch.await();
        TestUtils.runInThread(() -> {
            answerLatch.countDown();
            SnapshotExecutorTest.this.executor.installSnapshot(irb.build(),
                RpcRequests.InstallSnapshotResponse.newBuilder(), new RpcRequestClosure(retryInstallContext));
        });

        RpcResponseClosure<RpcRequests.GetFileResponse> closure = argument.getValue();
        final ByteBuffer metaBuf = this.table.saveToByteBufferAsRemote();
        closure.setResponse(RpcRequests.GetFileResponse.newBuilder().setReadSize(metaBuf.remaining()).setEof(true)
            .setData(ByteString.copyFrom(metaBuf)).build());

        //mock get file
        argument = ArgumentCaptor.forClass(RpcResponseClosure.class);
        rb.setFilename("testFile");
        rb.setCount(this.raftOptions.getMaxByteCountPerRpc());
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(rb.build()),
                eq(this.copyOpts.getTimeoutMs()), argument.capture())).thenReturn(future);

        closure.run(Status.OK());
        Thread.sleep(500);
        closure = argument.getValue();
        closure.setResponse(RpcRequests.GetFileResponse.newBuilder().setReadSize(100).setEof(true)
            .setData(ByteString.copyFrom(new byte[100])).build());

        final ArgumentCaptor<LoadSnapshotClosure> loadSnapshotArg = ArgumentCaptor.forClass(LoadSnapshotClosure.class);
        Mockito.when(this.fSMCaller.onSnapshotLoad(loadSnapshotArg.capture())).thenReturn(true);
        closure.run(Status.OK());
        Thread.sleep(2000);
        final LoadSnapshotClosure done = loadSnapshotArg.getValue();
        final SnapshotReader reader = done.start();
        assertNotNull(reader);
        assertEquals(1, reader.listFiles().size());
        assertTrue(reader.listFiles().contains("testFile"));
        done.run(Status.OK());
        this.executor.join();
        assertEquals(2, this.executor.getLastSnapshotTerm());
        assertEquals(1, this.executor.getLastSnapshotIndex());
        assertNotNull(installContext.getResponseObject());
        assertNotNull(retryInstallContext.getResponseObject());
        assertEquals(installContext.as(RpcRequests.ErrorResponse.class).getErrorCode(), RaftError.EINTR.getNumber());
        assertTrue(retryInstallContext.as(RpcRequests.InstallSnapshotResponse.class).hasSuccess());

    }

    @Test
    public void testInstallSnapshot() throws Exception {
        final RpcRequests.InstallSnapshotRequest.Builder irb = RpcRequests.InstallSnapshotRequest.newBuilder();
        irb.setGroupId("test");
        irb.setPeerId(this.addr.toString());
        irb.setServerId("localhost:8080");
        irb.setUri("remote://localhost:8080/99");
        irb.setTerm(0);
        irb.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(1).setLastIncludedTerm(2));

        Mockito.when(this.raftClientService.connect(new Endpoint("localhost", 8080))).thenReturn(true);

        final FutureImpl<Message> future = new FutureImpl<>();
        final RpcRequests.GetFileRequest.Builder rb = RpcRequests.GetFileRequest.newBuilder().setReaderId(99)
            .setFilename(Snapshot.JRAFT_SNAPSHOT_META_FILE).setCount(Integer.MAX_VALUE).setOffset(0)
            .setReadPartly(true);

        //mock get metadata
        ArgumentCaptor<RpcResponseClosure> argument = ArgumentCaptor.forClass(RpcResponseClosure.class);
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(rb.build()),
                eq(this.copyOpts.getTimeoutMs()), argument.capture())).thenReturn(future);
        TestUtils.runInThread(new Runnable() {

            @Override
            public void run() {
                SnapshotExecutorTest.this.executor.installSnapshot(irb.build(), RpcRequests.InstallSnapshotResponse
                    .newBuilder(), new RpcRequestClosure(SnapshotExecutorTest.this.asyncCtx));

            }
        });

        Thread.sleep(500);
        RpcResponseClosure<RpcRequests.GetFileResponse> closure = argument.getValue();
        final ByteBuffer metaBuf = this.table.saveToByteBufferAsRemote();
        closure.setResponse(RpcRequests.GetFileResponse.newBuilder().setReadSize(metaBuf.remaining()).setEof(true)
            .setData(ByteString.copyFrom(metaBuf)).build());

        //mock get file
        argument = ArgumentCaptor.forClass(RpcResponseClosure.class);
        rb.setFilename("testFile");
        rb.setCount(this.raftOptions.getMaxByteCountPerRpc());
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(rb.build()),
                eq(this.copyOpts.getTimeoutMs()), argument.capture())).thenReturn(future);

        closure.run(Status.OK());

        Thread.sleep(500);
        closure = argument.getValue();
        closure.setResponse(RpcRequests.GetFileResponse.newBuilder().setReadSize(100).setEof(true)
            .setData(ByteString.copyFrom(new byte[100])).build());

        final ArgumentCaptor<LoadSnapshotClosure> loadSnapshotArg = ArgumentCaptor.forClass(LoadSnapshotClosure.class);
        Mockito.when(this.fSMCaller.onSnapshotLoad(loadSnapshotArg.capture())).thenReturn(true);
        closure.run(Status.OK());
        Thread.sleep(500);
        final LoadSnapshotClosure done = loadSnapshotArg.getValue();
        final SnapshotReader reader = done.start();
        assertNotNull(reader);
        assertEquals(1, reader.listFiles().size());
        assertTrue(reader.listFiles().contains("testFile"));
        done.run(Status.OK());
        this.executor.join();
        assertEquals(2, this.executor.getLastSnapshotTerm());
        assertEquals(1, this.executor.getLastSnapshotIndex());
    }

    private void seedLocalSnapshot(final long index, final long term) throws Exception {
        final LocalSnapshotStorage seedStorage = new LocalSnapshotStorage(this.path, null, this.raftOptions);
        assertTrue(seedStorage.init(null));
        seedStorage.setServerAddr(new Endpoint("localhost", 8081));
        final LocalSnapshotWriter seedWriter = (LocalSnapshotWriter) seedStorage.create();
        assertNotNull(seedWriter);
        assertTrue(seedWriter.saveMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(index)
            .setLastIncludedTerm(term).build()));
        seedWriter.close();
        seedStorage.shutdown();
    }

    private void restartSeededExecutor() throws Exception {
        this.executor.shutdown();
        this.executor.join();
        Mockito.when(this.fSMCaller.onSnapshotLoad(Mockito.any(LoadSnapshotClosure.class))).thenAnswer(
            new Answer<Boolean>() {

                @Override
                public Boolean answer(final InvocationOnMock invocation) throws Throwable {
                    final LoadSnapshotClosure done = (LoadSnapshotClosure) invocation.getArguments()[0];
                    done.run(Status.OK());
                    return true;
                }
            });
        this.executor = new SnapshotExecutorImpl();
        final SnapshotExecutorOptions opts = new SnapshotExecutorOptions();
        opts.setFsmCaller(this.fSMCaller);
        opts.setInitTerm(0);
        opts.setNode(this.node);
        opts.setLogManager(this.logManager);
        opts.setAddr(this.addr);
        assertTrue(this.executor.init(opts));
    }

    private MockAsyncContext installRemoteSnapshot(final long index, final long term) throws Exception {
        final RpcRequests.InstallSnapshotRequest.Builder irb = RpcRequests.InstallSnapshotRequest.newBuilder();
        irb.setGroupId("test");
        irb.setPeerId(this.addr.toString());
        irb.setServerId("localhost:8080");
        irb.setUri("remote://localhost:8080/99");
        irb.setTerm(0);
        irb.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(index).setLastIncludedTerm(term));

        Mockito.when(this.raftClientService.connect(new Endpoint("localhost", 8080))).thenReturn(true);

        final FutureImpl<Message> future = new FutureImpl<>();
        final RpcRequests.GetFileRequest.Builder rb = RpcRequests.GetFileRequest.newBuilder().setReaderId(99)
            .setFilename(Snapshot.JRAFT_SNAPSHOT_META_FILE).setCount(Integer.MAX_VALUE).setOffset(0)
            .setReadPartly(true);
        final RpcRequests.GetFileRequest metaFileRequest = rb.build();
        rb.setFilename("testFile");
        rb.setCount(this.raftOptions.getMaxByteCountPerRpc());
        final RpcRequests.GetFileRequest dataFileRequest = rb.build();

        // Register BOTH stubs before the install thread starts: re-stubbing a mock
        // while another thread may be invoking it is a Mockito race, and the latch
        // handshake replaces the former sleep-then-getValue() flakes.
        final AtomicReference<RpcResponseClosure<RpcRequests.GetFileResponse>> metaClosureRef = new AtomicReference<>();
        final AtomicReference<RpcResponseClosure<RpcRequests.GetFileResponse>> dataClosureRef = new AtomicReference<>();
        final CountDownLatch metaLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(metaFileRequest),
                eq(this.copyOpts.getTimeoutMs()), Mockito.any(RpcResponseClosure.class))).thenAnswer(invocation -> {
            metaClosureRef.set((RpcResponseClosure<RpcRequests.GetFileResponse>) invocation.getArguments()[3]);
            metaLatch.countDown();
            return future;
        });
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(dataFileRequest),
                eq(this.copyOpts.getTimeoutMs()), Mockito.any(RpcResponseClosure.class))).thenAnswer(invocation -> {
            dataClosureRef.set((RpcResponseClosure<RpcRequests.GetFileResponse>) invocation.getArguments()[3]);
            dataLatch.countDown();
            return future;
        });

        final MockAsyncContext installContext = new MockAsyncContext();
        TestUtils.runInThread(() -> SnapshotExecutorTest.this.executor.installSnapshot(irb.build(),
            RpcRequests.InstallSnapshotResponse.newBuilder(), new RpcRequestClosure(installContext)));

        assertTrue("Timed out waiting for the meta file request", metaLatch.await(30, TimeUnit.SECONDS));
        RpcResponseClosure<RpcRequests.GetFileResponse> closure = metaClosureRef.get();
        final LocalSnapshotMetaTable installingMetaTable = new LocalSnapshotMetaTable(this.raftOptions);
        installingMetaTable.addFile("testFile",
            LocalFileMetaOutter.LocalFileMeta.newBuilder().setChecksum("test").build());
        installingMetaTable.setMeta(
            RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(index).setLastIncludedTerm(term).build());
        final ByteBuffer metaBuf = installingMetaTable.saveToByteBufferAsRemote();
        closure.setResponse(RpcRequests.GetFileResponse.newBuilder().setReadSize(metaBuf.remaining()).setEof(true)
            .setData(ByteString.copyFrom(metaBuf)).build());
        closure.run(Status.OK());

        assertTrue("Timed out waiting for the data file request", dataLatch.await(30, TimeUnit.SECONDS));
        closure = dataClosureRef.get();
        closure.setResponse(RpcRequests.GetFileResponse.newBuilder().setReadSize(100).setEof(true)
            .setData(ByteString.copyFrom(new byte[100])).build());
        closure.run(Status.OK());

        this.executor.join();
        return installContext;
    }

    @Test
    public void testInstallSnapshotLowerIndexDifferentTerm() throws Exception {
        // A lower leader snapshot with a different term can replace local branch state.
        seedLocalSnapshot(24, 11);
        restartSeededExecutor();
        assertEquals(24, this.executor.getLastSnapshotIndex());
        assertEquals(11, this.executor.getLastSnapshotTerm());

        final MockAsyncContext installContext = installRemoteSnapshot(20, 14);

        assertEquals(20, this.executor.getLastSnapshotIndex());
        assertEquals(14, this.executor.getLastSnapshotTerm());
        assertNotNull(installContext.getResponseObject());
        assertTrue(installContext.as(RpcRequests.InstallSnapshotResponse.class).getSuccess());
        final ArgumentCaptor<RaftOutter.SnapshotMeta> metaArg = ArgumentCaptor.forClass(RaftOutter.SnapshotMeta.class);
        Mockito.verify(this.logManager, Mockito.times(2)).setSnapshot(metaArg.capture());
        assertEquals(20, metaArg.getValue().getLastIncludedIndex());
        assertEquals(14, metaArg.getValue().getLastIncludedTerm());
        Mockito.verify(this.node).updateConfigurationAfterInstallingSnapshot(20L);
    }

    @Test
    public void testInstallSnapshotExactDuplicateIsNoOpSuccess() throws Exception {
        // Exact duplicate is the only safe no-op for a not-newer snapshot.
        seedLocalSnapshot(24, 11);
        restartSeededExecutor();
        assertEquals(24, this.executor.getLastSnapshotIndex());
        assertEquals(11, this.executor.getLastSnapshotTerm());

        final RpcRequests.InstallSnapshotRequest.Builder irb = RpcRequests.InstallSnapshotRequest.newBuilder();
        irb.setGroupId("test");
        irb.setPeerId(this.addr.toString());
        irb.setServerId("localhost:8080");
        irb.setUri("remote://localhost:8080/99");
        irb.setTerm(0);
        irb.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(24).setLastIncludedTerm(11));

        final MockAsyncContext installContext = new MockAsyncContext();
        this.executor.installSnapshot(irb.build(), RpcRequests.InstallSnapshotResponse.newBuilder(),
            new RpcRequestClosure(installContext));

        assertNotNull(installContext.getResponseObject());
        assertTrue(installContext.as(RpcRequests.InstallSnapshotResponse.class).getSuccess());
        assertEquals(24, this.executor.getLastSnapshotIndex());
        assertEquals(11, this.executor.getLastSnapshotTerm());
        Mockito.verify(this.raftClientService, Mockito.never()).getFile(Mockito.any(Endpoint.class),
            Mockito.any(RpcRequests.GetFileRequest.class), Mockito.anyInt(), Mockito.any(RpcResponseClosure.class));
    }

    @Test
    public void testInstallSnapshotSameIndexDifferentTermRejected() throws Exception {
        // A same-index/different-term snapshot cannot be installed safely: storage
        // keys snapshots by index only, so close() short-circuits with EEXISTS and
        // silently keeps the old branch's data while the executor would book the
        // leader's meta and reply success. Reject loudly instead of corrupting state.
        seedLocalSnapshot(24, 11);
        restartSeededExecutor();
        assertEquals(24, this.executor.getLastSnapshotIndex());
        assertEquals(11, this.executor.getLastSnapshotTerm());

        final RpcRequests.InstallSnapshotRequest.Builder irb = RpcRequests.InstallSnapshotRequest.newBuilder();
        irb.setGroupId("test");
        irb.setPeerId(this.addr.toString());
        irb.setServerId("localhost:8080");
        irb.setUri("remote://localhost:8080/99");
        irb.setTerm(0);
        irb.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(24).setLastIncludedTerm(12));

        final MockAsyncContext installContext = new MockAsyncContext();
        this.executor.installSnapshot(irb.build(), RpcRequests.InstallSnapshotResponse.newBuilder(),
            new RpcRequestClosure(installContext));

        assertNotNull(installContext.getResponseObject());
        assertFalse(installContext.as(RpcRequests.InstallSnapshotResponse.class).getSuccess());
        assertEquals(24, this.executor.getLastSnapshotIndex());
        assertEquals(11, this.executor.getLastSnapshotTerm());
        // No download may even start.
        Mockito.verify(this.raftClientService, Mockito.never()).getFile(Mockito.any(Endpoint.class),
            Mockito.any(RpcRequests.GetFileRequest.class), Mockito.anyInt(), Mockito.any(RpcResponseClosure.class));
        // Only the init-time load of the seeded snapshot — the remote one must never reach the FSM.
        Mockito.verify(this.fSMCaller, Mockito.times(1)).onSnapshotLoad(Mockito.any(LoadSnapshotClosure.class));
        // Only the init-time booking of the seeded meta — the leader's meta must not be recorded.
        final ArgumentCaptor<RaftOutter.SnapshotMeta> metaArg = ArgumentCaptor.forClass(RaftOutter.SnapshotMeta.class);
        Mockito.verify(this.logManager, Mockito.times(1)).setSnapshot(metaArg.capture());
        assertEquals(24, metaArg.getValue().getLastIncludedIndex());
        assertEquals(11, metaArg.getValue().getLastIncludedTerm());
        Mockito.verify(this.node, Mockito.times(1)).updateConfigurationAfterInstallingSnapshot(Mockito.anyLong());
    }

    @Test
    public void testInstallSnapshotLowerIndexSameTerm() throws Exception {
        // Same term does not prove the local snapshot is on the leader's branch.
        seedLocalSnapshot(254, 76);
        restartSeededExecutor();
        assertEquals(254, this.executor.getLastSnapshotIndex());
        assertEquals(76, this.executor.getLastSnapshotTerm());

        final MockAsyncContext installContext = installRemoteSnapshot(14, 76);

        assertEquals(14, this.executor.getLastSnapshotIndex());
        assertEquals(76, this.executor.getLastSnapshotTerm());
        assertNotNull(installContext.getResponseObject());
        assertTrue(installContext.as(RpcRequests.InstallSnapshotResponse.class).getSuccess());
        final ArgumentCaptor<RaftOutter.SnapshotMeta> metaArg = ArgumentCaptor.forClass(RaftOutter.SnapshotMeta.class);
        Mockito.verify(this.logManager, Mockito.times(2)).setSnapshot(metaArg.capture());
        assertEquals(14, metaArg.getValue().getLastIncludedIndex());
        assertEquals(76, metaArg.getValue().getLastIncludedTerm());
        Mockito.verify(this.node).updateConfigurationAfterInstallingSnapshot(14L);
    }

    @Test
    public void testInterruptInstallaling() throws Exception {
        final RpcRequests.InstallSnapshotRequest.Builder irb = RpcRequests.InstallSnapshotRequest.newBuilder();
        irb.setGroupId("test");
        irb.setPeerId(this.addr.toString());
        irb.setServerId("localhost:8080");
        irb.setUri("remote://localhost:8080/99");
        irb.setTerm(0);
        irb.setMeta(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(1).setLastIncludedTerm(1));

        Mockito.when(this.raftClientService.connect(new Endpoint("localhost", 8080))).thenReturn(true);

        final FutureImpl<Message> future = new FutureImpl<>();
        final RpcRequests.GetFileRequest.Builder rb = RpcRequests.GetFileRequest.newBuilder().setReaderId(99)
            .setFilename(Snapshot.JRAFT_SNAPSHOT_META_FILE).setCount(Integer.MAX_VALUE).setOffset(0)
            .setReadPartly(true);

        //mock get metadata
        final ArgumentCaptor<RpcResponseClosure> argument = ArgumentCaptor.forClass(RpcResponseClosure.class);
        Mockito.when(
            this.raftClientService.getFile(eq(new Endpoint("localhost", 8080)), eq(rb.build()),
                eq(this.copyOpts.getTimeoutMs()), argument.capture())).thenReturn(future);
        TestUtils.runInThread(new Runnable() {

            @Override
            public void run() {
                SnapshotExecutorTest.this.executor.installSnapshot(irb.build(), RpcRequests.InstallSnapshotResponse
                    .newBuilder(), new RpcRequestClosure(SnapshotExecutorTest.this.asyncCtx));

            }
        });

        this.executor.interruptDownloadingSnapshots(1);
        this.executor.join();
        assertEquals(0, this.executor.getLastSnapshotTerm());
        assertEquals(0, this.executor.getLastSnapshotIndex());
    }

    @Test
    public void testDoSnapshot() throws Exception {
        Mockito.when(this.fSMCaller.getLastAppliedIndex()).thenReturn(1L);
        final ArgumentCaptor<SaveSnapshotClosure> saveSnapshotClosureArg = ArgumentCaptor
            .forClass(SaveSnapshotClosure.class);
        Mockito.when(this.fSMCaller.onSnapshotSave(saveSnapshotClosureArg.capture())).thenReturn(true);
        final SynchronizedClosure done = new SynchronizedClosure();
        this.executor.doSnapshot(done);
        final SaveSnapshotClosure closure = saveSnapshotClosureArg.getValue();
        assertNotNull(closure);
        closure.start(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(2).setLastIncludedTerm(1).build());
        closure.run(Status.OK());
        done.await();
        this.executor.join();
        assertTrue(done.getStatus().isOk());
        assertEquals(1, this.executor.getLastSnapshotTerm());
        assertEquals(2, this.executor.getLastSnapshotIndex());
    }

    @Test
    public void testDoSnapshotSync() throws Exception {
        Mockito.when(this.fSMCaller.getLastAppliedIndex()).thenReturn(1L);
        final ArgumentCaptor<SaveSnapshotClosure> saveSnapshotClosureArg = ArgumentCaptor
            .forClass(SaveSnapshotClosure.class);
        Mockito.when(fSMCaller.isRunningOnFSMThread()).thenReturn(true);
        Mockito.doNothing().when(fSMCaller).onSnapshotSaveSync(saveSnapshotClosureArg.capture());
        final SynchronizedClosure done = new SynchronizedClosure();
        this.executor.doSnapshotSync(done);
        final SaveSnapshotClosure closure = saveSnapshotClosureArg.getValue();
        assertNotNull(closure);
        closure.start(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(2).setLastIncludedTerm(1).build());
        closure.run(Status.OK());
        done.await();
        this.executor.join();
        assertTrue(done.getStatus().isOk());
        assertEquals(1, this.executor.getLastSnapshotTerm());
        assertEquals(2, this.executor.getLastSnapshotIndex());
    }

    @Test(expected = IllegalStateException.class)
    public void testDoSnapshotSyncIllegalState() throws Exception {
        Mockito.when(this.fSMCaller.getLastAppliedIndex()).thenReturn(1L);
        final ArgumentCaptor<SaveSnapshotClosure> saveSnapshotClosureArg = ArgumentCaptor
            .forClass(SaveSnapshotClosure.class);
        Mockito.when(fSMCaller.isRunningOnFSMThread()).thenReturn(false);
        Mockito.doNothing().when(fSMCaller).onSnapshotSaveSync(saveSnapshotClosureArg.capture());
        final SynchronizedClosure done = new SynchronizedClosure();
        this.executor.doSnapshotSync(done);
    }

    @Test
    public void testNotDoSnapshotWithIntervalDist() throws Exception {
        final NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setSnapshotLogIndexMargin(10);
        Mockito.when(this.node.getOptions()).thenReturn(nodeOptions);
        Mockito.when(this.fSMCaller.getLastAppliedIndex()).thenReturn(1L);
        this.executor.doSnapshot(null);
        this.executor.join();

        assertEquals(0, this.executor.getLastSnapshotTerm());
        assertEquals(0, this.executor.getLastSnapshotIndex());

    }

    @Test
    public void testDoSnapshotWithIntervalDist() throws Exception {
        final NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setSnapshotLogIndexMargin(5);
        Mockito.when(this.node.getOptions()).thenReturn(nodeOptions);
        Mockito.when(this.fSMCaller.getLastAppliedIndex()).thenReturn(6L);

        final ArgumentCaptor<SaveSnapshotClosure> saveSnapshotClosureArg = ArgumentCaptor
            .forClass(SaveSnapshotClosure.class);
        Mockito.when(this.fSMCaller.onSnapshotSave(saveSnapshotClosureArg.capture())).thenReturn(true);
        final SynchronizedClosure done = new SynchronizedClosure();
        this.executor.doSnapshot(done);
        final SaveSnapshotClosure closure = saveSnapshotClosureArg.getValue();
        assertNotNull(closure);
        closure.start(RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(6).setLastIncludedTerm(1).build());
        closure.run(Status.OK());
        done.await();
        this.executor.join();

        assertEquals(1, this.executor.getLastSnapshotTerm());
        assertEquals(6, this.executor.getLastSnapshotIndex());

    }
}
