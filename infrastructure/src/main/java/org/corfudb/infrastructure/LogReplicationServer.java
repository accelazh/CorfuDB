package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.logreplication.LogReplicationConfig;
import org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationMetadataManager;
import org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationSinkManager;
import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.runtime.LogReplication;
import org.corfudb.runtime.LogReplication.LogReplicationEntryType;
import org.corfudb.runtime.proto.service.CorfuMessage;
import org.corfudb.runtime.proto.service.CorfuMessage.RequestMsg;
import org.corfudb.runtime.proto.service.CorfuMessage.RequestPayloadMsg.PayloadCase;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.corfudb.protocols.service.CorfuProtocolLogReplication.getLeadershipLoss;
import static org.corfudb.protocols.service.CorfuProtocolMessage.getResponseMsg;

/**
 * This class represents the Log Replication Server, which is
 * responsible of providing Log Replication across sites.
 *
 * The Log Replication Server, handles log replication entries--which
 * represent parts of a Snapshot (full) sync or a Log Entry (delta) sync
 * and also handles negotiation messages, which allows the Source Replicator
 * to get a view of the last synchronized point at the remote cluster.
 */
@Slf4j
public class LogReplicationServer extends AbstractServer {

    private final ServerContext serverContext;

    private final ExecutorService executor;

    @Getter
    private final LogReplicationMetadataManager metadataManager;

