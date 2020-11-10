package org.corfudb.generator.operations;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.generator.Correctness;
import org.corfudb.generator.State;
import org.corfudb.protocols.wireprotocol.Token;
import org.corfudb.runtime.exceptions.TransactionAbortedException;

import java.util.List;
import java.util.Random;

/**
 * Created by box on 7/15/17.
 */
@Slf4j
public class SnapshotTxOperation extends Operation {


    public SnapshotTxOperation(State state) {
        super(state);
        shortName = "TxSnap";
    }

    @Override
    public void execute() {
        try {
            Token trimMark = state.getTrimMark();
            Random rand = new Random();
            long delta = (long) rand.nextInt(10) + 1;

            // Safety Hack for not having snapshot in the future

            Correctness.recordTransactionMarkers(false, shortName, Correctness.TX_START);

            // TODO(Maithem) keep a window of tokens issued in the past and select
            // a random token to use for user-defined snapshot transactions
            state.startSnapshotTx();

            int numOperations = state.getOperationCount().sample(1).get(0);
            List<Operation> operations = state.getOperations().sample(numOperations);

            for (Operation operation : operations) {
                if (operation instanceof OptimisticTxOperation
                        || operation instanceof SnapshotTxOperation
                        || operation instanceof RemoveOperation
                        || operation instanceof WriteOperation
                        || operation instanceof NestedTxOperation) {
                    continue;
                }

                operation.execute();
            }

            state.stopTx();
            Correctness.recordTransactionMarkers(false, shortName, Correctness.TX_END);
            state.setLastSuccessfulReadOperationTimestamp(System.currentTimeMillis());
        } catch (TransactionAbortedException tae) {
            Correctness.recordTransactionMarkers(false, shortName, Correctness.TX_ABORTED);
        }


    }
}