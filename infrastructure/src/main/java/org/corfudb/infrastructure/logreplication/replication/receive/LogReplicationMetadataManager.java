package org.corfudb.infrastructure.logreplication.replication.receive;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.LogReplicationMetadataKey;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.LogReplicationMetadataVal;
import org.corfudb.protocols.wireprotocol.logreplication.LogReplicationEntry;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.CorfuStoreMetadata;
import org.corfudb.runtime.collections.CorfuRecord;
import org.corfudb.runtime.collections.CorfuStore;
import org.corfudb.runtime.collections.Table;
import org.corfudb.runtime.collections.TableOptions;
import org.corfudb.runtime.collections.TxBuilder;
import org.corfudb.runtime.view.Address;

import static org.corfudb.runtime.view.TableRegistry.CORFU_SYSTEM_NAMESPACE;

/**
 * The log replication metadata is stored in a corfutable in the corfustore.
 * The log replication metadata is defined as a proto message that contains:
 * SiteConfigID, Version, Snapshot Full Sync Status and Log Entry Sync Status.
 * The access the metadata is using UFO API.
 *
 * To record replication status, it has following values:
 * SnapshotStartTimestamp: when a full snapshot sync is started, it will first update this value and reset other snapshot related metadata to -1.
 * The init value for this metadata is -1. When it is -1, it means a snapshot full sync is required regardless.
 * SnapshotTranferredTimestamp: the init value is -1. When the receiver receives a snapshot transfer end marker, it will update this value to current snapshot timestamp,
 * it will be updated to the same value as snapshot start when the snapshot data transfer is done.
 * SnapshotSeqNum: it the sequence number of each snapshot messages to detect loss of messages and also to prevent the reappling the same message. All the messages must be
 * applied in the order of the snapshot sequence number.
 * SnapshotAppliedSeqNum: it records the operation's sequence during the apply phase to avoid the redo the apply if there is a leadership change.
 * LastLogProcessed: It records the most recent log entry has been processed.
 * When a snapshot full sync is complete, it will update this value. While processing a new log entry message, it will be updated too.
 *
 * While update siteConfigID or version number, the replication status will all be reset to -1 to
 * require a snapshot full sync.
 */
@Slf4j
public class LogReplicationMetadataManager {

    private static final String namespace = CORFU_SYSTEM_NAMESPACE;
    private static final String TABLE_PREFIX_NAME = "CORFU-REPLICATION-WRITER-";

    private CorfuStore corfuStore;

    private String metadataTableName;

    /**
     * Table used to store the log replication status
     */
    private Table<LogReplicationMetadataKey, LogReplicationMetadataVal, LogReplicationMetadataVal> metadataTable;
    private static final String DEFAULT_VERSION = "Release_Test_0";

    private CorfuRuntime runtime;

    public LogReplicationMetadataManager(CorfuRuntime rt, long topologyConfigId, String localClusterId) {
        this.runtime = rt;
        this.corfuStore = new CorfuStore(runtime);
        metadataTableName = getPersistedWriterMetadataTableName(localClusterId);
        try {
            metadataTable = this.corfuStore.openTable(namespace,
                    metadataTableName,
                    LogReplicationMetadataKey.class,
                    LogReplicationMetadataVal.class,
                    null,
                    TableOptions.builder().build());
        } catch (Exception e) {
            log.error("Caught an exception while opening the table namespace={}, name={}", namespace, metadataTableName);
            throw new ReplicationWriterException(e);
        }

        setupTopologyConfigID(topologyConfigId);
    }

    /**
     * Get the corfustore log tail.
     * @return
     */
    public CorfuStoreMetadata.Timestamp getTimestamp() {
        return corfuStore.getTimestamp();
    }

    /**
     * create a tx builder
     * @return
     */
    TxBuilder getTxBuilder() {
        return corfuStore.tx(namespace);
    }

