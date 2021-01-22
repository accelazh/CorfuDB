package org.corfudb.protocols.wireprotocol;

import com.google.common.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;

/**
 * Created by mwei on 8/8/16.
 */
@RequiredArgsConstructor
@AllArgsConstructor
public enum CorfuMsgType {
    // Base Messages
    ACK(4, TypeToken.of(CorfuMsg.class), true, false),
    WRONG_EPOCH(5, new TypeToken<CorfuPayloadMsg<Long>>() {},  true, false),
    NACK(6, TypeToken.of(CorfuMsg.class)),
    NOT_READY(9, TypeToken.of(CorfuMsg.class), true, false),
    WRONG_CLUSTER_ID(28, new TypeToken<CorfuPayloadMsg<WrongClusterMsg>>(){}, true, false),

    // Layout Messages
    LAYOUT_NOBOOTSTRAP(19, TypeToken.of(CorfuMsg.class), true, false),

    // Logging Unit Messages
    WRITE(30, new TypeToken<CorfuPayloadMsg<WriteRequest>>() {}),
    READ_REQUEST(31, new TypeToken<CorfuPayloadMsg<ReadRequest>>() {}),
    READ_RESPONSE(32, new TypeToken<CorfuPayloadMsg<ReadResponse>>() {}),
    INSPECT_ADDRESSES_REQUEST(36, new TypeToken<CorfuPayloadMsg<InspectAddressesRequest>>() {}),
    INSPECT_ADDRESSES_RESPONSE(37, new TypeToken<CorfuPayloadMsg<InspectAddressesResponse>>() {}),
    PREFIX_TRIM(38, new TypeToken<CorfuPayloadMsg<TrimRequest>>() {}),
    TAIL_REQUEST(41, new TypeToken<CorfuPayloadMsg<TailsRequest>>(){}),
    TAIL_RESPONSE(42, new TypeToken<CorfuPayloadMsg<TailsResponse>>(){}),
    COMPACT_REQUEST(43, TypeToken.of(CorfuMsg.class), true, false),
    FLUSH_CACHE(44, TypeToken.of(CorfuMsg.class), true, false),
    TRIM_MARK_REQUEST(45, TypeToken.of(CorfuMsg.class)),
    TRIM_MARK_RESPONSE(46, new TypeToken<CorfuPayloadMsg<Long>>(){}),
    RESET_LOGUNIT(47, new TypeToken<CorfuPayloadMsg<Long>>(){}, true, false),
    LOG_ADDRESS_SPACE_REQUEST(48, TypeToken.of(CorfuMsg.class)),
    LOG_ADDRESS_SPACE_RESPONSE(49, new TypeToken<CorfuPayloadMsg<StreamsAddressResponse>>(){}),

    WRITE_OK(50, TypeToken.of(CorfuMsg.class)),
    ERROR_TRIMMED(51, TypeToken.of(CorfuMsg.class)),
    ERROR_OVERWRITE(52, new TypeToken<CorfuPayloadMsg<Integer>>(){}, true, false),
    ERROR_OOS(53, TypeToken.of(CorfuMsg.class)),
    ERROR_RANK(54, TypeToken.of(CorfuMsg.class)),
    ERROR_NOENTRY(55, TypeToken.of(CorfuMsg.class)),
    RANGE_WRITE(56, new TypeToken<CorfuPayloadMsg<RangeWriteMsg>>(){}),
    ERROR_DATA_CORRUPTION(57, new TypeToken<CorfuPayloadMsg<Long>>(){}),

    // EXTRA CODES
    COMMITTED_TAIL_REQUEST(64, TypeToken.of(CorfuMsg.class)),
    COMMITTED_TAIL_RESPONSE(65, new TypeToken<CorfuPayloadMsg<Long>>(){}),
    UPDATE_COMMITTED_TAIL(66, new TypeToken<CorfuPayloadMsg<Long>>(){}),

    KNOWN_ADDRESS_REQUEST(86, new TypeToken<CorfuPayloadMsg<KnownAddressRequest>>() {}),
    KNOWN_ADDRESS_RESPONSE(87, new TypeToken<CorfuPayloadMsg<KnownAddressResponse>>() {}),

    ERROR_SERVER_EXCEPTION(200, new TypeToken<CorfuPayloadMsg<ExceptionMsg>>() {}, true, false),
    ;


    public final int type;
    public final TypeToken<? extends CorfuMsg> messageType;
    public boolean ignoreEpoch = false;
    public boolean ignoreClusterId = false;

    public <T> CorfuPayloadMsg<T> payloadMsg(T payload) {
        // todo:: maybe some typechecking here (performance impact?)
        return new CorfuPayloadMsg<T>(this, payload);
    }

    public CorfuMsg msg() {
        return new CorfuMsg(this);
    }

    @FunctionalInterface
    interface MessageConstructor<T> {
        T construct();
    }

    @Getter(lazy = true)
    private final MessageConstructor<? extends CorfuMsg> constructor = resolveConstructor();

    public byte asByte() {
        return (byte) type;
    }

    /** A lookup representing the context we'll use to do lookups. */
    private static java.lang.invoke.MethodHandles.Lookup lookup = MethodHandles.lookup();

    /** Generate a lambda pointing to the constructor for this message type. */
    @SuppressWarnings("unchecked")
    private MessageConstructor<? extends CorfuMsg> resolveConstructor() {
        // Grab the constructor and get convert it to a lambda.
        try {
            Constructor t = messageType.getRawType().getConstructor();
            MethodHandle mh = lookup.unreflectConstructor(t);
            MethodType mt = MethodType.methodType(Object.class);
            try {
                return (MessageConstructor<? extends CorfuMsg>) LambdaMetafactory.metafactory(
                        lookup, "construct",
                        MethodType.methodType(MessageConstructor.class),
                        mt, mh, mh.type())
                        .getTarget().invokeExact();
            } catch (Throwable th) {
                throw new RuntimeException(th);
            }
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException("CorfuMsgs must include a no-arg constructor!");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

}
