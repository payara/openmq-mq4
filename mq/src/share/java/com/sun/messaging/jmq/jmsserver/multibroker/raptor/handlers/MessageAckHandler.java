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
 * @(#)MessageAckHandler.java	1.25 06/28/07
 */ 

package com.sun.messaging.jmq.jmsserver.multibroker.raptor.handlers;

import java.io.*;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import com.sun.messaging.jmq.io.*;
import com.sun.messaging.jmq.util.*;
import com.sun.messaging.jmq.jmsserver.FaultInjection;
import com.sun.messaging.jmq.jmsserver.util.*;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.core.*;
import com.sun.messaging.jmq.jmsserver.multibroker.ClusterGlobals;
import com.sun.messaging.jmq.jmsserver.multibroker.raptor.*;
import com.sun.messaging.jmq.jmsserver.multibroker.MessageBusCallback;

public class MessageAckHandler extends GPacketHandler {
    private static boolean DEBUG_CLUSTER_TXN =
        Globals.getConfig().getBooleanProperty(
                            Globals.IMQ + ".cluster.debug.txn");

    private static boolean DEBUG_CLUSTER_MSG =
        Globals.getConfig().getBooleanProperty(
        Globals.IMQ + ".cluster.debug.msg") || DEBUG_CLUSTER_TXN;

    private static boolean DEBUG = DEBUG_CLUSTER_TXN || DEBUG_CLUSTER_MSG;
 
    private FaultInjection fi = null;
    private HashMap fiackCounts = null; //for fi

    public MessageAckHandler(RaptorProtocol p) {
        super(p);
        fi = FaultInjection.getInjection();
        fiackCounts = new HashMap();
    }

    public void handle(MessageBusCallback cb, BrokerAddress sender, GPacket pkt) {
        if (pkt.getType() == ProtocolGlobals.G_MESSAGE_ACK) {
            handleMessageAck(cb, sender, pkt);
        }
        else if (pkt.getType() == ProtocolGlobals.G_MESSAGE_ACK_REPLY) {
            handleMessageAckReply(sender, pkt);
        }
        else {
            logger.log(logger.WARNING, br.E_INTERNAL_BROKER_ERROR,
                       "Cannot handle this packet :" + pkt.toLongString());
        }
    }

    public void handleMessageAck(MessageBusCallback cb, BrokerAddress sender, GPacket pkt) {
        ClusterMessageAckInfo cai = ClusterMessageAckInfo.newInstance(pkt, c);
        int ackType = cai.getAckType();
        Long txnID = cai.getTransactionID();

        if (fi.FAULT_INJECTION) {
        ClusterMessageAckInfo.CHECKFAULT(fiackCounts, ackType, txnID,
        FaultInjection.MSG_REMOTE_ACK_HOME_P, FaultInjection.STAGE_1);
        }

        UID ssid = cai.getMessageStoreSessionUID();
        UID bsid = cai.getMessageBrokerSessionUID();
        
        int cnt = 1;
        if (cai.getCount() != null) { 
            cnt = cai.getCount().intValue();
        }
        SysMessageID[] sysids = new SysMessageID[cnt];
        ConsumerUID[] cuids = new ConsumerUID[cnt];

        if (cnt > 0) {
            cai.initPayloadRead();
            for (int i = 0; i < cnt; i++) {
                try {
                    sysids[i] = cai.readPayloadSysMessageID();
                    cuids[i] = cai.readPayloadConsumerUID();
                } catch (Exception e) {
                logger.logStack(logger.ERROR, br.getKString(
                    br.E_CLUSTER_READ_PACKET_EXCEPTION, pkt.toString(), sender), e);
                sendReply(sender, cai, Status.ERROR, e.getMessage(), null, null, null);
                return;
                }
            }
        }

        if (DEBUG) {
        logger.log(logger.DEBUGHIGH, "MessageBus: Received message ack : "+cai.toString(sysids, cuids));
        }

        if ((ssid != null) != Globals.getHAEnabled()) {
            logger.log(logger.ERROR, br.E_INTERNAL_BROKER_ERROR,
                       "HA mode not match for message ack " + cai.toString(sysids, cuids));
            sendReply(sender, cai, Status.ERROR, "message HA mode not match", null, sysids, cuids);
            return;
        }
        if (p.isTakeoverTarget(selfAddress)) {
            logger.log(logger.ERROR, br.getKString(
                br.E_CLUSTER_MSG_ACK_THIS_BEING_TAKEOVER, cai.toString(sysids, cuids), selfAddress));
            sendReply(sender, cai, Status.ERROR, br.getKString(
                br.X_CLUSTER_MSG_ACK_HOME_BEING_TAKEOVER, cai.toString(sysids, cuids), selfAddress),
                null, sysids, cuids);
            return;
        }

        try {

        if (txnID != null) {
        cb.processRemoteAck2P(sysids, cuids, ackType, cai.getOptionalProps(), txnID, sender);
        } else {
        if (sysids.length > 1) {
        throw new BrokerException("Internal Error: Unexpected remote ack count "+sysids.length);
        }
        cb.processRemoteAck(sysids[0], cuids[0], ackType, cai.getOptionalProps());
        }
        if (fi.FAULT_INJECTION) {
        ClusterMessageAckInfo.CHECKFAULT(fiackCounts, ackType, txnID,
        FaultInjection.MSG_REMOTE_ACK_HOME_P, FaultInjection.STAGE_2);
        }

        sendReply(sender, cai, Status.OK, null, null, sysids, cuids); 

        if (fi.FAULT_INJECTION) {
        ClusterMessageAckInfo.CHECKFAULT(fiackCounts, ackType, txnID,
        FaultInjection.MSG_REMOTE_ACK_HOME_P, FaultInjection.STAGE_3);
        }

        } catch (Exception e) {
        	
        if (DEBUG) {
        logger.logStack(logger.WARNING, br.getKString(
          br.W_CLUSTER_REMOTE_MSG_ACK_FAILED, cai.toString(sysids, cuids), sender), e);
        } else {
        logger.log(logger.WARNING, br.getKString(
        br.W_CLUSTER_REMOTE_MSG_ACK_FAILED, cai.toString(sysids, cuids), sender)+": "+e.getMessage());
        }
        if (e instanceof BrokerException) {
        sendReply(sender, cai, (BrokerException)e, sysids, cuids);
        } else {
        sendReply(sender, cai, Status.ERROR, e.getMessage(), null, sysids, cuids);
        }

        }
    }
   
    private void sendReply(BrokerAddress sender, ClusterMessageAckInfo cai,
                           BrokerException e, SysMessageID[] sysids, ConsumerUID[] cuids) {
        if (!(e instanceof AckEntryNotFoundException)) {
            sendReply(sender, cai, e.getStatusCode(), e.getMessage(), null, sysids, cuids);
            return;
        }
        AckEntryNotFoundException aee = (AckEntryNotFoundException)e;
        sendReply(sender, cai, e.getStatusCode(), e.getMessage(), aee.getAckEntries(), sysids, cuids);
    }

    private void sendReply(BrokerAddress sender, ClusterMessageAckInfo cai,
                           int status, String reason, ArrayList[] aes,
                           SysMessageID[] sysids, ConsumerUID[] cuids) {
        if (cai.needReply()) {
            try {
                c.unicast(sender, cai.getReplyGPacket(status, reason, aes));
            } catch (IOException e) {
            Object args = new Object[] { ProtocolGlobals.getPacketTypeDisplayString(
                                         ProtocolGlobals.G_MESSAGE_ACK_REPLY),
                                         sender, cai.toString(sysids, cuids) };
            logger.logStack(logger.ERROR, br.getKString(
                            br.E_CLUSTER_SEND_REPLY_FAILED, args), e);
            }
        }
    }

    public void handleMessageAckReply(BrokerAddress sender, GPacket pkt) {
        logger.log(logger.DEBUG,
            "MessageBus: Received G_MESSAGE_ACK_REPLY ("+ClusterMessageAckInfo.getAckAckType(pkt)+
            ")  from "+sender+ " : STATUS = "+ClusterMessageAckInfo.getAckAckStatus(pkt));
        p.receivedMessageAckReply(sender, pkt);
    }

}

/*
 * EOF
 */
