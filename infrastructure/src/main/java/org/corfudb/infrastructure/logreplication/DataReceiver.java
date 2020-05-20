package org.corfudb.infrastructure.logreplication;

import org.corfudb.protocols.wireprotocol.logreplication.LogReplicationEntry;

import java.util.List;

/**
 * This Interface comprises Data Path receive operations for both Source and Sink.
 */
public interface DataReceiver {

    LogReplicationEntry receive(LogReplicationEntry message);

    LogReplicationEntry receive(List<LogReplicationEntry> messages);
}