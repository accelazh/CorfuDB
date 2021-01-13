package org.corfudb.infrastructure.logreplication.infrastructure.plugins;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DefaultClusterConfig {

    @Getter
    private static final String DEFAULT_HOST = "localhost";

    @Getter
    private static final List<String> activeNodesUuid = Collections.singletonList("123e4567-e89b-12d3-a456-556642440000");

    @Getter
    private static final List<String> standbyNodesUuid = Collections.singletonList("123e4567-e89b-12d3-a456-556642440123");

    @Getter
    private static final List<String> activeNodeNames = Collections.singletonList("standby_site_node0");

    @Getter
    private static final List<String> activeIpAddresses = Arrays.asList(DEFAULT_HOST, DEFAULT_HOST, DEFAULT_HOST);

    @Getter
    private static final List<String> standbyIpAddresses = Collections.singletonList(DEFAULT_HOST);

    @Getter
    private static final String activeClusterId = "456e4567-e89b-12d3-a456-556642440001";

    @Getter
    private static final String activeCorfuPort = "9000";

    @Getter
    private static final String activeLogReplicationPort = "9010";

    @Getter
    private static final String standbyClusterId = "456e4567-e89b-12d3-a456-556642440002";

    @Getter
    private static final String standbyCorfuPort = "9001";

    @Getter
    private static final String standbyLogReplicationPort = "9020";

    @Getter
    private static final int logSenderBufferSize = 2;

    @Getter
    private static final int logSenderRetryCount = 5;

    @Getter
    private static final int logSenderResendTimer = 5000;

    @Getter
    private static final int logSenderTimeoutTimer = 5000;

    @Getter
    private static final boolean logSenderTimeout = true;

    @Getter
    private static final int logSinkBufferSize = 40;

    @Getter
    private static final int logSinkAckCycleCount = 4;

    @Getter
    private static final int logSinkAckCycleTimer = 1000;

    public static String getDefaultNodeId(String endpoint) {
        String port = endpoint.split(":")[1];
        if (port.equals(activeLogReplicationPort)) {
            return activeNodesUuid.get(0);
        } else if (port.equals(standbyLogReplicationPort)) {
            return standbyNodesUuid.get(0);
        } else {
            return null;
        }
    }

    private DefaultClusterConfig() {

    }
}