    /**
     * Given a specific metadata key type, get the corresponding value in string format.
     * @param timestamp
     * @param key
     * @return
     */
    String queryString(CorfuStoreMetadata.Timestamp timestamp, LogReplicationMetadataType key) {
        LogReplicationMetadataKey txKey = LogReplicationMetadataKey.newBuilder().setKey(key.getVal()).build();
        CorfuRecord record;
        if (timestamp == null) {
            record = corfuStore.query(namespace).getRecord(metadataTableName, txKey);
        } else {
            record = corfuStore.query(namespace).getRecord(metadataTableName, timestamp, txKey);
        }

        LogReplicationMetadataVal metadataVal = null;
        String val = null;

        if (record != null) {
            metadataVal = (LogReplicationMetadataVal) record.getPayload();
        }

        if (metadataVal != null) {
            val = metadataVal.getVal();
        }

        return val;
    }

    /**
     * Given a specific metadata key type and timestamp, get the corresponding long value.
     * @param timestamp
     * @param key
     * @return
     */
    long query(CorfuStoreMetadata.Timestamp timestamp, LogReplicationMetadataType key) {
        long val = -1;
        String str = queryString(timestamp, key);
        if (str != null) {
            val = Long.parseLong(str);
        }
        return val;
    }

    /**
     * Get the most recent ConfigID.
     * @return
     */
    public long getTopologyConfigID() {
        return query(null, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID);
    }
    /**
     * Given a specific timestamp, get the current version value.
     * If the timestamp is null, get the most recent value.
     * @param ts
     * @return
     */
    public String getVersion(CorfuStoreMetadata.Timestamp ts) { return queryString(ts, LogReplicationMetadataType.VERSION); }

    /**
     * Get the most recent version.
     * @return
     */
    public String getVersion() { return queryString(null, LogReplicationMetadataType.VERSION); }

    /**
     * Given a specific timestamp, get the most recent started full snapshot sync's timestamp.
     * If the timestamp is null, get the most recent value.
     * @param
     * @return
     */
    public long getLastSnapStartTimestamp() {
        return query(null, LogReplicationMetadataType.LAST_SNAPSHOT_STARTED);
    }

    public long getLastSnapTransferDoneTimestamp() {
        return query(null, LogReplicationMetadataType.LAST_SNAPSHOT_TRANSFERRED);
    }

    public long getLastSrcBaseSnapshotTimestamp() {
        return query(null, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED);
    }

    public long getLastSnapSeqNum() {
        return query(null, LogReplicationMetadataType.LAST_SNAPSHOT_SEQ_NUM);
    }

    public long getLastSnapAppliedSeqNum() {
        return query(null, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED_SEQ_NUM);
    }

    public long getLastProcessedLogTimestamp() {
        return query(null, LogReplicationMetadataType.LAST_LOG_PROCESSED);
    }

    /**
     * Given a specific timestamp, get the most recent full snapshot sync's timestamp that has complete the
     * transferred phase.
     * If the timestamp is null, get the most recent value.
     * @param ts
     * @return
     */
    public long getLastSnapTransferDoneTimestamp(CorfuStoreMetadata.Timestamp ts) {
            return query(ts, LogReplicationMetadataType.LAST_SNAPSHOT_TRANSFERRED);
    }

    /**
     * Given a specific timestamp, get the most recent completed snapshot full sync's timestamp.
     * If the timestamp is null, get the most recent value.
     * @param ts
     * @return
     */
    public long getLastSrcBaseSnapshotTimestamp(CorfuStoreMetadata.Timestamp ts) {
        return query(ts, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED);
    }

    /**
     * Given a specific timestamp, get the most recent snapshot message's sequence number
     * that the receiver has applied to the shadow streams.
     * If the timestamp is null, get the most recent value.
     * @param ts
     * @return
     */
    public long getLastSnapSeqNum(CorfuStoreMetadata.Timestamp ts) {
        return query(ts, LogReplicationMetadataType.LAST_SNAPSHOT_SEQ_NUM);
    }

