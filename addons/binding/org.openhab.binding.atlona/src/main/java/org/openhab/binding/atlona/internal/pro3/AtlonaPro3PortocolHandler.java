/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.atlona.internal.pro3;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.openhab.binding.atlona.internal.AtlonaHandlerCallback;
import org.openhab.binding.atlona.internal.net.SocketSession;
import org.openhab.binding.atlona.internal.net.SocketSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the protocol handler for the PRO3 product line. This handler will issue the protocol commands and will
 * process the responses from the PRO3 switch. This handler was written to respond to any response that can be sent from
 * the TCP/IP session (either in response to our own commands or in response to external events [other TCP/IP sessions,
 * web GUI, front panel keystrokes, etc]).
 *
 * @author Tim Roberts
 *
 */
class AtlonaPro3PortocolHandler {
    private Logger logger = LoggerFactory.getLogger(AtlonaPro3PortocolHandler.class);

    /**
     * The {@link SocketSession} used by this protocol handler
     */
    private final SocketSession _session;

    /**
     * The {@link AtlonaPro3Config} configuration used by this handler
     */
    private final AtlonaPro3Config _config;

    /**
     * The {@link AtlonaPro3Capabilities} of the PRO3 model
     */
    private final AtlonaPro3Capabilities _capabilities;

    /**
     * The {@link AtlonaPro3Handler} to call back to update status and state
     */
    private final AtlonaHandlerCallback _callback;

    /**
     * The model type identified by the switch. We save it for faster refreshes since it will not change
     */
    private String _modelType;

    /**
     * The version (firmware) identified by the switch. We save it for faster refreshes since it will not change between
     * sessions
     */
    private String _version;

    /**
     * A special (invalid) command used internally by this handler to identify whether the switch wants a login or not
     * (see {@link #login()})
     */
    private final static String NOTVALID_USER_OR_CMD = "notvalid$934%912";

    // ------------------------------------------------------------------------------------------------
    // The following are the various command formats specified by the Atlona protocol
    private final static String CMD_POWERON = "PWON";
    private final static String CMD_POWEROFF = "PWOFF";
    private final static String CMD_POWER_STATUS = "PWSTA";
    private final static String CMD_VERSION = "Version";
    private final static String CMD_TYPE = "Type";
    private final static String CMD_PANELLOCK = "Lock";
    private final static String CMD_PANELUNLOCK = "Unlock";
    private final static String CMD_PORT_RESETALL = "All#";
    private final static String CMD_PORT_POWER_FORMAT = "x%d$ %s";
    private final static String CMD_PORT_ALL_FORMAT = "x%dAll";
    private final static String CMD_PORT_SWITCH_FORMAT = "x%dAVx%d";
    private final static String CMD_PORT_MIRROR_FORMAT = "MirrorHdmi%d Out%d";
    private final static String CMD_PORT_MIRROR_STATUS_FORMAT = "MirrorHdmi%d sta";
    private final static String CMD_PORT_UNMIRROR_FORMAT = "UnMirror%d";
    private final static String CMD_VOLUME_FORMAT = "VOUT%d %s";
    private final static String CMD_VOLUME_MUTE_FORMAT = "VOUTMute%d %s";
    private final static String CMD_IROFF = "IROFF";
    private final static String CMD_IRON = "IRON";
    private final static String CMD_PORT_STATUS = "Status";
    private final static String CMD_PORT_STATUS_FORMAT = "Statusx%d";
    private final static String CMD_SAVEIO_FORMAT = "Save%d";
    private final static String CMD_RECALLIO_FORMAT = "Recall%d";
    private final static String CMD_CLEARIO_FORMAT = "Clear%d";
    private final static String CMD_MATRIX_RESET = "Mreset";
    private final static String CMD_BROADCAST_ON = "Broadcast on";

    // ------------------------------------------------------------------------------------------------
    // The following are the various responses specified by the Atlona protocol
    private final static String RSP_FAILED = "Command FAILED:";

    private final static String RSP_LOGIN = "Login";
    private final static String RSP_PASSWORD = "Password";

