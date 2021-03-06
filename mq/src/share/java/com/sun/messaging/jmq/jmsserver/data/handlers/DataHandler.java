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
 * %W% %G%
 */ 

package com.sun.messaging.jmq.jmsserver.data.handlers;

import java.util.*;
import java.io.*;
import com.sun.messaging.jmq.jmsserver.data.TransactionUID;
import com.sun.messaging.jmq.jmsserver.data.TransactionList;
import com.sun.messaging.jmq.jmsserver.data.PacketRouter;
import com.sun.messaging.jmq.jmsserver.data.PacketHandler;
import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.core.Producer;
import com.sun.messaging.jmq.jmsserver.core.ProducerUID;
import com.sun.messaging.jmq.jmsserver.core.DestinationUID;
import com.sun.messaging.jmq.io.*;
import com.sun.messaging.jmq.jmsserver.service.Connection;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQBasicConnection;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.core.Consumer;
import com.sun.messaging.jmq.jmsserver.core.PacketReference;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.resources.BrokerResources;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.FaultInjection;
import com.sun.messaging.jmq.jmsserver.Broker;
import com.sun.messaging.jmq.jmsserver.BrokerStateHandler;
import com.sun.messaging.jmq.jmsserver.util.memory.MemoryGlobals;
import com.sun.messaging.jmq.util.selector.SelectorFormatException;
import com.sun.messaging.jmq.util.log.Logger;



/**
 * Handler class which deals with normal (topic or queue) JMS messages
 */
public class DataHandler extends PacketHandler 
{
    TransactionList translist = null;
    private static boolean DEBUG = false;
    

    private int msgProcessCnt = 0; // used for fault injection
    private FaultInjection fi = null;

    public DataHandler(TransactionList list) 
    {
        this.translist = list;
        fi = FaultInjection.getInjection();
        
    }

    /**
     * Method to handle normal data messages
     */
    public boolean handle(IMQConnection con, Packet msg) 
        throws BrokerException
    {
        boolean sentReply = false;

        return handle(con, msg, false);

    }
 
