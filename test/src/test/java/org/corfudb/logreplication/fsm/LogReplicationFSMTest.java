package org.corfudb.logreplication.fsm;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.corfudb.common.compression.Codec;
import org.corfudb.logreplication.message.DataMessage;
import org.corfudb.logreplication.DataSender;
import org.corfudb.logreplication.transmit.DefaultReadProcessor;
import org.corfudb.logreplication.transmit.LogEntryReader;
import org.corfudb.logreplication.transmit.LogReplicationEventMetadata;
import org.corfudb.logreplication.transmit.SnapshotReader;
import org.corfudb.logreplication.transmit.SnapshotTransmitter;
import org.corfudb.logreplication.transmit.StreamsSnapshotReader;
import org.corfudb.protocols.wireprotocol.Token;
import org.corfudb.protocols.wireprotocol.TokenResponse;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.logreplication.fsm.LogReplicationEvent.LogReplicationEventType;
import org.corfudb.runtime.collections.CorfuTable;
import org.corfudb.runtime.view.AbstractViewTest;
import org.corfudb.runtime.view.Address;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Observable;
import java.util.Observer;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Log Replication FSM.
 */
public class LogReplicationFSMTest extends AbstractViewTest implements Observer {

    // Parameters for writes
    private static final int NUM_ENTRIES = 10;
    private static final int LARGE_NUM_ENTRIES = 100;
    private static final String PAYLOAD_FORMAT = "hello world %s";
    private static final String TEST_STREAM_NAME = "StreamA";
    private static final int BATCH_SIZE = 2;

    // This semaphore is used to block until the triggering event causes the transition to a new state
    private final Semaphore transitionAvailable = new Semaphore(1, true);
    // We observe the transition counter to know that a transition occurred.
    private ObservableValue transitionObservable;

    // Flag indicating if we should observer a snapshot sync, this is to interrupt it at any given stage
    private boolean observeSnapshotSync = false;
    private int limitSnapshotMessages = 0;
    private ObservableValue snapshotMessageCounterObservable;

    private LogReplicationFSM fsm;
    private CorfuRuntime runtime;
    private DataSender dataSender;
    private SnapshotReader snapshotReader;
    private LogEntryReader logEntryReader;
    private LogReplicationConfig logReplicationConfig;

    @Before
    public void setRuntime() {
        runtime = getDefaultRuntime();
        runtime.getParameters().setCodecType(Codec.Type.NONE.toString());

        initialize();
    }

    private void initialize() {
        // Test configuration shared with Readers and Listeners for testing purposes


        // The dummy implementation of our test snapshot/log entry reader does not look into streams,
        // it works at the address layer, for this reason we can provide an empty set as the streams to replicate.
        logReplicationConfig = new LogReplicationConfig(Collections.singleton(TEST_STREAM_NAME), UUID.randomUUID());
    }

