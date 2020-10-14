package org.corfudb.protocols;

import com.google.common.collect.EnumBiMap;
import com.google.common.collect.ImmutableMap;
import org.corfudb.protocols.wireprotocol.failuredetector.NodeConnectivity;
import org.corfudb.runtime.proto.NodeConnectivity.ConnectionStatus;
import org.corfudb.runtime.proto.NodeConnectivity.ConnectivityEntryMsg;
import org.corfudb.runtime.proto.NodeConnectivity.NodeConnectivityMsg;
import org.corfudb.runtime.proto.NodeConnectivity.NodeConnectivityType;

import java.util.stream.Collectors;

public class CorfuProtocolNodeConnectivity {
    private static EnumBiMap<NodeConnectivity.NodeConnectivityType, NodeConnectivityType> connectivityTypeMap =
            EnumBiMap.create(ImmutableMap.of(
                    NodeConnectivity.NodeConnectivityType.NOT_READY, NodeConnectivityType.NOT_READY,
                    NodeConnectivity.NodeConnectivityType.CONNECTED, NodeConnectivityType.CONNECTED,
                    NodeConnectivity.NodeConnectivityType.UNAVAILABLE, NodeConnectivityType.UNAVAILABLE
            ));

    private static EnumBiMap<NodeConnectivity.ConnectionStatus, ConnectionStatus> statusTypeMap =
            EnumBiMap.create(ImmutableMap.of(
                    NodeConnectivity.ConnectionStatus.OK, ConnectionStatus.OK,
                    NodeConnectivity.ConnectionStatus.FAILED, ConnectionStatus.FAILED
            ));

    public static NodeConnectivityMsg getNodeConnectivityMsg(NodeConnectivity nc) {
        return NodeConnectivityMsg.newBuilder()
                .setEndpoint(nc.getEndpoint())
                .setEpoch(nc.getEpoch())
                .setConnectivityType(connectivityTypeMap.get(nc.getType()))
                .addAllConnectivityInfo(nc.getConnectivity()
                        .entrySet()
                        .stream()
                        .map(e -> ConnectivityEntryMsg.newBuilder()
                                .setNode(e.getKey())
                                .setStatus(statusTypeMap.get(e.getValue()))
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    //TODO: conversion method from NodeConnectivityMsg to NodeConnectivity for client
}