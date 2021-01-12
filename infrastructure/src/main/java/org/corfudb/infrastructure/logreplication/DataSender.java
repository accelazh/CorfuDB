package org.corfudb.infrastructure.logreplication;

import org.corfudb.infrastructure.logreplication.replication.send.LogReplicationError;
import org.corfudb.runtime.LogReplication;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This Interface comprises Data Path send operations for both Source and Sink.
 *
 * Application is expected to transmit messages from source to sink, and ACKs
 * from sink to source.
 */
public interface DataSender {

    /**
     * Application callback on next available message for transmission to remote cluster.
     *
     * @param message LogReplicationEntry representing the data to send across sites.
     * @return
     */
    CompletableFuture<LogReplication.LogReplicationEntryMsg> send(LogReplication.LogReplicationEntryMsg message);

    /**
     * Application callback on next available messages for transmission to remote cluster.
     *
     * @param messages list of LogReplicationEntry representing the data to send across sites.
     * @return
     */
    CompletableFuture<LogReplication.LogReplicationEntryMsg> send(List<LogReplication.LogReplicationEntryMsg> messages);

    /**
     * Send metadata request to remote cluster
     */
    CompletableFuture<LogReplication.LogReplicationMetadataResponseMsg> sendMetadataRequest();

    /**
     * Application callback on error.
     *
     * @param error log replication error
     */
    void onError(LogReplicationError error);
}
