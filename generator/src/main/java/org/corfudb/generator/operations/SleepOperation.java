package org.corfudb.generator.operations;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.generator.State;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuInterruptedError;

import java.util.Random;

/**
 * Created by maithem on 7/14/17.
 */
@Slf4j
public class SleepOperation extends Operation {

    public SleepOperation(State state) {
        super(state);
        shortName = "Sleep";
    }

    @Override
    @SuppressWarnings("checkstyle:ThreadSleep")
    public void execute() {
        Random rand = new Random();

        int sleepTime = rand.nextInt(50);
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }
}