    /**
     * Method to handle normal/admin data messages
     */
    protected boolean handle(IMQConnection con, Packet msg, boolean isadmin) 
        throws BrokerException
    {
        if (DEBUG) {
        logger.log(Logger.INFO, "DataHandler:handle[Received JMS Message:"+
        msg.toString()+"]TID="+msg.getTransactionID()+" on connection "+con+", isadmin="+isadmin);
        }


        Hashtable props = null; // used for fault injection
        if (!isadmin && fi.FAULT_INJECTION) {
           msgProcessCnt ++; // for fault injection
           try {
               props = msg.getProperties();
           } catch (Exception ex) {
               props = new Properties();
           }
        } else {
           msgProcessCnt = 0;
        }


        boolean ack = msg.getSendAcknowledge();
        long cid = msg.getConsumerID();
        String refid = (((IMQBasicConnection)con).getDumpPacket() || ((IMQBasicConnection)con).getDumpOutPacket()) ?
                  msg.getSysMessageID().toString() : "";

        boolean isIndemp = msg.getIndempotent();

       String reason = null;
       List failedrefs = null;
       int status = Status.OK;
       boolean removeMsg = false;
       HashMap routedSet = null;
       boolean route = false;
       Producer pausedProducer = null;
       boolean transacted = false;
       try {
           pausedProducer = checkFlow(msg, con);
           transacted = (msg.getTransactionID() != 0);

            // OK .. handle Fault Injection
            if (!isadmin && fi.FAULT_INJECTION) {
                Map m = new HashMap();
                if (props != null)
                    m.putAll(props);
                m.put("mqMsgCount", new Integer(msgProcessCnt));
                m.put("mqIsTransacted", Boolean.valueOf(transacted));
                fi.checkFaultAndExit(FaultInjection.FAULT_SEND_MSG_1,
                     m, 2, false);
            }


            DestinationUID realduid = DestinationUID.getUID(msg.getDestination(),
                msg.getIsQueue());

            // get the list of "real" destination UIDs (this will be one if the
            // destination if not a wildcard and 0 or more if it is

            List duids = Destination.findMatchingIDs(realduid);

            boolean packetUsed = false;


            // IMPORTANT NOTE:
            // IF A MESSAGE IS BEING SENT TO A WILDCARD TOPIC AND A FAILURE
            // OCCURS ON ONE OF THE SENDS PROCESSING THAT MESSAGE WILL CONTINUE
            // A MESSAGE WILL BE LOGGED 
            //
            // THIS FACT NEEDS TO BE DOCUMENTED SOMEWHERE

            if (duids.size() == 0) {
                route = false; // nothing to do
            } else {
                Iterator itr = duids.iterator();
                while (itr.hasNext()) {
                    PacketReference ref = null;
                    Exception lastthr = null;
                    boolean isLast = false;
                    DestinationUID duid = (DestinationUID)itr.next();
                    isLast = !itr.hasNext();


                    Destination d = Destination.getDestination(duid);
                    try {

                        if (d == null) {
                            throw new BrokerException("Unknown Destination:" + msg.getDestination());
                        }
                        if (realduid.isWildcard() && d.isTemporary()) {
                            logger.log(Logger.DEBUG,"L10N-XXX: Wildcard production with destination name of "
                                         + realduid +  " to temporary destination " +
                                         d.getUniqueName() + " is not supported, ignoring");
                             continue;
                        }
                        if (realduid.isWildcard() && d.isInternal()) {
                            logger.log(Logger.DEBUG,"L10N-XXX: Wildcard production with destination name of "
                                         + realduid +  " to internal destination " +
                                         d.getUniqueName() + " is not supported, ignoring");
                             continue;
                        }

                        if (realduid.isWildcard() && d.isDMQ() ) {
                            logger.log(Logger.DEBUG,"L10N-XXX: Wildcard production with destination name of "
                                         + realduid +  " to the DeadMessageQueue" +
                                         d.getUniqueName() + " is not supported, ignoring");
                             continue;
                        }
                        if (pausedProducer != null) {
                             pauseProducer(d, duid, pausedProducer, con);
                             pausedProducer = null;
                        }
    
                        if (packetUsed) {
                            // create a new Packet for the message
                            // we need a new sysmsgid with it
                            Packet newp = new Packet();
                            newp.fill(msg);
                            newp.generateSequenceNumber(true);
                            newp.generateTimestamp(true);
                            newp.prepareToSend();
                            newp.generateSequenceNumber(false);
                            newp.generateTimestamp(false);
                            msg = newp;
                        }
                        packetUsed = true;
    
                        // OK generate a ref. This checks message size and
                        // will be needed for later operations
                        ref = createReference(msg, duid, con, isadmin);
    
                        // dont bother calling route if there are no messages
                        //
                        // to improve performance, we route and later forward
                        route |= queueMessage(d, ref, transacted);
    
                        // ok ... 
                        if (isLast && route && ack && !ref.isPersistent()) {
                            sendAcknowledge(refid, cid, status, con, reason, props, transacted);
                            ack = false;
                        }
    
    
                        Set s = routeMessage(transacted, ref, route, d);
                       
                        if (s != null && ! s.isEmpty()) {
                           if (routedSet == null)
                               routedSet = new HashMap();
                            routedSet.put(ref, s);
                        }

                        // handle producer flow control
                        pauseProducer(d, duid, pausedProducer, con);
                    } catch (Exception ex) {
                        if (ref != null) {
                            if (failedrefs == null)
                                failedrefs = new ArrayList();
                            failedrefs.add(ref);
                        }
                        lastthr = ex;
                        logger.log(Logger.DEBUG, BrokerResources.W_MESSAGE_STORE_FAILED,
                              con.toString(), ex);
                    } finally {
                        if (pausedProducer != null) {
                            pauseProducer(d, duid, pausedProducer, con);
                            pausedProducer = null;
                        }
                        if (isLast && lastthr != null) {
                            throw lastthr;
                        }
                    }
                } //while
            }

        } catch (BrokerException ex) {

            // dont log on dups if indemponent
            int loglevel = (isIndemp && ex.getStatusCode() 
                   == Status.NOT_MODIFIED) ? Logger.DEBUG 
                      : Logger.WARNING;
            logger.log(loglevel, 
                      BrokerResources.W_MESSAGE_STORE_FAILED,
                      con.toString(), ex);
            reason =  ex.getMessage();

            //LKS - we may want an improved error message in the wildcard case

            status = ex.getStatusCode();
        } catch (IOException ex) {
            logger.log(Logger.WARNING, BrokerResources.W_MESSAGE_STORE_FAILED,
                      con.toString(), ex);
            reason =  ex.getMessage();
            status = Status.ERROR;
        } catch (SecurityException ex) {
            logger.log(Logger.WARNING, BrokerResources.W_MESSAGE_STORE_FAILED,
                      con.toString(), ex);
            reason =  ex.getMessage();
            status = Status.FORBIDDEN;
        } catch (OutOfMemoryError err) {
            logger.logStack(Logger.WARNING, BrokerResources.W_MESSAGE_STORE_FAILED,
                      con.toString() + ":" + msg.getPacketSize(), err);
            reason =  err.getMessage();
            status = Status.ERROR;
        } catch (Exception ex) {

            logger.logStack(Logger.WARNING, BrokerResources.W_MESSAGE_STORE_FAILED,
                      con.toString(), ex);
             reason =  ex.getMessage();
             status = Status.ERROR;
        }

        if (status == Status.ERROR && failedrefs != null ) {
            // make sure we remove the message
            //
            // NOTE: we only want to remove the last failure (its too late
            // for the rest).  In the non-wildcard case, this will be the
            // only entry. In the wildcard cause, it will be the one that had an issue
            Iterator itr = failedrefs.iterator();
            while (itr.hasNext()) {
                PacketReference ref = (PacketReference)itr.next();
                Destination d = Destination.getDestination(ref.getDestinationUID());
                if (d != null)
                    cleanupOnError(d, ref);

            }
        }

        if (ack)
            sendAcknowledge(refid, cid, status, con, reason, props, transacted);

        if (route && routedSet != null) {
            Iterator itr = routedSet.keySet().iterator();
            while (itr.hasNext()) {
                PacketReference pktref = (PacketReference)itr.next();
                DestinationUID duid = pktref.getDestinationUID();
                Destination dest = Destination.getDestination(duid);
                Set s = (Set)routedSet.get(pktref);
                forwardMessage(dest, pktref, s);
            }
        }
 
        return isadmin; // someone else will free

    }