    private final Pattern _powerStatusPattern = Pattern.compile("PW(\\w+)");
    private final Pattern _versionPattern = Pattern.compile("Firmware (.*)");
    private final Pattern _typePattern = Pattern.compile("AT-UHD-PRO3-(\\d+)M");
    private final static String RSP_ALL = "All#";
    private final static String RSP_LOCK = "Lock";
    private final static String RSP_UNLOCK = "Unlock";
    private final Pattern _portStatusPattern = Pattern.compile("x(\\d+)AVx(\\d+),?+");
    private final Pattern _portPowerPattern = Pattern.compile("x(\\d+)\\$ (\\w+)");
    private final Pattern _portAllPattern = Pattern.compile("x(\\d+)All");
    private final Pattern _portMirrorPattern = Pattern.compile("MirrorHdmi(\\d+) (\\p{Alpha}+)(\\d*)");
    private final Pattern _portUnmirrorPattern = Pattern.compile("UnMirror(\\d+)");
    private final Pattern _volumePattern = Pattern.compile("VOUT(\\d+) (-?\\d+)");
    private final Pattern _volumeMutePattern = Pattern.compile("VOUTMute(\\d+) (\\w+)");
    private final static String RSP_IROFF = "IROFF";
    private final static String RSP_IRON = "IRON";
    private final Pattern _saveIoPattern = Pattern.compile("Save(\\d+)");
    private final Pattern _recallIoPattern = Pattern.compile("Recall(\\d+)");
    private final Pattern _clearIoPattern = Pattern.compile("Clear(\\d+)");
    private final Pattern _broadCastPattern = Pattern.compile("Broadcast (\\w+)");
    private final static String RSP_MATRIX_RESET = "Mreset";

    // ------------------------------------------------------------------------------------------------
    // The following isn't part of the atlona protocol and is generated by us
    private final static String CMD_PING = "ping";
    private final static String RSP_PING = "Command FAILED: (ping)";

