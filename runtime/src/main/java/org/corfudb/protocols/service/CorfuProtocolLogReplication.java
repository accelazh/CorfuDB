package org.corfudb.protocols.service;

import org.corfudb.runtime.LogReplication.LogReplicationLeadershipLossResponseMsg;
import org.corfudb.runtime.proto.service.CorfuMessage;

public class CorfuProtocolLogReplication {
    // Prevent class from being instantiated
    private CorfuProtocolLogReplication() {}

    public static CorfuMessage.ResponsePayloadMsg getLeadershipLoss(String endpoint) {
        return CorfuMessage.ResponsePayloadMsg.newBuilder().setLrLeadershipLoss(
                LogReplicationLeadershipLossResponseMsg
                        .newBuilder()
                        .setEndpoint(endpoint)
                        .build()
        ).build();
    }


}
