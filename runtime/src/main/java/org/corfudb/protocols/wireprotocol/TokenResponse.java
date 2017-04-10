package org.corfudb.protocols.wireprotocol;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Created by mwei on 8/8/16.
 */
@Data
@AllArgsConstructor
public class TokenResponse implements ICorfuPayload<TokenResponse> {

    /** the cause/type of response */
    final TokenType respType;

    /** The current token,
     * or overload with "cause address" in case token request is denied. */
    final Long token;

    /** The backpointer map, if available. */
    final Map<UUID, Long> backpointerMap;

    /** The map of local stream addresses. */
    final Map<UUID, Long> streamAddresses;

    public TokenResponse(ByteBuf buf) {
        respType = TokenType.values()[ICorfuPayload.fromBuffer(buf, Byte.class)];
        token = ICorfuPayload.fromBuffer(buf, Long.class);
        backpointerMap = ICorfuPayload.mapFromBuffer(buf, UUID.class, Long.class);
        streamAddresses = ICorfuPayload.mapFromBuffer(buf, UUID.class, Long.class);
    }

    @Override
    public void doSerialize(ByteBuf buf) {
        ICorfuPayload.serialize(buf, respType);
        ICorfuPayload.serialize(buf, token);
        ICorfuPayload.serialize(buf, backpointerMap);
        ICorfuPayload.serialize(buf, streamAddresses);
    }
}