    /**
     * Given a specific timestamp, get the most recent applied operation sequence number
     * that the receiver has applied to the real streams.
     * If the timestamp is null, get the most recent value.
     * @param ts
     * @return
     */
    public long getLastSnapAppliedSeqNum(CorfuStoreMetadata.Timestamp ts) {
        return query(ts, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED_SEQ_NUM);
    }

    /**
     * Given a specific timestamp, get the most recent log that has applied to the
     * receiver's log.
     * If the timestamp is null, get the most recent value.
     * @param ts
     * @return
     */
    public long getLastProcessedLogTimestamp(CorfuStoreMetadata.Timestamp ts) {
        return query(ts, LogReplicationMetadataType.LAST_LOG_PROCESSED);
    }

    /**
     * Append an metadata update to the same tx with a long.
     * @param txBuilder
     * @param key the key to be updated.
     * @param val the new value.
     */
    void appendUpdate(TxBuilder txBuilder, LogReplicationMetadataType key, long val) {
        LogReplicationMetadataKey txKey = LogReplicationMetadataKey.newBuilder().setKey(key.getVal()).build();
        LogReplicationMetadataVal txVal = LogReplicationMetadataVal.newBuilder().setVal(Long.toString(val)).build();
        txBuilder.update(metadataTableName, txKey, txVal, null);
    }

    /**
     * Append an metadata update to the same tx with a String.
     * @param txBuilder
     * @param key the key to be updated.
     * @param val the new value.
     */
    private void appendUpdate(TxBuilder txBuilder, LogReplicationMetadataType key, String val) {
        LogReplicationMetadataKey txKey = LogReplicationMetadataKey.newBuilder().setKey(key.getVal()).build();
        LogReplicationMetadataVal txVal = LogReplicationMetadataVal.newBuilder().setVal(val).build();
        txBuilder.update(metadataTableName, txKey, txVal, null);
    }

    /**
     * Update the siteConfigID if it is bigger than the current one.
     * At the same time reset replication status metadata to -1.
     * @param topologyConfigId
     */
    public void setupTopologyConfigID(long topologyConfigId) {
        CorfuStoreMetadata.Timestamp timestamp = corfuStore.getTimestamp();
        long persistedTopologyConfigId = query(timestamp, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID);

        if (topologyConfigId <= persistedTopologyConfigId) {
            log.warn("Skip setupTopologyConfigId. the current topologyConfigId " + topologyConfigId + " is not larger than the persistedTopologyConfigID " + persistedTopologyConfigId);
            return;
        }

        TxBuilder txBuilder = corfuStore.tx(namespace);

        for (LogReplicationMetadataType key : LogReplicationMetadataType.values()) {
            long val = Address.NON_ADDRESS;
            if (key == LogReplicationMetadataType.TOPOLOGY_CONFIG_ID) {
                val = topologyConfigId;
            }
            appendUpdate(txBuilder, key, val);
         }

        if (getVersion(null) == null) {

        }
        txBuilder.commit(timestamp);

        log.info("Update siteConfigID {}, new metadata {}", topologyConfigId, toString());
    }

