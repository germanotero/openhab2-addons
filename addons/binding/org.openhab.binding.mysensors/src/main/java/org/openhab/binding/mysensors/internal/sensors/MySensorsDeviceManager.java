package org.openhab.binding.mysensors.internal.sensors;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.openhab.binding.mysensors.internal.MySensorsUtility;
import org.openhab.binding.mysensors.internal.event.MySensorsEventType;
import org.openhab.binding.mysensors.internal.event.MySensorsStatusUpdateEvent;
import org.openhab.binding.mysensors.internal.event.MySensorsUpdateListener;
import org.openhab.binding.mysensors.internal.exception.NoMoreIdsException;
import org.openhab.binding.mysensors.internal.protocol.MySensorsBridgeConnection;
import org.openhab.binding.mysensors.internal.protocol.message.MySensorsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MySensorsDeviceManager implements MySensorsUpdateListener {
    private Logger logger = LoggerFactory.getLogger(getClass());

    private MySensorsBridgeConnection myCon = null;

    private HashMap<Integer, MySensorsNode> nodeMap = null;

    public MySensorsDeviceManager(MySensorsBridgeConnection myCon) {
        this.myCon = myCon;
        this.nodeMap = new HashMap<Integer, MySensorsNode>();
    }

    public MySensorsDeviceManager(MySensorsBridgeConnection myCon, HashMap<Integer, MySensorsNode> nodeMap) {
        this.myCon = myCon;
        this.nodeMap = nodeMap;
    }

    public MySensorsDeviceManager(MySensorsBridgeConnection myCon, List<MySensorsNode> nodeList) {
        this.myCon = myCon;
        this.nodeMap = new HashMap<Integer, MySensorsNode>();

        if (nodeList != null) {
            for (MySensorsNode n : nodeList) {
                nodeMap.put(n.getNodeId(), n);
            }
        }

    }

    public MySensorsNode getNode(int nodeId) {
        return nodeMap.get(nodeId);
    }

    public void addNode(MySensorsNode node) {
        synchronized (nodeMap) {
            nodeMap.put(node.getNodeId(), node);
        }
    }

    public void addChild(int nodeId, MySensorsChild<?> child) {
        synchronized (nodeMap) {
            MySensorsNode node = nodeMap.get(nodeId);
            if (node != null) {
                node.addChild(child);
            } else {
                logger.warn("Node {} not found in map", nodeId);
            }
        }
    }

    public Set<Integer> getGivenIds() {
        return nodeMap.keySet();
    }

    public Integer reserveId() throws NoMoreIdsException {
        int newId = 1;

        clearNullOnMap();

        Set<Integer> takenIds = getGivenIds();

        synchronized (takenIds) {
            while (newId < 255) {
                if (!takenIds.contains(newId)) {
                    nodeMap.put(newId, null);
                } else {
                    newId++;
                }
            }
        }

        if (newId == 255) {
            throw new NoMoreIdsException();
        }

        return newId;
    }

    @Override
    public void statusUpdateReceived(MySensorsStatusUpdateEvent event) {
        switch (event.getEventType()) {
            case INCOMING_MESSAGE:
                handleIncomingMessageEvent((MySensorsMessage) event.getData());
                break;
            default:
                break;
        }

    }

    private void handleIncomingMessageEvent(MySensorsMessage msg) {
        // Are we getting a Request ID Message?
        if (MySensorsUtility.isIdRequestMessage(msg)) {
            answerIDRequest();
            return;
        }

        // Register node if not present
        // checkNodeFound(msg);
        // checkChildFound(msg);
    }

    private void checkNodeFound(MySensorsMessage msg) {
        MySensorsNode node = null;
        synchronized (nodeMap) {
            if (!nodeMap.containsKey(msg.nodeId)) {
                logger.debug("Node {} found!", msg.getNodeId());

                node = new MySensorsNode(msg.nodeId);
                addNode(node);
            }
        }

        if (node != null) {
            MySensorsStatusUpdateEvent evt = new MySensorsStatusUpdateEvent(MySensorsEventType.NEW_NODE_DISCOVERED,
                    node);
            myCon.broadCastEvent(evt);
        }
    }

    private void checkChildFound(MySensorsMessage msg) {
        MySensorsNode node = null;
        synchronized (nodeMap) {
            node = nodeMap.get(msg.nodeId);
            if (node != null) {
                logger.debug("Child {} for node: {} found!", msg.getChildId(), msg.getNodeId());

                MySensorsChild<?> child = new MySensorsChild<Void>(msg.nodeId, null);
                addChild(msg.nodeId, child);
            }
        }
    }

    /**
     * Removes null element from map, null element represent reserved, but not used, id for nodes.
     */
    private void clearNullOnMap() {
        synchronized (nodeMap) {
            for (Integer i : getGivenIds()) {
                if (getNode(i) == null) {
                    nodeMap.remove(i);
                }
            }
        }
    }

    /**
     * If an ID -Request from a sensor is received the controller will send an id to the sensor
     */
    private void answerIDRequest() {
        logger.debug("ID Request received");

        int newId = 0;
        try {
            newId = reserveId();
            MySensorsMessage newMsg = new MySensorsMessage(255, 255, 3, 0, false, 4, newId + "");
            myCon.addMySensorsOutboundMessage(newMsg);
            logger.info("New Node in the MySensors network has requested an ID. ID is: {}", newId);
        } catch (NoMoreIdsException e) {
            logger.error("No more IDs available for this node, try cleaning cache");
        }
    }
}