package org.corfudb.infrastructure.log.statetransfer;

import com.google.common.collect.ImmutableList;
import org.corfudb.common.result.Result;
import org.corfudb.infrastructure.log.StreamLog;
import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.CurrentTransferSegment;
import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.CurrentTransferSegmentStatus;
import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState;
import org.corfudb.infrastructure.log.statetransfer.batchprocessor.StateTransferBatchProcessorData;
import org.corfudb.infrastructure.log.statetransfer.batchprocessor.committedbatchprocessor.CommittedBatchProcessor;
import org.corfudb.infrastructure.log.statetransfer.batchprocessor.protocolbatchprocessor.ProtocolBatchProcessor;
import org.corfudb.infrastructure.log.statetransfer.streamprocessor.StreamProcessFailure;
import org.corfudb.runtime.clients.LogUnitClient;
import org.corfudb.runtime.view.AddressSpaceView;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.corfudb.infrastructure.log.statetransfer.Plan.Bundle;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.CommittedTransferData;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState.FAILED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState.NOT_TRANSFERRED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState.RESTORED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState.TRANSFERRED;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class StateTransferManagerTest {

    @Test
    void getUnknownAddressesInRange() {
        StreamLog streamLog = mock(StreamLog.class);
        Set<Long> retVal = LongStream.range(0L, 80L).boxed().collect(Collectors.toSet());

        doReturn(retVal)
                .when(streamLog)
                .getKnownAddressesInRange(0L, 100L);

        StateTransferManager stateTransferManager =
                new StateTransferManager(streamLog, 10);

        ImmutableList<Long> unknownAddressesInRange = stateTransferManager
                .getUnknownAddressesInRange(0L, 100L);

        ImmutableList<Long> expected =
                ImmutableList.copyOf(LongStream.range(80L, 101L)
                        .boxed().collect(Collectors.toList()));

        assertThat(unknownAddressesInRange).isEqualTo(expected);

    }

    private final CurrentTransferSegment createTransferSegment(
            long start,
            long end,
            Optional<CommittedTransferData> committedData,
            SegmentState segmentState,
            long totalTransferred,
            Optional<StreamProcessFailure> causeOfFailure
    ) {
        return new CurrentTransferSegment(start,
                end,
                CompletableFuture.completedFuture(
                        new CurrentTransferSegmentStatus
                                (segmentState, totalTransferred, causeOfFailure)),
                committedData);
    }

    @Test
    void handleTransfer() {
        // Any status besides NOT_TRANSFERRED should not be updated
        AddressSpaceView addressSpaceView = mock(AddressSpaceView.class);
        StreamLog streamLog = mock(StreamLog.class);
        Map<String, LogUnitClient> map = mock(Map.class);
        StateTransferBatchProcessorData batchProcessorData =
                new StateTransferBatchProcessorData(streamLog, addressSpaceView, map);
        StateTransferManager manager = new StateTransferManager(streamLog, 10);
        ImmutableList<CurrentTransferSegment> segments =
                ImmutableList.of(createTransferSegment(0L, 50L, Optional.empty(), TRANSFERRED, 51L, Optional.empty()),
                        createTransferSegment(51L, 99L, Optional.empty(), FAILED, 49L, Optional.empty()),
                        createTransferSegment(100L, 199L, Optional.empty(), RESTORED, 100L, Optional.empty()));

        List<CurrentTransferSegmentStatus> statusesExpected = segments.stream().map(segment -> segment.getStatus().join()).collect(Collectors.toList());
        List<Long> totalTransferredExpected = segments.stream().map(segment -> segment.getStatus().join().getTotalTransferred()).collect(Collectors.toList());
        List<SimpleEntry<Long, Long>> rangesExpected = segments.stream().map(segment -> new SimpleEntry<>(segment.getStartAddress(),
                segment.getEndAddress())).collect(Collectors.toList());

        ImmutableList<CurrentTransferSegment> currentTransferSegments =
                manager.handleTransfer(segments, batchProcessorData);

        List<CurrentTransferSegmentStatus> statuses = currentTransferSegments.stream().map(segment -> segment.getStatus().join()).collect(Collectors.toList());
        List<Long> totalTransferred = currentTransferSegments.stream().map(segment -> segment.getStatus().join().getTotalTransferred()).collect(Collectors.toList());
        List<SimpleEntry<Long, Long>> ranges = currentTransferSegments.stream().map(segment -> new SimpleEntry<>(segment.getStartAddress(),
                segment.getEndAddress())).collect(Collectors.toList());

        assertThat(statuses).isEqualTo(statusesExpected);
        assertThat(totalTransferred).isEqualTo(totalTransferredExpected);
        assertThat(ranges).isEqualTo(rangesExpected);
        StateTransferManager spy = spy(manager);

        // Segment is from 0L to 50L, all data present, segment is transferred
        CurrentTransferSegment transferSegment =
                createTransferSegment(0L, 50L, Optional.empty(), NOT_TRANSFERRED,
                        0L, Optional.empty());
        doReturn(ImmutableList.of()).when(spy).getUnknownAddressesInRange(0L, 50L);
        currentTransferSegments =
                spy.handleTransfer(ImmutableList.of(transferSegment), batchProcessorData);

        assertThat(currentTransferSegments.get(0).getStatus().join().getSegmentState())
                .isEqualTo(TRANSFERRED);
        assertThat(currentTransferSegments.get(0).getStatus().join().getTotalTransferred())
                .isEqualTo(51L);
        // Some data is not present
        ImmutableList<Long> unknownData =
                ImmutableList.copyOf(LongStream.range(25L, 51L).boxed().collect(Collectors.toList()));

        doReturn(unknownData).when(spy).getUnknownAddressesInRange(0L, 50L);

        StateTransferConfig config = StateTransferConfig.builder()
                .unknownAddresses(unknownData)
                .committedTransferData(Optional.empty())
                .batchSize(10)
                .batchProcessorData(batchProcessorData).build();

        doReturn(CompletableFuture.completedFuture(Result.ok(26L)))
                .when(spy).stateTransfer(config);

        currentTransferSegments =
                spy.handleTransfer(ImmutableList.of(transferSegment), batchProcessorData);

        assertThat(currentTransferSegments.get(0).getStatus().join().getSegmentState())
                .isEqualTo(TRANSFERRED);
    }


    @Test
    void createStatusBasedOnTransferResult() {
        StreamLog streamLog = mock(StreamLog.class);
        StateTransferManager stateTransferManager =
                new StateTransferManager(streamLog, 10);

        // Success
        Result<Long, StreamProcessFailure> result = Result.ok(200L);
        long totalNeeded = 200L;
        CurrentTransferSegmentStatus status =
                stateTransferManager.createStatusBasedOnTransferResult(result, totalNeeded);
        assertThat(status.getSegmentState()).isEqualTo(TRANSFERRED);
        assertThat(status.getTotalTransferred()).isEqualTo(totalNeeded);
        // Not all data present
        result = Result.ok(180L);
        status = stateTransferManager.createStatusBasedOnTransferResult(result, totalNeeded);
        assertThat(status.getSegmentState()).isEqualTo(FAILED);
        assertThat(status.getTotalTransferred()).isEqualTo(0L);
        // Failure
        result = Result.error(new StreamProcessFailure());
        status = stateTransferManager.createStatusBasedOnTransferResult(result, totalNeeded);
        assertThat(status.getSegmentState()).isEqualTo(FAILED);
        assertThat(status.getTotalTransferred()).isEqualTo(0L);

    }

    @Test
    void createStateTransferPlan() {

        List<Long> addresses = LongStream.range(0L, 100L).boxed().collect(Collectors.toList());
        int batchSize = 10;
        AddressSpaceView addressSpaceView = mock(AddressSpaceView.class);
        StreamLog streamLog = mock(StreamLog.class);
        Map<String, LogUnitClient> map = mock(Map.class);
        StateTransferBatchProcessorData batchProcessorData =
                new StateTransferBatchProcessorData(streamLog, addressSpaceView, map);

        //All via a replication protocol
        StateTransferConfig config = StateTransferConfig.builder()
                .batchSize(batchSize)
                .batchProcessorData(batchProcessorData)
                .unknownAddresses(addresses)
                .build();

        StateTransferManager stateTransferManager =
                new StateTransferManager(streamLog, batchSize);

        Plan stateTransferPlan = stateTransferManager.createStateTransferPlan(config);
        Bundle bundle = stateTransferPlan.getBundles().get(0);
        assertThat(bundle.getProcessor().getBatchProcessor())
                .isInstanceOf(ProtocolBatchProcessor.class);
        assertThat(bundle.getData().getAddresses()).isEqualTo(addresses);
        assertThat(bundle.getData().getDefaultBatchSize()).isEqualTo(batchSize);

        //All via a committed protocol
        CommittedTransferData committedTransferData =
                new CommittedTransferData(99L, Arrays.asList("A", "B", "C"));
        config = StateTransferConfig.builder()
                .batchSize(batchSize)
                .committedTransferData(Optional.of(committedTransferData))
                .batchProcessorData(batchProcessorData)
                .unknownAddresses(addresses)
                .build();

        stateTransferManager = new StateTransferManager(streamLog, batchSize);
        stateTransferPlan = stateTransferManager.createStateTransferPlan(config);
        assertThat(stateTransferPlan.getBundles().size()).isEqualTo(1);
        bundle = stateTransferPlan.getBundles().get(0);
        assertThat(bundle.getProcessor().getBatchProcessor())
                .isInstanceOf(CommittedBatchProcessor.class);
        assertThat(bundle.getData().getAddresses()).isEqualTo(addresses);
        List<String> availableServers = Arrays.asList("A", "B", "C");
        //Half and half
        committedTransferData =
                new CommittedTransferData(50L, availableServers);
        config = StateTransferConfig.builder()
                .batchSize(batchSize)
                .committedTransferData(Optional.of(committedTransferData))
                .batchProcessorData(batchProcessorData)
                .unknownAddresses(addresses)
                .build();
        stateTransferManager = new StateTransferManager(streamLog, batchSize);
        stateTransferPlan = stateTransferManager.createStateTransferPlan(config);
        assertThat(stateTransferPlan.getBundles().size()).isEqualTo(2);
        ImmutableList<Bundle> bundles = stateTransferPlan.getBundles();

        List<Long> committed = LongStream.range(0L, 50L + 1).boxed().collect(Collectors.toList());
        List<Long> nonCommitted = LongStream.range(51L, 100L).boxed().collect(Collectors.toList());

        Bundle bundle1 = bundles.get(0);
        Bundle bundle2 = bundles.get(1);
        assertThat(bundle1.getProcessor().getBatchProcessor()).isInstanceOf(ProtocolBatchProcessor
                .class);
        assertThat(bundle1.getData().getAddresses()).isEqualTo(nonCommitted);

        assertThat(bundle2.getProcessor().getBatchProcessor()).isInstanceOf(CommittedBatchProcessor
                .class);
        assertThat(bundle2.getData().getAddresses()).isEqualTo(committed);
        assertThat(bundle2.getData().getAvailableServers()).isPresent();
        assertThat(bundle2.getData().getAvailableServers().get()).isEqualTo(availableServers);

    }


    @Test
    void coalesceResults() {
        // List is empty
        StreamLog streamLog = mock(StreamLog.class);
        StateTransferManager manager = new StateTransferManager(streamLog, 10);
        CompletableFuture<Result<Long, StreamProcessFailure>> res =
                manager.coalesceResults(ImmutableList.of());
        assertThat(res.join().isError()).isTrue();
        // One error
        List<Result<Long, StreamProcessFailure>> list =
                ImmutableList.of(Result.error(new StreamProcessFailure()), Result.ok(100L));
        List<CompletableFuture<Result<Long, StreamProcessFailure>>> collect =
                list.stream().map(CompletableFuture::completedFuture).collect(Collectors.toList());
        res = manager.coalesceResults(collect);
        assertThat(res.join().isError()).isTrue();

        list = ImmutableList.of(Result.ok(100L), Result.ok(100L), Result.ok(100L), Result.ok(100L));
        collect = list.stream().map(CompletableFuture::completedFuture).collect(Collectors.toList());
        res = manager.coalesceResults(collect);
        assertThat(res.join().isValue()).isTrue();
        assertThat(res.join().get()).isEqualTo(400L);

    }
}