    /**
     * If the current topologyConfigId is not the same as the persisted topologyConfigId, ignore the operation.
     * If the current ts is smaller than the persisted snapStart, it is an old operation,
     * ignore it it.
     * Otherwise, update the snapStart. The update of topologyConfigId just fence off any other metadata
     * updates in another transactions.
     *
     * @param topologyConfigId the current operation's topologyConfigId
     * @param ts the snapshotStart snapshot time for the topologyConfigId.
     * @return if the operation succeeds or not.
     */
    public boolean setSrcBaseSnapshotStart(long topologyConfigId, long ts) {
        CorfuStoreMetadata.Timestamp timestamp = corfuStore.getTimestamp();
        long persistedTopologyConfigID = query(timestamp, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID);
        long persistSnapStart = query(timestamp, LogReplicationMetadataType.LAST_SNAPSHOT_STARTED);

        log.debug("Set snapshotStart topologyConfigId={}, ts={}, persistedTopologyConfigID={}, persistedSnapshotStart={}",
                topologyConfigId, ts, persistedTopologyConfigID, persistSnapStart);

        // It means the cluster config has changed, ignore the update operation.
        if (topologyConfigId != persistedTopologyConfigID || ts <= persistedTopologyConfigID) {
            log.warn("The metadata is older than the persisted one. Set snapshotStart topologyConfigId={}, ts={}," +
                    " persistedTopologyConfigId={}, persistedSnapshotStart={}", topologyConfigId, ts,
                    persistedTopologyConfigID, persistSnapStart);
            return false;
        }

        TxBuilder txBuilder = corfuStore.tx(namespace);

        // Update the topologyConfigId to fence all other transactions that update the metadata at the same time
        appendUpdate(txBuilder, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID, topologyConfigId);

        // Setup the LAST_SNAPSHOT_STARTED
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_STARTED, ts);