    /**
     * Constructs the protocol handler from given parameters
     *
     * @param session a non-null {@link SocketSession} (may be connected or disconnected)
     * @param config a non-null {@link AtlonaPro3Config}
     * @param capabilities a non-null {@link AtlonaPro3Capabilities}
     * @param callback a non-null {@link AtlonaHandlerCallback} to update state and status
     */
    AtlonaPro3PortocolHandler(SocketSession session, AtlonaPro3Config config, AtlonaPro3Capabilities capabilities,
            AtlonaHandlerCallback callback) {

        if (session == null) {
            throw new IllegalArgumentException("session cannot be null");
        }

        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities cannot be null");
        }

        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }

        _session = session;
        _config = config;
        _capabilities = capabilities;
        _callback = callback;
    }

    /**
     * Attempts to log into the switch when prompted by the switch. Please see code comments on the exact protocol for
     * this.
     *
     * @return a null if logged in successfully (or if switch didn't require login). Non-null if an exception occurred.
     * @throws IOException an IO exception occurred during login
     */
    String login() throws Exception {

        logger.debug("Logging into atlona switch");
        // Void to make sure we retrieve them
        _modelType = null;
        _version = null;

        NoDispatchingCallback callback = new NoDispatchingCallback();
        _session.addListener(callback);

        // Burn the initial (empty) return
        String response;
        try {
            response = callback.getResponse();
            if (!response.equals("")) {
                logger.info("Altona protocol violation - didn't start with an inital empty response: '{}'", response);
            }
        } catch (Exception e) {
            // ignore - may not having given us an initial ""
        }

        // At this point - we are not sure if it's:
        // 1) waiting for a command input
        // or 2) has sent a "Login: " prompt
        // By sending a string that doesn't exist as a command or user
        // we can tell which by the response to the invalid command
        _session.sendCommand(NOTVALID_USER_OR_CMD);

        // Command failed - Altona not configured with IPLogin - return success
        response = callback.getResponse();
        if (response.startsWith(RSP_FAILED)) {
            logger.debug("Altona didn't require a login");
            postLogin();
            return null;
        }

        // We should have been presented wit a new "\r\nLogin: "
        response = callback.getResponse();
        if (!response.equals("")) {
            logger.info("Altona protocol violation - didn't start with an inital empty response: '{}'", response);
        }

        // Get the new "Login: " prompt response
        response = callback.getResponse();
        if (response.equals(RSP_LOGIN)) {
            if (_config.getUserName() == null || _config.getUserName().trim().length() == 0) {
                return "Atlona PRO3 has enabled Telnet/IP Login but no username was provided in the configuration.";
            }

            // Send the username and wait for a ": " response
            _session.sendCommand(_config.getUserName());
        } else {
            return "Altona protocol violation - wasn't initially a command failure or login prompt: " + response;
        }

        // We should have gotten the password response
        response = callback.getResponse();

        // Burn the empty response if we got one (
        if (response.equals("")) {
            response = callback.getResponse();
        }
        if (!response.equals(RSP_PASSWORD)) {
            // If we got another login response, username wasn't valid
            if (response.equals(RSP_LOGIN)) {
                return "Username " + _config.getUserName() + " is not a valid user on the atlona";
            }
            return "Altona protocol violation - invalid response to a login: " + response;
        }

        // Make sure we have a password
        if (_config.getPassword() == null || _config.getPassword().trim().length() == 0) {
            return "Atlona PRO3 has enabled Telnet/IP Login but no password was provided in the configuration.";
        }

        // Send the password
        _session.sendCommand(_config.getPassword());
        response = callback.getResponse();

        // At this point, we don't know if we received a
        // 1) "\r\n" and waiting for a command
        // or 2) "\r\nLogin: " if the password is invalid
        // Send an invalid command to see if we get the failed command response

        // First make sure we had an empty response (the "\r\n" part)
        if (!response.equals("")) {
            logger.info("Altona protocol violation - not an empty response after password: '{}'", response);
        }

        // Now send an invalid command
        _session.sendCommand(NOTVALID_USER_OR_CMD);

        // If we get an invalid command response - we are logged in
        response = callback.getResponse();
        if (response.startsWith(RSP_FAILED)) {
            postLogin();
            return null;
        }

        // Nope - password invalid
        return "Password was invalid - please check your atlona setup";
    }

    /**
     * Post successful login stuff - mark us online and refresh from the switch
     */
    private void postLogin() {
        logger.debug("Atlona switch now connected");
        _session.clearListeners();
        _session.addListener(new NormalResponseCallback());
        _callback.statusChanged(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);

        // Set broadcast to on to receive notifications when
        // routing changes (via the webpage, or presets or IR, etc)
        sendCommand(CMD_BROADCAST_ON);

        // setup the most likely state of these switches (there is no protocol to get them)
        refreshAll();
    }

    /**
     * Returns the callback being used by this handler
     *
     * @return a non-null {@link AtlonaHandlerCallback}
     */
    AtlonaHandlerCallback getCallback() {
        return _callback;
    }

    /**
     * Pings the server with an (invalid) ping command to keep the connection alive
     */
    void ping() {
        sendCommand(CMD_PING);
    }

    /**
     * Refreshes the state from the switch itself. This will retrieve all the state (that we can get) from the switch.
     */
    void refreshAll() {
        logger.debug("Refreshing matrix state");
        if (_version == null) {
            refreshVersion();
        } else {
            _callback.setProperty(AtlonaPro3Constants.PROPERTY_VERSION, _version);
        }

        if (_modelType == null) {
            refreshType();
        } else {
            _callback.setProperty(AtlonaPro3Constants.PROPERTY_TYPE, _modelType);
        }

        refreshPower();
        refreshAllPortStatuses();

        final int nbrPowerPorts = _capabilities.getNbrPowerPorts();
        for (int x = 1; x <= nbrPowerPorts; x++) {
            refreshPortPower(x);
        }

        final int nbrAudioPorts = _capabilities.getNbrAudioPorts();
        for (int x = 1; x <= nbrAudioPorts; x++) {
            refreshVolumeStatus(x);
            refreshVolumeMute(x);
        }

        for (int x : _capabilities.getHdmiPorts()) {
            refreshPortStatus(x);
        }
    }

    /**
     * Sets the power to the switch
     *
     * @param on true if on, false otherwise
     */
    void setPower(boolean on) {
        sendCommand(on ? CMD_POWERON : CMD_POWEROFF);
    }

    /**
     * Queries the switch about it's power state
     */
    void refreshPower() {
        sendCommand(CMD_POWER_STATUS);
    }

    /**
     * Queries the switch about it's version (firmware)
     */
    void refreshVersion() {
        sendCommand(CMD_VERSION);
    }

    /**
     * Queries the switch about it's type (model)
     */
    void refreshType() {
        sendCommand(CMD_TYPE);
    }

    /**
     * Sets whether the front panel is locked or not
     *
     * @param locked true if locked, false otherwise
     */
    void setPanelLock(boolean locked) {
        sendCommand(locked ? CMD_PANELLOCK : CMD_PANELUNLOCK);
    }

    /**
     * Resets all ports back to their default state.
     */
    void resetAllPorts() {
        sendCommand(CMD_PORT_RESETALL);
    }

    /**
     * Sets whether the specified port is powered (i.e. outputing).
     *
     * @param portNbr a greater than zero port number
     * @param on true if powered.
     */
    void setPortPower(int portNbr, boolean on) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_PORT_POWER_FORMAT, portNbr, on ? "on" : "off"));
    }

    /**
     * Refreshes whether the specified port is powered (i.e. outputing).
     *
     * @param portNbr a greater than zero port number
     */
    void refreshPortPower(int portNbr) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_PORT_POWER_FORMAT, portNbr, "sta"));
    }

    /**
     * Sets all the output ports to the specified input port.
     *
     * @param portNbr a greater than zero port number
     */
    void setPortAll(int portNbr) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_PORT_ALL_FORMAT, portNbr));
    }

    /**
     * Sets the input port number to the specified output port number.
     *
     * @param inPortNbr a greater than zero port number
     * @param outPortNbr a greater than zero port number
     */
    void setPortSwitch(int inPortNbr, int outPortNbr) {
        if (inPortNbr <= 0) {
            throw new IllegalArgumentException("inPortNbr must be greater than 0");
        }
        if (outPortNbr <= 0) {
            throw new IllegalArgumentException("outPortNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_PORT_SWITCH_FORMAT, inPortNbr, outPortNbr));
    }

    /**
     * Sets the hdmi port number to mirror the specified output port number.
     *
     * @param hdmiPortNbr a greater than zero port number
     * @param outPortNbr a greater than zero port number
     */
    void setPortMirror(int hdmiPortNbr, int outPortNbr) {
        if (hdmiPortNbr <= 0) {
            throw new IllegalArgumentException("hdmiPortNbr must be greater than 0");
        }
        if (outPortNbr <= 0) {
            throw new IllegalArgumentException("outPortNbr must be greater than 0");
        }

        if (_capabilities.getHdmiPorts().contains(hdmiPortNbr)) {
            sendCommand(String.format(CMD_PORT_MIRROR_FORMAT, hdmiPortNbr, outPortNbr));
        } else {
            logger.info("Trying to set port mirroring on a non-hdmi port: {}", hdmiPortNbr);
        }
    }

    /**
     * Disabled mirroring on the specified hdmi port number.
     *
     * @param hdmiPortNbr a greater than zero port number
     * @param outPortNbr a greater than zero port number
     */
    void removePortMirror(int hdmiPortNbr) {
        if (hdmiPortNbr <= 0) {
            throw new IllegalArgumentException("hdmiPortNbr must be greater than 0");
        }

        if (_capabilities.getHdmiPorts().contains(hdmiPortNbr)) {
            sendCommand(String.format(CMD_PORT_UNMIRROR_FORMAT, hdmiPortNbr));
        } else {
            logger.info("Trying to remove port mirroring on a non-hdmi port: {}", hdmiPortNbr);
        }
    }

    /**
     * Sets the volume level on the specified audio port.
     *
     * @param portNbr a greater than zero port number
     * @param level a volume level in decibels (must range from -79 to +15)
     */
    void setVolume(int portNbr, double level) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        if (level < -79 || level > 15) {
            throw new IllegalArgumentException("level must be between -79 to +15");
        }
        sendCommand(String.format(CMD_VOLUME_FORMAT, portNbr, level));
    }

    /**
     * Refreshes the volume level for the given audio port.
     *
     * @param portNbr a greater than zero port number
     */
    void refreshVolumeStatus(int portNbr) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_VOLUME_FORMAT, portNbr, "sta"));
    }

    /**
     * Refreshes the specified hdmi port's mirroring status
     *
     * @param hdmiPortNbr a greater than zero hdmi port number
     */
    void refreshPortMirror(int hdmiPortNbr) {
        if (hdmiPortNbr <= 0) {
            throw new IllegalArgumentException("hdmiPortNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_PORT_MIRROR_STATUS_FORMAT, hdmiPortNbr));
    }

    /**
     * Mutes/Unmutes the specified audio port.
     *
     * @param portNbr a greater than zero port number
     * @param mute true to mute, false to unmute
     */
    void setVolumeMute(int portNbr, boolean mute) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_VOLUME_MUTE_FORMAT, portNbr, mute ? "on" : "off"));
    }

    /**
     * Refreshes the volume mute for the given audio port.
     *
     * @param portNbr a greater than zero port number
     */
    void refreshVolumeMute(int portNbr) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_VOLUME_MUTE_FORMAT, portNbr, "sta"));
    }

    /**
     * Turn on/off the front panel IR.
     *
     * @param on true for on, false otherwise
     */
    void setIrOn(boolean on) {
        sendCommand(on ? CMD_IRON : CMD_IROFF);
    }

    /**
     * Refreshes the input port setting on the specified output port.
     *
     * @param portNbr a greater than zero port number
     */
    void refreshPortStatus(int portNbr) {
        if (portNbr <= 0) {
            throw new IllegalArgumentException("portNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_PORT_STATUS_FORMAT, portNbr));
    }

    /**
     * Refreshes all of the input port settings for all of the output ports.
     */
    private void refreshAllPortStatuses() {
        sendCommand(CMD_PORT_STATUS);
    }

    /**
     * Saves the current Input/Output scheme to the specified preset number.
     *
     * @param presetNbr a greater than 0 preset number
     */
    void saveIoSettings(int presetNbr) {
        if (presetNbr <= 0) {
            throw new IllegalArgumentException("presetNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_SAVEIO_FORMAT, presetNbr));
    }

    /**
     * Recalls the Input/Output scheme for the specified preset number.
     *
     * @param presetNbr a greater than 0 preset number
     */
    void recallIoSettings(int presetNbr) {
        if (presetNbr <= 0) {
            throw new IllegalArgumentException("presetNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_RECALLIO_FORMAT, presetNbr));
    }

    /**
     * Clears the Input/Output scheme for the specified preset number.
     *
     * @param presetNbr a greater than 0 preset number
     */
    void clearIoSettings(int presetNbr) {
        if (presetNbr <= 0) {
            throw new IllegalArgumentException("presetNbr must be greater than 0");
        }
        sendCommand(String.format(CMD_CLEARIO_FORMAT, presetNbr));
    }

    /**
     * Resets the matrix back to defaults.
     */
    void resetMatrix() {
        sendCommand(CMD_MATRIX_RESET);
    }

    /**
     * Sends the command and puts the thing into {@link ThingStatus#OFFLINE} if an IOException occurs
     *
     * @param command a non-null, non-empty command to send
     */
    private void sendCommand(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command cannot be null");
        }
        if (command.trim().length() == 0) {
            throw new IllegalArgumentException("command cannot be empty");
        }
        try {
            _session.sendCommand(command);
        } catch (IOException e) {
            _callback.statusChanged(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Exception occurred sending to Atlona: " + e);
        }
    }

    /**
     * Handles the switch power response. The first matching group should be "on" or "off"
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handlePowerResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 1) {
            switch (m.group(1)) {
                case "ON":
                    _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PRIMARY,
                            AtlonaPro3Constants.CHANNEL_POWER), OnOffType.ON);
                    break;
                case "OFF":
                    _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PRIMARY,
                            AtlonaPro3Constants.CHANNEL_POWER), OnOffType.OFF);
                    break;
                default:
                    logger.warn("Invalid power response: '{}'", resp);
            }
        } else {
            logger.warn("Invalid power response: '{}'", resp);
        }
    }

    /**
     * Handles the version (firmware) response. The first matching group should be the version
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleVersionResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 1) {
            _version = m.group(1);
            _callback.setProperty(AtlonaPro3Constants.PROPERTY_VERSION, _version);
        } else {
            logger.warn("Invalid version response: '{}'", resp);
        }
    }

    /**
     * Handles the type (model) response. The first matching group should be the type.
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleTypeResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 1) {
            _modelType = resp;
            _callback.setProperty(AtlonaPro3Constants.PROPERTY_TYPE, _modelType);
        } else {
            logger.warn("Invalid Type response: '{}'", resp);
        }
    }

    /**
     * Handles the panel lock response. The response is only on or off.
     *
     * @param resp the possibly null, possibly empty actual response
     */
    private void handlePanelLockResponse(String resp) {
        _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PRIMARY,
                AtlonaPro3Constants.CHANNEL_PANELLOCK), RSP_LOCK.equals(resp) ? OnOffType.ON : OnOffType.OFF);
    }

    /**
     * Handles the port power response. The first two groups should be the port nbr and either "on" or "off"
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handlePortPowerResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 2) {
            try {
                int portNbr = Integer.parseInt(m.group(1));
                switch (m.group(2)) {
                    case "on":
                        _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PORT,
                                portNbr, AtlonaPro3Constants.CHANNEL_PORTPOWER), OnOffType.ON);
                        break;
                    case "off":
                        _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PORT,
                                portNbr, AtlonaPro3Constants.CHANNEL_PORTPOWER), OnOffType.OFF);
                        break;
                    default:
                        logger.warn("Invalid port power response: '{}'", resp);
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid port power (can't parse number): '{}'", resp);
            }
        } else {
            logger.warn("Invalid port power response: '{}'", resp);
        }
    }

    /**
     * Handles the port all response. Simply calls {@link #refreshAllPortStatuses()}
     *
     * @param resp ignored
     */
    private void handlePortAllResponse(String resp) {
        refreshAllPortStatuses();
    }

    /**
     * Handles the port output response. This matcher can have multiple groups separated by commas. Find each group and
     * that group should have two groups within - an input port nbr and an output port number
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handlePortOutputResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }

        m.reset();
        while (m.find()) {
            try {
                int inPort = Integer.parseInt(m.group(1));
                int outPort = Integer.parseInt(m.group(2));

                _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PORT, outPort,
                        AtlonaPro3Constants.CHANNEL_PORTOUTPUT), new DecimalType(inPort));
            } catch (NumberFormatException e) {
                logger.warn("Invalid port output response (can't parse number): '{}'", resp);
            }
        }
    }

    /**
     * Handles the mirror response. The matcher should have two groups - an hdmi port number and an output port number.
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleMirrorResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 3) {
            try {
                int hdmiPortNbr = Integer.parseInt(m.group(1));

                // could be "off" (if mirror off), "on"/"Out" (with 3rd group representing out)
                String oper = StringUtils.trimToEmpty(m.group(2)).toLowerCase();

                if (oper.equals("off")) {
                    _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_MIRROR,
                            hdmiPortNbr, AtlonaPro3Constants.CHANNEL_PORTMIRRORENABLED), OnOffType.OFF);
                } else {
                    int outPortNbr = Integer.parseInt(m.group(3));
                    _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_MIRROR,
                            hdmiPortNbr, AtlonaPro3Constants.CHANNEL_PORTMIRROR), new DecimalType(outPortNbr));
                    _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_MIRROR,
                            hdmiPortNbr, AtlonaPro3Constants.CHANNEL_PORTMIRRORENABLED), OnOffType.ON);
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid mirror response (can't parse number): '{}'", resp);
            }
        } else {
            logger.warn("Invalid mirror response: '{}'", resp);
        }
    }

    /**
     * Handles the unmirror response. The first group should contain the hdmi port number
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleUnMirrorResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 1) {
            try {
                int hdmiPortNbr = Integer.parseInt(m.group(1));
                _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_MIRROR,
                        hdmiPortNbr, AtlonaPro3Constants.CHANNEL_PORTMIRROR), new DecimalType(0));
            } catch (NumberFormatException e) {
                logger.warn("Invalid unmirror response (can't parse number): '{}'", resp);
            }
        } else {
            logger.warn("Invalid unmirror response: '{}'", resp);
        }
    }

    /**
     * Handles the volume response. The first two group should be the audio port number and the level
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleVolumeResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 2) {
            try {
                int portNbr = Integer.parseInt(m.group(1));
                double level = Double.parseDouble(m.group(2));
                _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_VOLUME, portNbr,
                        AtlonaPro3Constants.CHANNEL_VOLUME), new DecimalType(level));
            } catch (NumberFormatException e) {
                logger.warn("Invalid volume response (can't parse number): '{}'", resp);
            }
        } else {
            logger.warn("Invalid volume response: '{}'", resp);
        }
    }

    /**
     * Handles the volume mute response. The first two group should be the audio port number and either "on" or "off
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleVolumeMuteResponse(Matcher m, String resp) {
        if (m == null) {
            throw new IllegalArgumentException("m (matcher) cannot be null");
        }
        if (m.groupCount() == 2) {
            try {
                int portNbr = Integer.parseInt(m.group(1));
                switch (m.group(2)) {
                    case "on":
                        _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_VOLUME,
                                portNbr, AtlonaPro3Constants.CHANNEL_VOLUME_MUTE), OnOffType.ON);
                        break;
                    case "off":
                        _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_VOLUME,
                                portNbr, AtlonaPro3Constants.CHANNEL_VOLUME_MUTE), OnOffType.OFF);
                        break;
                    default:
                        logger.warn("Invalid volume mute response: '{}'", resp);
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid volume mute (can't parse number): '{}'", resp);
            }
        } else {
            logger.warn("Invalid volume mute response: '{}'", resp);
        }
    }

    /**
     * Handles the IR Response. The response is either on or off
     *
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleIrLockResponse(String resp) {
        _callback.stateChanged(AtlonaPro3Utilities.createChannelID(AtlonaPro3Constants.GROUP_PRIMARY,
                AtlonaPro3Constants.CHANNEL_IRENABLE), RSP_IRON.equals(resp) ? OnOffType.ON : OnOffType.OFF);
    }

    /**
     * Handles the Save IO Response. Should have one group specifying the preset number
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleSaveIoResponse(Matcher m, String resp) {
        // nothing to handle
    }

    /**
     * Handles the Recall IO Response. Should have one group specifying the preset number. After updating the Recall
     * State, we refresh all the ports via {@link #refreshAllPortStatuses()}.
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleRecallIoResponse(Matcher m, String resp) {
        refreshAllPortStatuses();
    }

    /**
     * Handles the Clear IO Response. Should have one group specifying the preset number.
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleClearIoResponse(Matcher m, String resp) {
        // nothing to handle
    }

    /**
     * Handles the broadcast Response. Should have one group specifying the status.
     *
     * @param m the non-null {@link Matcher} that matched the response
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleBroadcastResponse(Matcher m, String resp) {
        // nothing to handle
    }

    /**
     * Handles the matrix reset response. The matrix will go offline immediately on a reset.
     *
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleMatrixResetResponse(String resp) {
        if (RSP_MATRIX_RESET.equals(resp)) {
            _callback.statusChanged(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "System is rebooting due to matrix reset");
        }
    }

    /**
     * Handles a command failure - we simply log the response as an error
     *
     * @param resp the possibly null, possibly empty actual response
     */
    private void handleCommandFailure(String resp) {
        logger.info(resp);
    }

    /**
     * This callback is our normal response callback. Should be set into the {@link SocketSession} after the login
     * process to handle normal responses.
     *
     * @author Tim Roberts
     *
     */
    private class NormalResponseCallback implements SocketSessionListener {

        @Override
        public void responseReceived(String response) {
            if (response == null || response == "") {
                return;
            }

            if (RSP_PING.equals(response)) {
                // ignore
                return;
            }

            Matcher m;

            m = _portStatusPattern.matcher(response);
            if (m.find()) {
                handlePortOutputResponse(m, response);
                return;
            }

            m = _powerStatusPattern.matcher(response);
            if (m.matches()) {
                handlePowerResponse(m, response);
                return;
            }

            m = _versionPattern.matcher(response);
            if (m.matches()) {
                handleVersionResponse(m, response);
                return;
            }

            m = _typePattern.matcher(response);
            if (m.matches()) {
                handleTypeResponse(m, response);
                return;
            }

            m = _portPowerPattern.matcher(response);
            if (m.matches()) {
                handlePortPowerResponse(m, response);
                return;
            }

            m = _volumePattern.matcher(response);
            if (m.matches()) {
                handleVolumeResponse(m, response);
                return;
            }

            m = _volumeMutePattern.matcher(response);
            if (m.matches()) {
                handleVolumeMuteResponse(m, response);
                return;
            }

            m = _portAllPattern.matcher(response);
            if (m.matches()) {
                handlePortAllResponse(response);
                return;
            }

            m = _portMirrorPattern.matcher(response);
            if (m.matches()) {
                handleMirrorResponse(m, response);
                return;
            }

            m = _portUnmirrorPattern.matcher(response);
            if (m.matches()) {
                handleUnMirrorResponse(m, response);
                return;
            }

            m = _saveIoPattern.matcher(response);
            if (m.matches()) {
                handleSaveIoResponse(m, response);
                return;
            }

            m = _recallIoPattern.matcher(response);
            if (m.matches()) {
                handleRecallIoResponse(m, response);
                return;
            }

            m = _clearIoPattern.matcher(response);
            if (m.matches()) {
                handleClearIoResponse(m, response);
                return;
            }

            m = _broadCastPattern.matcher(response);
            if (m.matches()) {
                handleBroadcastResponse(m, response);
                return;
            }

            if (RSP_IRON.equals(response) || RSP_IROFF.equals(response)) {
                handleIrLockResponse(response);
                return;
            }

            if (RSP_ALL.equals(response)) {
                handlePortAllResponse(response);
                return;
            }

            if (RSP_LOCK.equals(response) || RSP_UNLOCK.equals(response)) {
                handlePanelLockResponse(response);
                return;
            }

            if (RSP_MATRIX_RESET.equals(response)) {
                handleMatrixResetResponse(response);
                return;
            }

            if (response.startsWith(RSP_FAILED)) {
                handleCommandFailure(response);
                return;
            }

            logger.info("Unhandled response: {}", response);
        }

        @Override
        public void responseException(Exception e) {
            _callback.statusChanged(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Exception occurred reading from Atlona: " + e);
        }

    }

    /**
     * Special callback used during the login process to not dispatch the responses to this class but rather give them
     * back at each call to {@link NoDispatchingCallback#getResponse()}
     *
     * @author Tim Roberts
     *
     */
    private class NoDispatchingCallback implements SocketSessionListener {

        /**
         * Cache of responses that have occurred
         */
        private BlockingQueue<Object> _responses = new ArrayBlockingQueue<Object>(5);

        /**
         * Will return the next response from {@link #_responses}. If the response is an exception, that exception will
         * be thrown instead.
         *
         * @return a non-null, possibly empty response
         * @throws Exception an exception if one occurred during reading
         */
        String getResponse() throws Exception {
            final Object lastResponse = _responses.poll(5, TimeUnit.SECONDS);
            if (lastResponse instanceof String) {
                return (String) lastResponse;
            } else if (lastResponse instanceof Exception) {
                throw (Exception) lastResponse;
            } else if (lastResponse == null) {
                throw new Exception("Didn't receive response in time");
            } else {
                return lastResponse.toString();
            }
        }

        @Override
        public void responseReceived(String response) {
            try {
                _responses.put(response);
            } catch (InterruptedException e) {
            }
        }

        @Override
        public void responseException(Exception e) {
            try {
                _responses.put(e);
            } catch (InterruptedException e1) {
            }

        }

    }
}