    public void sendAcknowledge(String refid,
          long cid, int status, IMQConnection con,
          String reason, Hashtable props /* fi only */, 
          boolean transacted /*fi only */)
    {
            // OK .. handle Fault Injection
            if (!con.isAdminConnection() && fi.FAULT_INJECTION) {
                Map m = new HashMap();
                if (props != null)
                    m.putAll(props);
                m.put("mqMsgCount", new Integer(msgProcessCnt));
                m.put("mqIsTransacted", Boolean.valueOf(transacted));
                fi.checkFaultAndExit(FaultInjection.FAULT_SEND_MSG_2,
                     m, 2, false);
            }
        // send the reply (if necessary)
            Packet pkt = new Packet(con.useDirectBuffers());
            pkt.setPacketType(PacketType.SEND_REPLY);
            pkt.setConsumerID(cid);
            Hashtable hash = new Hashtable();
            hash.put("JMQStatus", new Integer(status));
            if (reason != null)
                hash.put("JMQReason", reason);
            if (((IMQBasicConnection)con).getDumpPacket() ||
                ((IMQBasicConnection)con).getDumpOutPacket()) 
                hash.put("JMQReqID", refid);
            pkt.setProperties(hash);
            con.sendControlMessage(pkt);
            // OK .. handle Fault Injection
            if (!con.isAdminConnection() && fi.FAULT_INJECTION) {
                Map m = new HashMap();
                if (props != null)
                    m.putAll(props);
                m.put("mqMsgCount", new Integer(msgProcessCnt));
                m.put("mqIsTransacted", Boolean.valueOf(transacted));
                fi.checkFaultAndExit(FaultInjection.FAULT_SEND_MSG_3,
                     m, 2, false);
            }
    }

