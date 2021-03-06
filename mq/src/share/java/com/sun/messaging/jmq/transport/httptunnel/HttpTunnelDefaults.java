/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)HttpTunnelDefaults.java	1.12 06/28/07
 */ 

package com.sun.messaging.jmq.transport.httptunnel;

/**
 * Protocol constants, packet types, default values etc.
 */
public interface HttpTunnelDefaults {

    //
    // VARIOUS DEFAULT VALUES :
    //

    /**
     * Default listening port for the TCP connection between
     * the servlet and the <code>HttpTunnelServerDriver</code>.
     */
    public static final int DEFAULT_HTTP_TUNNEL_PORT = 7675;
    public static final int DEFAULT_HTTPS_TUNNEL_PORT = 7674;

    /**
     * Default connection retry attempt interval for the TCP
     * connection between the servlet and the
     * <code>HttpTunnelServerDriver</code>.
     */
    public static final int CONNECTION_RETRY_INTERVAL = 5000;

    /**
     * Default max connection retry wait for re-establish TCP
     * connection from HttpTunnelServerDriver with the servlet
     */
    public static final int MAX_CONNECTION_RETRY_WAIT = 900000;

    /**
     * Inactive connection abort interval.
     *
     * In 'continuous pull mode' (pullPeriod &lt= 0) the connection
     * is aborted if the servlet does not receive a pull request for
     * more than DEFAULT_CONNECTION_TIMEOUT_INTERVAL seconds.
     *
     * If pullPeriod is greater than 0, the connection is aborted
     * if the servlet does not receive a pull request for more than
     * (5 * pullPeriod) seconds.
     */
    public static final int DEFAULT_CONNECTION_TIMEOUT_INTERVAL = 60;

    /**
     * Maximum blocking period for HTTP pull requests in
     * continuous pull mode.
     */
    public static final int MAX_PULL_BLOCK_PERIOD = 60 * 1000;

    /**
     * Default listen queue backlog.
     */
    public static final int DEFAULT_BACKLOG = 256;

    /**
     * Transmit window size (number of packets).
     */
    public static final int DEFAULT_WINDOW_SIZE = 64;

    /**
     * Maximum data bytes per packet.
     */
    public static final int MAX_PACKETSIZE = 8192;

    /**
     * Initial packet retransmission period.
     */
    public static final int INITIAL_RETRANSMIT_PERIOD = 15000;

    /**
     * Minimum limit on measured retranmission timeout (based on
     * round trip delay).
     */
    public static final int MIN_RETRANSMIT_PERIOD = 1000;

    /**
     * Maximum limit on retransmission period binary exponential
     * backoff.
     */
    public static final int MAX_RETRANSMIT_PERIOD = 3 * 60 * 1000;

    /**
     * Number of repeat acknowledgements before a fast retransmit.
     */
    public static final int FAST_RETRANSMIT_ACK_COUNT = 3;

    public boolean ONE_PACKET_PER_REQUEST = false;

    //
    // PACKET TYPES :
    //

    /**
     * Packet type : Connection initiation request.
     */
    public static final int CONN_INIT_PACKET = 1;

    /**
     * Packet type : Connection initiation acknowledgement.
     */
    public static final int CONN_INIT_ACK = 2;

    /**
     * Packet type : Connection rejected.
     */
    public static final int CONN_REJECTED = 3;

    /**
     * Packet type : Application data.
     */
    public static final int DATA_PACKET = 4;

    /**
     * Packet type : Connection close request.
     */
    public static final int CONN_CLOSE_PACKET = 5;

    /**
     * Packet type : Acknowledgement.
     */
    public static final int ACK = 6;

    /**
     * Packet type : Cleanup connection table resources at the
     * servlet.
     */
    public static final int CONN_SHUTDOWN = 7;

    /**
     * Packet type : Link initialization information from
     * the <code>HttpTunnelServerDriver</code> to the servlet.
     * The payload contains the connection table information.
     * When the web server restarts, this is the first packet
     * received by the servlet so that it can restore its
     * connection table.
     */
    public static final int LINK_INIT_PACKET = 8;

    /**
     * Packet type : Connection aborted notification.
     */
    public static final int CONN_ABORT_PACKET = 9;

    /**
     * Packet type : Connection aborted notification.
     */
    public static final int CONN_OPTION_PACKET = 10;

    /**
     * Packet type : Listen state change notifications (server to servlet)
     */
    public static final int LISTEN_STATE_PACKET = 11;

    /**
     * Packet type : No-op filler packet. Used as payload for empty
     * responses.
     */
    public static final int NO_OP_PACKET = 12;

    /**
     * Packet type : Test packet.
     */
    public static final int DUMMY_PACKET = 100;

    //
    // CONNECTION OPTION TYPES :
    //

    /**
     * Connection option : Pull request period.
     * By default connections operate in 'continuous pull mode'.
     * Since this can hog web server resources, it is advisable to
     * use a positive 'pullPeriod' value. This value is used
     * by the client as a delay (in seconds) between pull requests,
     * when the connection is idle.
     */
    public static final int CONOPT_PULL_PERIOD = 1;

    /**
     * Connection option : Connection timeout.
     * If the client is unable to communicate with the web server for
     * the 'connectionTimeout' period, the connection is aborted by
     * the client driver..
     */
    public static final int CONOPT_CONNECTION_TIMEOUT = 2;
}

/*
 * EOF
 */
