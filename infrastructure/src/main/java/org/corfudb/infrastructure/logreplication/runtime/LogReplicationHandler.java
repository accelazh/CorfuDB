package org.corfudb.infrastructure.logreplication.runtime;

import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.clients.ClientMsgHandler;
import org.corfudb.runtime.clients.IClient;
import org.corfudb.runtime.clients.IClientRouter;
import org.corfudb.runtime.clients.IHandler;
import org.corfudb.runtime.clients.ResponseHandler;
import org.corfudb.runtime.proto.service.CorfuMessage.ResponseMsg;
import org.corfudb.runtime.proto.service.CorfuMessage.ResponsePayloadMsg.PayloadCase;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandles;
import java.util.UUID;


/**
 * A client to the Log Replication Server
 */
@Slf4j
public class LogReplicationHandler implements IClient, IHandler<LogReplicationClient> {

    @Setter
    @Getter
    private IClientRouter router;

    @Getter
    public ClientMsgHandler msgHandler = new ClientMsgHandler(this)
            .generateHandlers(MethodHandles.lookup(), this);

    /**
     * Handle an ACK from Log Replication server.
     *
     * @param response The ack message
     * @param ctx      The context the message was sent under
     * @param router   A reference to the router
     */
    @ResponseHandler(type = PayloadCase.LR_ENTRY_RESPONSE)
    private static Object handleLogReplicationAck(@Nonnull ResponseMsg response,
                                                  @Nonnull ChannelHandlerContext ctx,
                                                  @Nonnull IClientRouter router) {
        log.debug("Handle log replication ACK");
        return response.getPayload();
    }

    @ResponseHandler(type = PayloadCase.LR_METADATA_RESPONSE)
    private static Object handleLogReplicationMetadata(@Nonnull ResponseMsg response,
                                                       @Nonnull ChannelHandlerContext ctx,
                                                       @Nonnull IClientRouter router) {
        log.debug("Handle log replication Metadata Response");
        return response.getPayload();
    }

    @ResponseHandler(type = PayloadCase.LR_LEADERSHIP_RESPONSE)
    private static Object handleLogReplicationQueryLeadershipResponse(@Nonnull ResponseMsg response,
                                                                      @Nonnull ChannelHandlerContext ctx,
                                                                      @Nonnull IClientRouter router) {
        log.trace("Handle log replication query leadership response msg {}", response);
        return response.getPayload();
    }

    @ResponseHandler(type = PayloadCase.LR_LEADERSHIP_LOSS)
    private static Object handleLogReplicationLeadershipLoss(@Nonnull ResponseMsg response,
                                                             @Nonnull ChannelHandlerContext ctx,
                                                             @Nonnull IClientRouter router) {
        log.debug("Handle log replication leadership loss msg {}", response);
        return response.getPayload();
    }

    @Override
    public LogReplicationClient getClient(long epoch, UUID clusterID) {
        return new LogReplicationClient(router, epoch);
    }
}