    public Producer checkFlow(Packet msg, IMQConnection con)
    {
           Producer pausedProducer = null;
           // check and clearout the F bit (before anything)
           if (msg.getFlowPaused()) {
               con.flowPaused(0);
               msg.setFlowPaused(false);
           } 
           long pid = msg.getProducerID();
           ProducerUID puid = new ProducerUID(pid);
           Producer p = Producer.getProducer(puid);
           if (p != null)
               p.addMsg(); // increment counter
           // see if we need to resume flow
           if (msg.getConsumerFlow()) {
               pausedProducer = p;
               if (pausedProducer == null) {
                   logger.log(Logger.INFO,"Internal Error: Unknown ProducerUID " + puid);
               } else if (pausedProducer.getConnectionUID() != con.getConnectionUID()) {
                   logger.log(Logger.INFO,"Internal Error: Producer " + pausedProducer
                      + " not on this connection " + con.getConnectionUID());

               }
               msg.setConsumerFlow(false);
           }
           return pausedProducer;

    }

    public PacketReference createReference(Packet msg, DestinationUID duid, IMQConnection con,
             boolean isadmin) throws BrokerException
    {
            // OK generate a ref. This checks message size and
            // will be needed for later operations
            PacketReference ref = PacketReference.createReference(msg,duid, con);
            if (isadmin) {
                ref.overridePersistence(false);
            }
            return ref;
    }

    public boolean queueMessage(Destination d, PacketReference ref, boolean transacted)
        throws BrokerException
    {

        return d.queueMessage(ref, transacted);
    }



    public Set routeMessage(boolean transacted, PacketReference ref, 
             boolean route, Destination d)
        throws BrokerException, SelectorFormatException
    {
            String reason = null;
            int status = Status.OK;
            Set s = null;
            if (transacted) {
                // if we are transacted, we just store the msg and
                // go on (we dont route)
                try {
                    ref.store();
                    translist.addMessage(ref.getTransactionID(), 
                        ref.getSysMessageID());
                } catch (Exception ex) {
                    ref.destroy();
                    logger.logStack((BrokerStateHandler.shuttingDown? 
                            Logger.DEBUG : Logger.WARNING),
                            BrokerResources.E_INTERNAL_BROKER_ERROR, 
                            "transaction failed", ex);
                    reason = "transaction failed: " + ex.getMessage();
                    status = Status.ERROR;
                    throw new BrokerException(reason, status);
                }
            } else {
                if (route)
                    s = d.routeNewMessage(ref);
            }
            return s;
     }


     public void cleanupOnError(Destination d, PacketReference ref)
     {
            try {
                d.removeMessage(ref.getSysMessageID(), null);
            } catch (Throwable thr) {
               // we should never throw anything .. but this is
               // a just in case [since we dont have any one else
               // to catch it in this case]
               // if something goes wrong removing the message 
               // there is nothing we can do
            }
     }


     public void forwardMessage(Destination d, PacketReference ref, Set s)
         throws BrokerException
     {
         d.forwardMessage(s, ref);
     }


     public void pauseProducer(Destination d, 
                               Producer pausedProducer, IMQConnection con)
     {
         pauseProducer(d, d.getDestinationUID(), pausedProducer, con);
     }

     public void pauseProducer(Destination d, DestinationUID duid, 
                               Producer pausedProducer, IMQConnection con)
     {
            // handle producer flow control
            if (pausedProducer != null) { 
                if (d != null) {
                    d.producerFlow(con, pausedProducer);
                } else if (duid != null) {
                    pausedProducer.sendResumeFlow(duid); 
                }
            }
     }
}