    @Getter
    private final LogReplicationSinkManager sinkManager;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);

    private final AtomicBoolean isActive = new AtomicBoolean(false);

    /**
     * RequestHandlerMethods for the LogReplication server
     */
    @Getter
    private final RequestHandlerMethods handlerMethods =
            RequestHandlerMethods.generateHandler(MethodHandles.lookup(), this);

    public LogReplicationServer(@Nonnull ServerContext context, @Nonnull  LogReplicationConfig logReplicationConfig,
                                @Nonnull LogReplicationMetadataManager metadataManager, String corfuEndpoint,
                                long topologyConfigId) {
        this.serverContext = context;
        this.metadataManager = metadataManager;
        this.sinkManager = new LogReplicationSinkManager(corfuEndpoint, logReplicationConfig, metadataManager, serverContext, topologyConfigId);

        this.executor = Executors.newFixedThreadPool(1,
                new ServerThreadFactory("LogReplicationServer-", new ServerThreadFactory.ExceptionHandler()));
    }

    /* ************ Override Methods ************ */

    @Override
    public boolean isServerReadyToHandleMsg(CorfuMsg msg) {
        return getState() == ServerState.READY;
    }

    @Override
    protected void processRequest(CorfuMsg msg, ChannelHandlerContext ctx, IServerRouter r) {
        executor.submit(() -> getHandler().handle(msg, ctx, r));
    }

    @Override
    protected void processRequest(RequestMsg req, ChannelHandlerContext ctx, IServerRouter r) {
        executor.submit(() -> getHandlerMethods().handle(req, ctx, r));
    }

    @Override
    public void shutdown() {
        super.shutdown();
        executor.shutdown();
    }

    /* ************ Server Handlers ************ */

    @RequestHandler(type = PayloadCase.LR_ENTRY_REQUEST)
    private void handleLrEntryRequest(@Nonnull RequestMsg request,
                                      @Nonnull ChannelHandlerContext ctx,
                                      @Nonnull IServerRouter router) {
        log.trace("Log Replication Entry received by Server.");

        if (isLeader(request, ctx, router)) {
            // Forward the received message to the Sink Manager for apply
            LogReplication.LogReplicationEntryMsg ack =
                    sinkManager.receive(request.getPayload().getLrEntryRequest());

            if (ack != null) {
                long ts = ack.getMetadata().getEntryType().equals(LogReplicationEntryType.LOG_ENTRY_REPLICATED) ?
                        ack.getMetadata().getTimestamp() : ack.getMetadata().getSnapshotTimestamp();
                log.info("Sending ACK {} on {} to Client ", ack.getMetadata(), ts);
                CorfuMessage.ResponsePayloadMsg payload = getLogEntryResponse(request);
                CorfuMessage.ResponseMsg response = getResponseMsg(request.getHeader(), payload);
                router.sendResponse(response, ctx);
            }
        } else {
            log.warn("Dropping log replication entry as this node is not the leader.");
        }
    }

    private CorfuMessage.ResponsePayloadMsg getLogEntryResponse(RequestMsg request) {
        LogReplication.LogReplicationEntryMsg payload = sinkManager.receive(
                request.getPayload().getLrEntryRequest());
        return CorfuMessage.ResponsePayloadMsg.newBuilder()
                .setLrEntryResponse(payload).build();
    }

    @RequestHandler(type = PayloadCase.LR_METADATA_REQUEST)
    private void handleMetadataRequest(@Nonnull RequestMsg request,
                                       @Nonnull ChannelHandlerContext ctx,
                                       @Nonnull IServerRouter router) {
        log.info("Log Replication Metadata Request received by Server.");

        if (isLeader(request, ctx, router)) {
            LogReplicationMetadataManager metadataMgr = sinkManager.getLogReplicationMetadataManager();
            CorfuMessage.ResponsePayloadMsg payload = getMetadataResponse(metadataMgr);
            log.info("Send Metadata response :: {}", payload);
            //r.sendResponse(msg, CorfuMsgType.LOG_REPLICATION_METADATA_RESPONSE.payloadMsg(response));
            CorfuMessage.ResponseMsg response = getResponseMsg(request.getHeader(), payload);
            router.sendResponse(response, ctx);


            // If a snapshot apply is pending, start (if not started already)
            if (isSnapshotApplyPending(metadataMgr) && !sinkManager.getOngoingApply().get()) {
                sinkManager.resumeSnapshotApply();
            }
        } else {
            log.warn("Dropping metadata request as this node is not the leader.");
        }
    }

    private CorfuMessage.ResponsePayloadMsg getMetadataResponse(LogReplicationMetadataManager metadataMgr) {
        // TODO (Xiaoqin Ma): That's 6 independent DB calls per one request.
        //  Can we do just one? Also, It does not look like we handle failures if one of them fails, for example.
        LogReplication.LogReplicationMetadataResponseMsg payload = LogReplication.LogReplicationMetadataResponseMsg.newBuilder()
                .setSiteConfigID(metadataMgr.getTopologyConfigId())
                .setVersion(metadataMgr.getVersion())
                .setSnapshotStart(metadataMgr.getLastStartedSnapshotTimestamp())
                .setSnapshotTransferred(metadataMgr.getLastTransferredSnapshotTimestamp())
                .setSnapshotApplied(metadataMgr.getLastAppliedSnapshotTimestamp())
                .setLastLogEntryTimestamp(metadataMgr.getLastProcessedLogEntryTimestamp()).build();

        return CorfuMessage.ResponsePayloadMsg.newBuilder()
                .setLrMetadataResponse(payload).build();
    }

    @RequestHandler(type = PayloadCase.LR_LEADERSHIP_REQUEST)
    private void handleLogReplicationQueryLeadership(@Nonnull RequestMsg request,
                                                     @Nonnull ChannelHandlerContext ctx,
                                                     @Nonnull IServerRouter router) {
        log.debug("Log Replication Query Leadership Request received by Server.");
        CorfuMessage.ResponsePayloadMsg payload = getLeadershipResponse(isLeader.get(), serverContext.getLocalEndpoint());
        CorfuMessage.ResponseMsg response = getResponseMsg(request.getHeader(), payload);
        router.sendResponse(response, ctx);
    }

    private CorfuMessage.ResponsePayloadMsg getLeadershipResponse(boolean isLeader, String endpoint) {
        log.debug("Send Log Replication Leadership Response isLeader={}, endpoint={}", isLeader, endpoint);
        LogReplication.LogReplicationLeadershipResponseMsg payload = LogReplication.LogReplicationLeadershipResponseMsg
                .newBuilder()
                .setEpoch(0)
                .setIsLeader(isLeader)
                .setEndpoint(endpoint).build();
        return CorfuMessage.ResponsePayloadMsg.newBuilder()
                .setLrLeadershipResponse(payload).build();
    }

    /* ************ Private / Utility Methods ************ */

    private boolean isSnapshotApplyPending(LogReplicationMetadataManager metadataMgr) {
        return (metadataMgr.getLastStartedSnapshotTimestamp() == metadataMgr.getLastTransferredSnapshotTimestamp()) &&
                metadataMgr.getLastTransferredSnapshotTimestamp() > metadataMgr.getLastAppliedSnapshotTimestamp();
    }

    /**
     * Verify if current node is still the lead receiving node.
     *
     * @return true, if leader node.
     *         false, otherwise.
     */
    private synchronized boolean isLeader(@Nonnull RequestMsg request,
                                          @Nonnull ChannelHandlerContext ctx,
                                          @Nonnull IServerRouter router) {
        // If the current cluster has switched to the active role (no longer the receiver) or it is no longer the leader,
        // skip message processing (drop received message) and nack on leadership (loss of leadership)
        // This will re-trigger leadership discovery on the sender.
        boolean lostLeadership = isActive.get() || !isLeader.get();

        if (lostLeadership) {
            log.warn("This node has changed, active={}, leader={}. Dropping message type={}, id={}", isActive.get(),
                    isLeader.get(), request.getPayload().getPayloadCase(), request.getHeader().getRequestId());
            CorfuMessage.ResponsePayloadMsg payload = getLeadershipLoss(serverContext.getLocalEndpoint());
            CorfuMessage.ResponseMsg response = getResponseMsg(request.getHeader(), payload);
            router.sendResponse(response, ctx);
        }

        return !lostLeadership;
    }


    /* ************ Public Methods ************ */

    public synchronized void setLeadership(boolean leader) {
        isLeader.set(leader);
    }

    public synchronized void setActive(boolean active) {
        isActive.set(active);
    }
}
