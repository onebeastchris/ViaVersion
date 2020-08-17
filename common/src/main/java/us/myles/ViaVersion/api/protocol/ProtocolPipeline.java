package us.myles.ViaVersion.api.protocol;

import com.google.common.collect.Lists;
import us.myles.ViaVersion.api.PacketWrapper;
import us.myles.ViaVersion.api.Via;
import us.myles.ViaVersion.api.data.UserConnection;
import us.myles.ViaVersion.api.platform.ViaPlatform;
import us.myles.ViaVersion.packets.Direction;
import us.myles.ViaVersion.packets.State;
import us.myles.ViaVersion.protocols.base.ProtocolInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public class ProtocolPipeline extends SimpleProtocol {
    private List<Protocol> incoming;
    private List<Protocol> outgoing;
    private UserConnection userConnection;

    public ProtocolPipeline(UserConnection userConnection) {
        super();
        init(userConnection);
    }

    @Override
    protected void registerPackets() {
        incoming = new CopyOnWriteArrayList<>();
        outgoing = Lists.reverse(incoming);
        // This is a pipeline so we register basic pipes
        incoming.add(ProtocolRegistry.BASE_PROTOCOL);
    }

    @Override
    public void init(UserConnection userConnection) {
        this.userConnection = userConnection;

        ProtocolInfo protocolInfo = new ProtocolInfo(userConnection);
        protocolInfo.setPipeline(this);

        userConnection.setProtocolInfo(protocolInfo);

        /* Init through all our pipes */
        for (Protocol protocol : incoming) {
            protocol.init(userConnection);
        }
    }

    /**
     * Add a protocol to the current pipeline
     * This will call the {@link Protocol#init(UserConnection)} method.
     *
     * @param protocol The protocol to add to the end
     */
    public void add(Protocol protocol) {
        if (incoming != null) {
            incoming.add(protocol);
            protocol.init(userConnection);
            // Move base Protocols to the end, so the login packets can be modified by other protocols
            List<Protocol> toMove = new ArrayList<>();
            for (Protocol p : incoming) {
                if (ProtocolRegistry.isBaseProtocol(p)) {
                    toMove.add(p);
                }
            }
            incoming.removeAll(toMove);
            incoming.addAll(toMove);
        } else {
            throw new NullPointerException("Tried to add protocol too early");
        }
    }

    @Override
    public void transform(Direction direction, State state, PacketWrapper packetWrapper) throws Exception {
        int originalID = packetWrapper.getId();
        List<Protocol> protocols = direction == Direction.OUTGOING ? outgoing : incoming;

        // Apply protocols
        packetWrapper.apply(direction, state, 0, protocols);
        super.transform(direction, state, packetWrapper);

        if (Via.getManager().isDebug()) {
            logPacket(direction, state, packetWrapper, originalID);
        }
    }

    private void logPacket(Direction direction, State state, PacketWrapper packetWrapper, int originalID) {
        // Debug packet
        int clientProtocol = userConnection.getProtocolInfo().getProtocolVersion();
        ViaPlatform platform = Via.getPlatform();

        String actualUsername = packetWrapper.user().getProtocolInfo().getUsername();
        String username = actualUsername != null ? actualUsername + " " : "";

        platform.getLogger().log(Level.INFO, "{0}{1} {2}: {3} (0x{4}) -> {5} (0x{6}) [{7}] {8}",
                new Object[]{
                        username,
                        direction,
                        state,
                        originalID,
                        Integer.toHexString(originalID),
                        packetWrapper.getId(),
                        Integer.toHexString(packetWrapper.getId()),
                        Integer.toString(clientProtocol),
                        packetWrapper
                });
    }

    /**
     * Check if the pipeline contains a protocol
     *
     * @param pipeClass The class to check
     * @return True if the protocol class is in the pipeline
     */
    public boolean contains(Class<? extends Protocol> pipeClass) {
        for (Protocol protocol : incoming) {
            if (protocol.getClass().equals(pipeClass)) return true;
        }
        return false;
    }

    public <P extends Protocol> P getProtocol(Class<P> pipeClass) {
        for (Protocol protocol : incoming) {
            if (protocol.getClass() == pipeClass) return (P) protocol;
        }
        return null;
    }

    /**
     * Use the pipeline to filter a NMS packet
     *
     * @param o    The NMS packet object
     * @param list The output list to write to
     * @return If it should not write the input object to te list.
     * @throws Exception If it failed to convert / packet cancelld.
     */
    public boolean filter(Object o, List list) throws Exception {
        for (Protocol protocol : incoming) {
            if (protocol.isFiltered(o.getClass())) {
                protocol.filterPacket(userConnection, o, list);
                return true;
            }
        }

        return false;
    }

    public List<Protocol> pipes() {
        return incoming;
    }

    /**
     * Cleans the pipe and adds {@link us.myles.ViaVersion.protocols.base.BaseProtocol}
     * /!\ WARNING - It doesn't add version-specific base Protocol
     */
    public void cleanPipes() {
        pipes().clear();
        registerPackets();
    }
}