    /**
     * Verify state machine behavior in the most simple (no error) path.
     *
     * This is the sequence of events triggered and expected state change:
     *
     * (1) None -> verify FSM initial state is INITIALIZED
     * (2) Replication Stop -> verify it stays in the INITIALIZED state as replication has not been started
     * (3) Replication Start -> IN_LOG_ENTRY_SYNC state
     * (4) Snapshot Sync Request -> IN_SNAPSHOT_SYNC state
     * (5) Snapshot Sync Complete -> IN_LOG_ENTRY_SYNC state
     * (6) Replication Stop -> back to INITIALIZED state
     *
     */
    @Test
    public void testLogReplicationFSMTransitions() throws Exception {

        initLogReplicationFSM(ReaderImplementation.EMPTY);

        // Initial state: Initialized
        LogReplicationState initState = fsm.getState();
        assertThat(initState.getType()).isEqualTo(LogReplicationStateType.INITIALIZED);

        transitionAvailable.acquire();

        // Transition #1: Replication Stop (without any replication having started)
        transition(LogReplicationEventType.REPLICATION_STOP, LogReplicationStateType.INITIALIZED);

        // Transition #2: Replication Start
        transition(LogReplicationEventType.REPLICATION_START, LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        // Transition #3: Snapshot Sync Request
        UUID snapshotSyncId = transition(LogReplicationEventType.SNAPSHOT_SYNC_REQUEST, LogReplicationStateType.IN_SNAPSHOT_SYNC);

        // Transition #4: Snapshot Sync Complete
        transition(LogReplicationEventType.SNAPSHOT_SYNC_COMPLETE, LogReplicationStateType.IN_LOG_ENTRY_SYNC, snapshotSyncId);

        // Transition #5: Stop Replication
        transition(LogReplicationEventType.REPLICATION_STOP, LogReplicationStateType.INITIALIZED);
    }

    /**
     * Test Trim Exception Events for Log Replication FSM
     *
     * This is the sequence of events triggered and expected state change:
     *
     * (1) Snapshot Sync Request => IN_SNAPSHOT_SYNC state
     * (2) Trimmed Exception (incorrect id) => IN_LOG_ENTRY_SYNC
     * (3) Trimmed Exception (for state in (5)) => IN_REQUIRE_SNAPSHOT_SYNC
     *
     * @throws Exception
     */
    @Test
    public void testTrimExceptionFSM() throws Exception {
        initLogReplicationFSM(ReaderImplementation.EMPTY);

        // Initial acquire of the semaphore, so the occurrence of the transition releases it for the transition itself.
        transitionAvailable.acquire();

        // Transition #1: Snapshot Sync Request
        transition(LogReplicationEventType.SNAPSHOT_SYNC_REQUEST, LogReplicationStateType.IN_SNAPSHOT_SYNC);

        // Since there is no data in Corfu, it will automatically COMPLETE and transition to IN_LOG_ENTRY_SYNC
        transitionAvailable.acquire();
        assertThat(fsm.getState().getType()).isEqualTo(LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        // TODO: CHANGE TRIMMED EXCEPTION, CANCEL_SYNC

        // Transition #2: Trimmed Exception on Log Entry Sync for an invalid event id, this should not be taken as
        // a valid trimmed exception for the current state, hence it remains in the same state
        transition(LogReplicationEventType.SYNC_CANCEL, LogReplicationStateType.IN_LOG_ENTRY_SYNC, UUID.randomUUID());

        // Transition #3: Trimmed Exception
        // Because this is an internal state, we need to capture the actual event id internally generated
        UUID logEntrySyncID = fsm.getStates().get(LogReplicationStateType.IN_LOG_ENTRY_SYNC).getTransitionEventId();
        transition(LogReplicationEventType.SYNC_CANCEL, LogReplicationStateType.IN_REQUIRE_SNAPSHOT_SYNC, logEntrySyncID);
    }


    /**
     * Test SnapshotTransmitter through dummy implementations of the SnapshotReader and DataSender.
     *
     * (1) Initiate the Log Replication State Machine (which defaults to the INITIALIZED State)
     * (2) Write NUM_ENTRIES to the database in a consecutive address space for a given stream.
     * (3) Enforce event to initialize SNAPSHOT_SYNC.
     * (4) When SNAPSHOT_SYNC is completed the FSM should transition to a new state IN_LOG_ENTRY_SYNC. Block until
     * this transition occurs.
     * (5) Once this transition occurs verify that the Listener has received the same data written in (2).
     *
     * In this test we assume the SnapshotReader reads all NUM_ENTRIES in a single call (no batching)
     *
     * @throws Exception
     */
    @Test
    public void testLogReplicationSnapshotTransmitterNoBatch() throws Exception {
        testSnapshotTransmitter(NUM_ENTRIES);
    }

    /**
     * Test SnapshotTransmitter through dummy implementations of the SnapshotReader and DataSender.
     *
     * (1) Initiate the Log Replication State Machine (which defaults to the INITIALIZED State)
     * (2) Write NUM_ENTRIES to the database in a consecutive address space for a given stream.
     * (3) Enforce event to initialize SNAPSHOT_SYNC.
     * (4) When SNAPSHOT_SYNC is completed the FSM should transition to a new state IN_LOG_ENTRY_SYNC. Block until
     * this transition occurs.
     * (5) Once this transition occurs verify that the Listener has received the same data written in (2).
     *
     * In this test we assume the SnapshotReader reads NUM_ENTRIES in batches, and confirm all NUM_ENTRIES are
     * received by the listener.
     *
     * @throws Exception
     */
    @Test
    public void testLogReplicationSnapshotTransmitterBatch() throws Exception {
        testSnapshotTransmitter(BATCH_SIZE);
    }

    private void testSnapshotTransmitter(int batchSize) throws Exception {

        // Initialize State Machine
        initLogReplicationFSM(ReaderImplementation.TEST);

        // Modify test configuration to the specified batch size
        ((TestSnapshotReader)snapshotReader).setBatchSize(batchSize);

        // Write NUM_ENTRIES to streamA
        writeToStream();

        // Initial acquire of semaphore, the transition method will block until a transition occurs
        transitionAvailable.acquire();

        // Transition #1: Replication Start
        transition(LogReplicationEventType.REPLICATION_START, LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        // Transition #2: Snapshot Sync Request
        transition(LogReplicationEventType.SNAPSHOT_SYNC_REQUEST, LogReplicationStateType.IN_SNAPSHOT_SYNC);

        // Block until the snapshot sync completes and next transition occurs.
        // The transition should happen to IN_LOG_ENTRY_SYNC state.
        transitionAvailable.acquire();
        assertThat(fsm.getState().getType()).isEqualTo(LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        Queue<DataMessage> listenerQueue = ((TestDataSender) dataSender).getSnapshotQueue();

        assertThat(listenerQueue.size()).isEqualTo(NUM_ENTRIES);

        for (int i=0; i<NUM_ENTRIES; i++) {
            assertThat(listenerQueue.poll().getData())
                    .isEqualTo( String.format("hello world %s", i).getBytes());
        }
    }

    /**
     * This test verifies that canceling a snapshot sync which is in progress, takes log replication
     * back to the INITIALIZED state and that snapshot sync can be retried and completed successfully.
     *
     * (1) Write NUM_ENTRIES to StreamA
     * (2) Trigger start of SNAPSHOT_SYNC
     * (3) Interrupt/Stop snapshot sync when 2 messages have been sent to the remote site.
     * (4) Re-trigger SNAPSHOT_SYNC
     * (5) Check for completeness, i.e., that state has changed to IN_LOG_ENTRY_SYNC
     *
     * @throws Exception
     */
    @Test
    public void cancelSnapshotSyncInProgressAndRetry() throws Exception {
        // This test needs to observe the number of messages generated during snapshot sync to interrupt/stop it,
        // before it completes.
        observeSnapshotSync = true;

        // Initialize State Machine
        initLogReplicationFSM(ReaderImplementation.TEST);

        // Modify test configuration to the specified batch size (since we write NUM_ENTRIES = 10) and we send in
        // batches of BATCH_SIZE = 2, we will stop snapshot sync at 2 sent messages.
        ((TestSnapshotReader)snapshotReader).setBatchSize(BATCH_SIZE);
        limitSnapshotMessages = 2;

        // Write NUM_ENTRIES to streamA
        writeToStream();

        // Initial acquire of semaphore, the transition method will block until a transition occurs
        transitionAvailable.acquire();

        // Transition #1: Snapshot Sync Request
        transition(LogReplicationEventType.SNAPSHOT_SYNC_REQUEST, LogReplicationStateType.IN_SNAPSHOT_SYNC);

        // We observe the number of transmitted messages and force a REPLICATION_STOP, when 2 messages have been sent
        // so we verify the state moves to INITIALIZED again.
        transitionAvailable.acquire();

        assertThat(fsm.getState().getType()).isEqualTo(LogReplicationStateType.INITIALIZED);

        ((TestDataSender) dataSender).getSnapshotQueue().clear();

        // Stop observing number of messages in snapshot sync, so this time it completes
        observeSnapshotSync = false;

        // Transition #2: This time the snapshot sync completes
        transition(LogReplicationEventType.SNAPSHOT_SYNC_REQUEST, LogReplicationStateType.IN_SNAPSHOT_SYNC);

        transitionAvailable.acquire();
        assertThat(fsm.getState().getType()).isEqualTo(LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        Queue<DataMessage> listenerQueue = ((TestDataSender) dataSender).getSnapshotQueue();

        assertThat(listenerQueue.size()).isEqualTo(NUM_ENTRIES);

        for (int i=0; i<NUM_ENTRIES; i++) {
            assertThat(listenerQueue.poll().getData())
                    .isEqualTo( String.format("hello world %s", i).getBytes());
        }
    }


    /**
     * Test Snapshot Sync for Default Stream-based implementations.
     *
     * @throws Exception
     */
    @Test
    public void testSnapshotSyncStreamImplementation() throws Exception {

        // Initialize State Machine
        initLogReplicationFSM(ReaderImplementation.STREAMS);

        // Write LARGE_NUM_ENTRIES to streamA
        writeToMap();

        // Initial acquire of semaphore, the transition method will block until a transition occurs
        transitionAvailable.acquire();

        // Transition #1: Replication Start
        transition(LogReplicationEventType.REPLICATION_START, LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        // Transition #2: Snapshot Sync Request
        transition(LogReplicationEventType.SNAPSHOT_SYNC_REQUEST, LogReplicationStateType.IN_SNAPSHOT_SYNC);

        // Block until the snapshot sync completes and next transition occurs.
        // The transition should happen to IN_LOG_ENTRY_SYNC state.
        for (int i = 0; i<LARGE_NUM_ENTRIES/(StreamsSnapshotReader.MAX_NUM_SMR_ENTRY*SnapshotTransmitter.SNAPSHOT_BATCH_SIZE); i++) {
            transitionAvailable.acquire();
        }

        assertThat(fsm.getState().getType()).isEqualTo(LogReplicationStateType.IN_LOG_ENTRY_SYNC);

        Queue<DataMessage> listenerQueue = ((TestDataSender) dataSender).getSnapshotQueue();

        assertThat(listenerQueue.size()).isEqualTo(LARGE_NUM_ENTRIES/StreamsSnapshotReader.MAX_NUM_SMR_ENTRY);

        // Transactional puts into the stream (incremental updates)
        writeTxIncrementalUpdates();

        int incrementalUpdates = 0;

        while(incrementalUpdates < NUM_ENTRIES) {
           ((TestDataSender)dataSender).getLogEntryQueue().poll();
           incrementalUpdates++;
        }

        assertThat(incrementalUpdates).isEqualTo(NUM_ENTRIES);
    }

    private void writeTxIncrementalUpdates() {
        CorfuTable<String, String> map = runtime.getObjectsView()
                .build()
                .setStreamName(TEST_STREAM_NAME)
                .setTypeToken(new TypeToken<CorfuTable<String, String>>() {})
                .open();

        for(int i=0; i<NUM_ENTRIES; i++) {
            runtime.getObjectsView().TXBegin();
            map.put(String.valueOf(i), String.valueOf(i));
            runtime.getObjectsView().TXEnd();
        }
    }

    private void writeToStream() {
        UUID streamA = UUID.nameUUIDFromBytes(TEST_STREAM_NAME.getBytes());

        final long epoch = runtime.getLayoutView().getLayout().getEpoch();

        // Write
        long backpointer = Address.NO_BACKPOINTER;
        for (int i=0; i<NUM_ENTRIES; i++) {
            runtime.getAddressSpaceView().write(new TokenResponse(new Token(epoch, i),
                            Collections.singletonMap(streamA, backpointer)),
                    String.format(PAYLOAD_FORMAT, i).getBytes());
            backpointer = i;
        }

        // Read to verify data is there
        for (int i=0; i<NUM_ENTRIES; i++) {
            assertThat(runtime.getAddressSpaceView().read((long)i).getPayload(getRuntime()))
                    .isEqualTo( String.format(PAYLOAD_FORMAT, i).getBytes());
        }
    }

    private void writeToMap() {
        CorfuTable<String, String> map = runtime.getObjectsView()
                .build()
                .setStreamName(TEST_STREAM_NAME)
                .setTypeToken(new TypeToken<CorfuTable<String, String>>() {})
                .open();

        for (int i=0; i<LARGE_NUM_ENTRIES; i++) {
            map.put(String.valueOf(i), String.valueOf(i));
        }
    }

    /**
     * Initialize Log Replication FSM
     *
     * Use empty implementations for those cases where you want to verify the behavior of the state machine.
     *
     * @param readerImpl implementation to use for readers.
     */
    private void initLogReplicationFSM(ReaderImplementation readerImpl) {

        logEntryReader = new TestLogEntryReader();
        dataSender = new TestDataSender();


        switch(readerImpl) {
            case EMPTY:
                // Empty implementations of reader and listener - used for testing transitions
                snapshotReader = new EmptySnapshotReader();
                dataSender = new EmptyDataSender();
                break;
            case TEST:
                // Dummy implementations of reader and listener for testing
                // The reader queries the log for the config provided (stream name, number of entries)
                // The listener inserts what it receives into a queue
                TestReaderConfiguration testConfig = TestReaderConfiguration.builder()
                        .endpoint(getDefaultEndpoint())
                        .numEntries(NUM_ENTRIES)
                        .payloadFormat(PAYLOAD_FORMAT)
                        .streamName(TEST_STREAM_NAME)
                        .batchSize(BATCH_SIZE).build();

                snapshotReader = new TestSnapshotReader(testConfig);
                dataSender = new TestDataSender();
                break;
            case STREAMS:
                // Default implementation used for Log Replication (stream-based)
                LogReplicationConfig logReplicationConfig = new LogReplicationConfig(Collections.singleton(TEST_STREAM_NAME),
                        UUID.randomUUID());
                snapshotReader = new StreamsSnapshotReader(getNewRuntime(getDefaultNode()).connect(),
                        logReplicationConfig);
                dataSender = new TestDataSender();
                break;
            default:
                break;
        }

        fsm = new LogReplicationFSM(runtime, snapshotReader, dataSender, logEntryReader, new DefaultReadProcessor(runtime),
                Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("fsm-worker").build()));
        transitionObservable = fsm.getNumTransitions();
        transitionObservable.addObserver(this);

        if (observeSnapshotSync) {
            snapshotMessageCounterObservable = ((InSnapshotSyncState) fsm.getStates()
                    .get(LogReplicationStateType.IN_SNAPSHOT_SYNC)).getSnapshotTransmitter().getObservedCounter();
            snapshotMessageCounterObservable.addObserver(this);
        }
    }

    /**
     * It performs a transition, based on the given event type and asserts the FSM has moved to the expected state
     *
     * @param eventType log replication event.
     * @param expectedState expected state after transition is completed.
     * @param eventId identifier of the event.
     */
    private UUID transition(LogReplicationEventType eventType,
                            LogReplicationStateType expectedState,
                            UUID eventId)
            throws InterruptedException {

        LogReplicationEvent event;

        if (eventId != null) {
            // For testing we are enforcing internal events (like Trimmed Exception or Snapshot Complete),
            // for this reason we must set the id of the event that preceded it, so it corresponds to the same state.
            event = new LogReplicationEvent(eventType, new LogReplicationEventMetadata(eventId));
        } else {
            event = new LogReplicationEvent(eventType);
        }

        fsm.input(event);

        transitionAvailable.acquire();

        assertThat(fsm.getState().getType()).isEqualTo(expectedState);

        return event.getEventID();
    }

    /**
     * Insert an event of eventType into the Log Replication FSM and assert on the expected state.
     *
     * This method blocks until an actual transition occurs in the FSM.
     *
     * @param eventType the type of the event to input into the state machine
     * @param expectedState the expected state to transition to
     *
     * @return UUID of the input event
     *
     * @throws InterruptedException
     */
    private UUID transition(LogReplicationEventType eventType,
                            LogReplicationStateType expectedState) throws InterruptedException {
        return transition(eventType, expectedState, null);
    }

    /**
     * Observer callback, will be called on every transition of the log replication FSM.
     */
    @Override
    public void update(Observable obs, Object arg) {
        if (obs == transitionObservable)
        {
            transitionAvailable.release();
            System.out.println("Transition::#"  + transitionObservable.getValue() + "::" + fsm.getState().getType());
        } else if (obs == snapshotMessageCounterObservable) {
            if (limitSnapshotMessages == snapshotMessageCounterObservable.getValue() && observeSnapshotSync) {
                // If number of messages in snapshot reaches the expected value force termination of SNAPSHOT_SYNC
                fsm.input(new LogReplicationEvent(LogReplicationEventType.REPLICATION_STOP));
            }
        }
    }

    public enum ReaderImplementation {
        EMPTY,
        TEST,
        STREAMS
    }
}