        // Reset other metadata
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_TRANSFERRED, Address.NON_ADDRESS);
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED, Address.NON_ADDRESS);
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_SEQ_NUM, Address.NON_ADDRESS);
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED_SEQ_NUM, Address.NON_ADDRESS);
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_LOG_PROCESSED, Address.NON_ADDRESS);

        txBuilder.commit(timestamp);

        log.debug("Commit. Set snapshotStart topologyConfigId={}, ts={}, persistedTopologyConfigID={}, " +
                        "persistedSnapshotStart={}",
                topologyConfigId, ts, persistedTopologyConfigID, persistSnapStart);

        return (ts == getLastSnapStartTimestamp() && topologyConfigId == getTopologyConfigID());
    }


    /**
     * While snapshot full sync has finished the phase I: transferred all data from sender to receiver and applied to
     * the shadow streams.
     * @param ts
     */
    public void setLastSnapTransferDoneTimestamp(long topologyConfigId, long ts) {
        CorfuStoreMetadata.Timestamp timestamp = corfuStore.getTimestamp();
        long persistedTopologyConfigId = query(timestamp, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID);
        long persistSnapStart = query(timestamp, LogReplicationMetadataType.LAST_SNAPSHOT_STARTED);

        log.debug("setLastSnapTransferDone snapshotStart topologyConfigId={}, ts={}, persistedTopologyConfigID={}," +
                " persistedSiteConfigID={}, persistedSnapshotStart={}", topologyConfigId, ts, persistedTopologyConfigId,
                persistSnapStart);

        // It means the cluster config has changed, ignore the update operation.
        if (topologyConfigId != persistedTopologyConfigId || ts <= persistedTopologyConfigId) {
            log.warn("The metadata is older than the persisted one. Set snapshotStart topologyConfigId " + topologyConfigId + " ts " + ts +
                    " persisteSiteConfigID " + persistedTopologyConfigId + " persistSnapStart " + persistSnapStart);
            return;
        }

        TxBuilder txBuilder = corfuStore.tx(namespace);

        //Update the topologyConfigId to fence all other transactions that update the metadata at the same time
        appendUpdate(txBuilder, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID, topologyConfigId);

        //Setup the LAST_SNAPSHOT_STARTED
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_TRANSFERRED, ts);

        txBuilder.commit(timestamp);

        log.debug("Commit. Set snapshotStart topologyConfigId " + topologyConfigId + " ts " + ts +
                " persisteSiteConfigID " + persistedTopologyConfigId + " persistSnapStart " + persistSnapStart);
        return;
    }

    /**
     * When the snapshot full sync completes both phase I, data transfer, and phase II, applying to the real data
     * streams at the receiver, update the full snapshot complete value.
     * @param entry
     */
    public void setSnapshotApplied(LogReplicationEntry entry) {
        CorfuStoreMetadata.Timestamp timestamp = corfuStore.getTimestamp();
        long persistSiteConfigID = query(timestamp, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID);
        long persistSnapStart = query(timestamp, LogReplicationMetadataType.LAST_SNAPSHOT_STARTED);
        long persistSnapTranferDone = query(timestamp, LogReplicationMetadataType.LAST_SNAPSHOT_TRANSFERRED);
        long siteConfigID = entry.getMetadata().getTopologyConfigId();
        long ts = entry.getMetadata().getSnapshotTimestamp();

        if (siteConfigID != persistSiteConfigID || ts != persistSnapStart || ts != persistSnapTranferDone) {
            log.warn("topologyConfigId " + siteConfigID + " != " + " persist " + persistSiteConfigID +  " ts " + ts +
                    " != " + "persistSnapTransferDone " + persistSnapTranferDone);
            return;
        }

        TxBuilder txBuilder = corfuStore.tx(namespace);

        //Update the topologyConfigId to fence all other transactions that update the metadata at the same time
        appendUpdate(txBuilder, LogReplicationMetadataType.TOPOLOGY_CONFIG_ID, siteConfigID);

        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED, ts);
        appendUpdate(txBuilder, LogReplicationMetadataType.LAST_LOG_PROCESSED, ts);

        txBuilder.commit(timestamp);

        log.debug("Commit. Set snapshotStart topologyConfigId " + siteConfigID + " ts " + ts +
                " persistSiteConfigID " + persistSiteConfigID + " persistSnapStart " + persistSnapStart);

        return;
    }

    @Override
    public String toString() {
        String s = new String();
        s = s.concat(LogReplicationMetadataType.TOPOLOGY_CONFIG_ID.getVal() + " " + getTopologyConfigID() +" ");
        s = s.concat(LogReplicationMetadataType.LAST_SNAPSHOT_STARTED.getVal() + " " + getLastSnapStartTimestamp() +" ");
        s = s.concat(LogReplicationMetadataType.LAST_SNAPSHOT_TRANSFERRED.getVal() + " " + getLastSnapTransferDoneTimestamp() + " ");
        s = s.concat(LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED.getVal() + " " + getLastSrcBaseSnapshotTimestamp() + " ");
        s = s.concat(LogReplicationMetadataType.LAST_SNAPSHOT_SEQ_NUM.getVal() + " " + getLastSnapSeqNum() + " ");
        s = s.concat(LogReplicationMetadataType.LAST_SNAPSHOT_APPLIED_SEQ_NUM.getVal() + " " + getLastSnapAppliedSeqNum() + " ");
        s = s.concat(LogReplicationMetadataType.LAST_LOG_PROCESSED.getVal() + " " + getLastProcessedLogTimestamp() + " ");

        return s;
    }

    public static String getPersistedWriterMetadataTableName(String localClusterId) {
        return TABLE_PREFIX_NAME + localClusterId;
    }

    public long getLogHead() {
        return runtime.getAddressSpaceView().getTrimMark().getSequence();
    }

    /**
     * All types of metadata stored in the corfu table, those corresponding strings are used as keys to access
     * the real values.
     */
    public enum LogReplicationMetadataType {
        TOPOLOGY_CONFIG_ID("topologyConfigId"),
        VERSION("version"),
        LAST_SNAPSHOT_STARTED("lastSnapStart"),
        LAST_SNAPSHOT_TRANSFERRED("lastSnapTransferred"),
        LAST_SNAPSHOT_APPLIED("lastSnapApplied"),
        LAST_SNAPSHOT_SEQ_NUM("lastSnapSeqNum"),
        LAST_SNAPSHOT_APPLIED_SEQ_NUM("lastSnapAppliedSeqNum"),
        LAST_LOG_PROCESSED("lastLogProcessed");

        @Getter
        String val;
        LogReplicationMetadataType(String newVal) {
            val  = newVal;
        }
    }
}
