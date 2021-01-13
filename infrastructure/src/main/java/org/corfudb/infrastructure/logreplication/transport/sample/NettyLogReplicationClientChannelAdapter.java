package org.corfudb.infrastructure.logreplication.transport.sample;

import lombok.NonNull;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.logreplication.infrastructure.ClusterDescriptor;
import org.corfudb.infrastructure.logreplication.infrastructure.NodeDescriptor;
import org.corfudb.runtime.Messages.CorfuMessage;
import org.corfudb.infrastructure.logreplication.transport.client.IClientChannelAdapter;
import org.corfudb.infrastructure.logreplication.runtime.LogReplicationClientRouter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class NettyLogReplicationClientChannelAdapter extends IClientChannelAdapter {

    /**
     * Map of remote node id to Channel
     */
    private volatile Map<String, CorfuNettyClientChannel> channels;

    private final ExecutorService executorService;

    /**
     * Constructor
     *
     * @param remoteClusterDescriptor
     * @param router
     */
    public NettyLogReplicationClientChannelAdapter(@NonNull String localClusterId,
                                                   @NonNull ClusterDescriptor remoteClusterDescriptor,
                                                   @NonNull LogReplicationClientRouter router) {
        super(localClusterId, remoteClusterDescriptor, router);
        this.channels = new ConcurrentHashMap<>();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public void connectAsync() {
        executorService.submit(() -> {
            ClusterDescriptor remoteCluster = getRemoteClusterDescriptor();
            for (NodeDescriptor node : remoteCluster.getNodesDescriptors()) {
                log.info("Create Netty Channel to remote node {}", node.getRealNodeId());
                CorfuNettyClientChannel channel = new CorfuNettyClientChannel(node, getRouter().getParameters().getNettyEventLoop(), this);
                this.channels.put(node.getRealNodeId().toString(), channel);
            }
        });
    }

    @Override
    public void stop() {
        channels.values().forEach(ch -> ch.close());
    }

    @Override
    public void send(String nodeId, CorfuMessage msg) {
        // Check the connection future. If connected, continue with sending the message.
        // If timed out, return a exceptionally completed with the timeout.
        if (channels.containsKey(nodeId)) {
            log.info("Sending message to node {} on cluster {}, type={}", nodeId, getRemoteClusterDescriptor().getClusterId(), msg.getType());
            channels.get(nodeId).send(msg);
        } else {
            log.warn("Channel to node {} does not exist, message of type={} is dropped", nodeId, msg.getType());
        }
    }

    @Override
    public void onConnectionUp(String nodeId) {
        executorService.submit(() -> super.onConnectionUp(nodeId));
    }

    public void completeExceptionally(Exception exception) {
        getRouter().completeAllExceptionally(exception);
    }
}
