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
 * @(#)MessageConsumerImpl.java	1.59 06/27/07
 */ 

package com.sun.messaging.jmq.jmsclient;

import javax.jms.*;

import java.util.Vector;
import java.util.logging.Level;
import java.io.IOException;
import java.io.PrintStream;

import com.sun.messaging.AdministeredObject;
import com.sun.messaging.jmq.io.ReadOnlyPacket;
import com.sun.messaging.jmq.io.SysMessageID;

import com.sun.messaging.jmq.jmsclient.resources.ClientResources;

/** A client uses a message consumer to receive messages from a Destination.
  * It is created by passing a Destination to a create message consumer method
  * supplied by a Session.
  *
  * <P>The parent interface for all message consumers.
  *
  * <P>A message consumer can be created with a message selector. This allows
  * the client to restrict the messages delivered to the message consumer to
  * those that match the selector.
  *
  * <P>A client may either synchronously receive a message consumer's messages
  * or have the consumer asynchronously deliver them as they arrive.
  *
  * <P>A client can request the next message from a message consumer using one
  * of its receive methods. There are several variations of receive that allow a
  * client to poll or wait for the next message.
  *
  * <P>A client can register a MessageListener object with a message consumer.
  * As messages arrive at the message consumer, it delivers them by calling the
  * MessageListener's onMessage method.
  *
  * <P>It is a client programming error for a MessageListener to throw an
  * exception.
  *
  * @see         javax.jms.QueueReceiver
  * @see         javax.jms.TopicSubscriber
  * @see         javax.jms.Session
  */

public class MessageConsumerImpl extends Consumer
                 implements MessageConsumer, Traceable {

    protected MessageListener messageListener = null;

    protected SessionImpl session = null;

    protected ReceiveQueue receiveQueue = null;

    private boolean syncReadFlag = true;

    protected SysMessageID lastDeliveredID = null;

    //used in onMessage().
    //private boolean successfulDelivery = true;

    //if message listener throws exception more than this
    //number, we close this consumer.
    //default to 5.
    //protected int runtimeExceptionMax = 5;
    //run time exception counter
    //protected int runtimeExceptionCounter = 0;

    private boolean pendingPrefetch = false;

    //XXX - message conversion from pkt to Message when receive().
    public MessageConsumerImpl (SessionImpl session,
                                Destination dest,
                                String messageSelector,
                                //boolean isTopic,
                                boolean noLocal) throws JMSException {

        super(session.getConnection(), dest, messageSelector, noLocal);
        this.session = session;
        init();
    }

    //public MessageConsumerImpl (SessionImpl session,
    //                            Destination dest) throws JMSException {

    //    super(session.getConnection(), dest);
    //    this.session = session;
    //    init();
    //}

    /**
     * The subclass calls init() method.
     */
    public MessageConsumerImpl (SessionImpl session,
                                Destination dest) throws JMSException {
        super(session.getConnection(), dest);
        this.session = session;
    }


    /**
     *
     */
    protected void init() throws JMSException {
        try {
            //Verify (if temp) that it's on it's own connection
            checkConsumerCreation();

            receiveQueue = new ReceiveQueue();

            /**
             * Set ack mode.  The ack mode is used by the broker.
             * This must be done before addInterest() call.
             */
            if (session.getTransacted() == false) {
                acknowledgeMode = session.acknowledgeMode;
            }

            //if not register here, problems occur when clients do things like:
            // publish();
            // receive();
            addInterest();

            if ( session.sessionLogger.isLoggable(Level.FINE) ) {
                logLifeCycle(ClientResources.I_CONSUMER_CREATED);
            }

        } catch (JMSException jmse) {
            ExceptionHandler.throwJMSException(jmse);
        }
    }

    protected void setInterestId (Long id) {
        lastDeliveredID = null;
        pendingPrefetch = false;
        super.setInterestId(id);
    }

    private void addInterest() throws JMSException {
        session.checkConsumerCreation();
        registerInterest();
        //XXX PROTOCOL2.1
        //session.addMessageConsumer (this);
    }

    private void removeInterest() throws JMSException {
        session.removeMessageConsumer( this );
        deregisterInterest();
    }

    private synchronized void setSyncReadFlag (boolean flag) {
        syncReadFlag = flag;
    }

    protected synchronized boolean getSyncReadFlag() {
        return syncReadFlag;
    }

    /**
     * If message listener is set, no receive() can be used.
     * See JMS spec 1.0.2 - 4.4.7
     */
    protected void checkReceive() throws JMSException {

        checkState();

        if ( getSyncReadFlag() == false ) {
            String errorString = AdministeredObject.cr.getKString(AdministeredObject.cr.X_SYNC_ASYNC_RECEIVER);

            JMSException jmse =
            new JMSException (errorString, AdministeredObject.cr.X_SYNC_ASYNC_RECEIVER);

            ExceptionHandler.throwJMSException(jmse);
        }

        if ( session.failoverOccurred &&
             (session.acknowledgeMode == Session.CLIENT_ACKNOWLEDGE) ) {

            //throw session invalidated exception.
            String errorString = AdministeredObject.cr.getKString(AdministeredObject.cr.X_SESSION_INVALID_CLIENTACK);

            JMSException jmse =
            new JMSException (errorString, AdministeredObject.cr.X_SESSION_INVALID_CLIENTACK);

            ExceptionHandler.throwJMSException(jmse);
        }

        //transacted session validation.
        session.checkFailOver();

        //session.checkSessionState();
    }

    /**
     * Lock receiveQueue.  Receive() will be blocked.
     */
    protected void stop() {
        receiveQueue.stop();
    }

    protected void stopNoWait() {
        receiveQueue.stopNoWait();
    }

    /**
     *  unlock receive queue. Message will be delived to receive() call if
     *  message in the receiveQueue.
     */
    protected void start() {
        receiveQueue.start();
    }

    protected SessionQueue getReceiveQueue() {
        return receiveQueue;
    }

    protected SessionImpl getSession() {
        return session;
    }

    protected Long getReadQueueId() {
        return session.getSessionId();
    }

    /* This method is called by SessionReader.
     * Messages are delivered to the receiveQueue by default.  After
     * MessageListener is set, messages are delivered to the MessageListener.
     */
    protected void
    onMessage (MessageImpl message) throws JMSException {
        //System.out.println ("MessageConsumer on message ...");

        if (getSyncReadFlag() == true) {
            receiveQueue.enqueueNotify (message);
            //message is not acked until receive().
        } else {
            //setting message listener after receive() is called could cause
            //messages left in the receive queue.  The following statement is to
            //ensure all messages are delivered.
        	if ( receiveQueue.isEmpty()  ) {
        		//For normal situation, receiveQueue.isEmpty() above should be true
        		//and the code execution comes here immediately if the listener is
        		//set.
        		deliverAndAcknowledge (message);
        	} else {
        		
        		//if there are messages in the receive queue,
        		//add the current message to the end of the receive queue
        		//this change is due to bug 6469383, 6469397, 6466942.
        		receiveQueue.enqueueNotify(message);
        		
        		//deliver all messages in the receive queue to the late listener
        		onMessageToListenerFromReceiveQueue();
        	}
        }
    }

    /**
     * Setting message listener after receive() is called could cause
     * messages left in the receive queue.  The following block is to
     * ensure all messages are delivered.
     */
    protected void onMessageToListenerFromReceiveQueue() throws JMSException {

        if ( receiveQueue.isEmpty() == false ) {
            MessageImpl message;
            //The message listener is set so late that we have messages
            //in the queue.
            //synchronized to ensure the client can not change mind until
            //we finished.
            synchronized (receiveQueue) {
            	
                //int size = receiveQueue.size();
                //for ( int i=0; i< size;i++) {
            	while ( receiveQueue.isEmpty() == false ) {
            		
            		//get next message
                    message = (MessageImpl) receiveQueue.dequeue();
                    
                    /**
                     * Do not deliver if this is a null object.  This could happen if app rollbacks a
                     * transaction while we are still in the loop. 
                     * 
                     * bug 6466942 -- Null pointer exception showed in client log.
                     */
                    if ( message != null ) {    	
                    	deliverAndAcknowledge (message);
                    }
                    
                }
                
                //reset the flag.
                session.sessionQueue.setListenerLate (false);
            }
        }
    }

    /**
     * Call message listener and acknowledge
     */
    protected void deliverAndAcknowledge(MessageImpl message) throws JMSException {

		// always update the current message before deliver to the listener.
		// bug 6469383, 6469397
		this.session.sessionReader.setCurrentMessage(message);

		try {
            session.setIsMessageListenerThread(true);
            if (!isDMQConsumer && message._isExpired()) {
                session.acknowledgeExpired(message);
                return;
            }

			try {
				messageListener.onMessage(message);
			} catch (Exception e) {

				// let the client know they are doing something wrong
				Debug.printStackTrace(e);
				// for non-transacted, auto-acknowledge session
				if (session.getTransacted() == false && session.acknowledgeMode != Session.CLIENT_ACKNOWLEDGE) {
					message.doAcknowledge = false;
					// set redeliver flag to true
					message.setJMSRedelivered(true);
					// redeliver to the listener
					try {
						messageListener.onMessage(message);
						message.doAcknowledge = true;
					} catch (Exception e1) {
						Debug.printStackTrace(e1);
					}
				}
			}
			// do acknowledge -- flag only affects auto ack and dups ok ack.
			if (message.doAcknowledge && !message.consumerInRA) {
				if (!session.getTransacted() || !connection.isAppTransactedAck()) {
					session.acknowledge(message);
				}
			}

		} finally {
			session.setIsMessageListenerThread(false);
		}

		// XXX PROTOCOL3.5
		// Remember the last delivered message id.
		if (!(session.getTransacted() && connection.isAppTransactedAck())) {
			lastDeliveredID = message.getMessageID();
		}
	}

    /** Get the message consumer's MessageListener.
      *
      * @return the listener for the message consumer, or null if there isn't
      * one set.
      *
      * @exception JMSException if JMS fails to get message
      *                         listener due to some JMS error
      * @see javax.jms.MessageConsumer#setMessageListener
      */

    public MessageListener getMessageListener() throws JMSException {
        checkState();
        return messageListener;
    }


    /** Set the message consumer's MessageListener.
      *
      * <P>Setting the message listener to null is the equivalent of
      * unsetting the message listener for the message consumer.
      *
      * <P>Calling the setMessageListener method of MessageConsumer
      * while messages are being consumed by an existing listener
      * or the consumer is being used to synchronously consume messages
      * is undefined.
      *
      * @param messageListener the messages are delivered to this listener
      *
      * @exception JMSException if JMS fails to set message
      *                         listener due to some JMS error
      * @see javax.jms.MessageConsumer#getMessageListener
      */

    public void
    setMessageListener(MessageListener listener) throws JMSException {

        checkState();

        messageListener = listener;

        //there are only two mode: sync or async.  NOT BOTH.
        if ( listener == null ) {
            setSyncReadFlag ( true );
        } else {
            setSyncReadFlag ( false );
            if ( receiveQueue.size() > 0 ) {
                //notify SessionReader to deliver to the listener
                session.sessionQueue.setListenerLateNotify();
            }
        }
    }


    /** Receive the next message produced for this message consumer.
      *
      * <P>This call blocks indefinitely until a message is produced
      * or until this message consumer is closed.
      *
      * <P>If this receive is done within a transaction, the message
      * remains on the consumer until the transaction commits.
      *
      * @exception JMSException if JMS fails to receive the next
      *                         message due to some error.
      *
      * @return the next message produced for this message consumer, or
      * null if this message consumer is concurrently closed.
      *
      */

    public Message receive() throws JMSException {
        return receive (0);
    }

    /** Receive the next message that arrives within the specified
      * timeout interval.
      *
      * <P>This call blocks until a message arrives, the
      * timeout expires, or this message consumer is closed.
      * A timeout of zero never expires and the call blocks indefinitely.
      *
      * @param timeout the timeout value (in milliseconds)
      *
      * @exception JMSException if JMS fails to receive the next
      *                         message due to some error.
      * @return the next message produced for this message consumer, or
      * return null if timeout expires or message consumer concurrently closed.
      */

    public Message
    receive(long timeout) throws JMSException {
        MessageImpl message = null;

        while (true) {

        checkReceive();

        try {
            if (noprefetch && pendingPrefetch) {
                session.doPrefetch(this);
                pendingPrefetch = false;
            }

            message = (MessageImpl) receiveQueue.dequeueWait(timeout);

            if ( message != null) {
                if (!isDMQConsumer && message._isExpired()) {
                    if (noprefetch) {
                        pendingPrefetch = true;
                        session.acknowledgeExpired(message, false);
                    } else {
                        session.acknowledgeExpired(message, true);
                    }
                    continue;
                }
                if (!session.getTransacted() || !connection.isAppTransactedAck()) {
                    if (noprefetch) {
                        pendingPrefetch = true;
                        session.acknowledge(message, false);
                    } else {
                        session.acknowledge (message, true);
                    }
                }

                //XXX PROTOCOL3.5
                // Remember the last delivered message id.
                lastDeliveredID = message.getMessageID();
            } else {
				// if message is null and the connection is broken, throws a
				// JMSException
				// so that the MessageConsumer knows that this is caused by the
				// MQ internal error -
				// bug 6485924- consumer.receive() returns null instead of
				// throwing an exception.
				if (this.session.connection.connectionIsBroken) {

						String errorString = AdministeredObject.cr
								.getKString(ClientResources.X_CONSUMER_CLOSED);
						// construct JMSException
						JMSException jmse = new com.sun.messaging.jms.JMSException(errorString,
								ClientResources.X_CONSUMER_CLOSED);
					
						if (session.connection.readChannel.savedJMSException != null) {
							jmse.setLinkedException(session.connection.readChannel.savedJMSException);
						}

					ExceptionHandler.throwJMSException(jmse);
				}
				
			}
            break;
            
		} finally {
			receiveQueue.setReceiveInProcess(false);
		}

        } //while

        return message;
    }


    /**
	 * Receive the next message if one is immediately available.
	 * 
	 * @exception JMSException
	 *                if JMS fails to receive the next message due to some
	 *                error.
	 * @return the next message produced for this message consumer, or null if
	 *         one is not available.
	 */

    public Message
    receiveNoWait() throws JMSException {

        MessageImpl message = null;
        while (true) {

        checkReceive();

        try {
            //if the queue is locked/Connection.stop() is called,
            //no messages should be received.
            if ( receiveQueue.getIsLocked() ) {
                return null;
            }

            receiveQueue.setReceiveInProcess (true);

            if (noprefetch && pendingPrefetch) {
                session.doPrefetch(this);
                pendingPrefetch = false;
            }

            message = (MessageImpl) receiveQueue.dequeue();

            if ( message != null) {
                if (!isDMQConsumer && message._isExpired()) {
                    if (noprefetch) {
                        pendingPrefetch = true;
                        session.acknowledgeExpired(message, false);
                    } else {
                        session.acknowledgeExpired(message, true);
                    }
                    continue;
                }
                if (noprefetch) {
                    pendingPrefetch = true;
                    session.acknowledge(message, false);
                } else {
                    session.acknowledge (message, true);
                }

                //XXX PROTOCOL3.5
                // Remember the last delivered message id.
                lastDeliveredID = message.getMessageID();
            }
            break;
        } finally {
            receiveQueue.setReceiveInProcess (false);
        }

        } //while

        return message;
    }


    /** Since a provider may allocate some resources on behalf of a
      * MessageConsumer outside the JVM, clients should close them when they
      * are not needed. Relying on garbage collection to eventually reclaim
      * these resources may not be timely enough.
      *
      * <P>This call blocks until a receive or message listener in progress
      * has completed. A blocked message consumer receive call
      * returns null when this message consumer is closed.
      *
      * @exception JMSException if JMS fails to close the consumer
      *                         due to some error.
      */

    public void
    close() throws JMSException {

        int reduceFlowCount = 0;

        /**
         * This is to avoid closing twice issues.
         */
        synchronized ( receiveQueue ) {

            if ( isClosed ) {
                return; //was closed
            }

            isClosed = true;

        }

        try {

            /**
             * if call is not not from message listener,
             * we need to stop session reader.
             */
            if (Thread.currentThread() != session.sessionReader.sessionThread) {
                session.sessionQueue.stop(true);
            }

            //This call blocks until a receive or message listener in progress
            //has completed.
            stop();

            //messages in the receive queue.
            reduceFlowCount = receiveQueue.size();

            //This make sure receive() returns null
            receiveQueue.close();

            //do not deregister durable subscriber
            //do not call if connection is broken
            if (session.connection.isBroken() == false && (session.connection.recoverInProcess == false)) {

                if (session.dupsOkAckOnTimeout) {
                    session.syncedDupsOkCommitAcknowledge();
                }

                removeInterest();
            }

            if (session.connection.getBrokerProtocolLevel() <
                com.sun.messaging.jmq.io.PacketType.VERSION350) {
                if (session.isTransacted ||
                    (session.acknowledgeMode == Session.CLIENT_ACKNOWLEDGE) ||
                    (session.acknowledgeMode == Session.DUPS_OK_ACKNOWLEDGE)) {
                    //remove unacked messages if any -- 4934856
                    session.removeUnAckedMessages(interestId);
                }
            }

            /**
             * Remove messages in the session queue for this message
             * consumer.
             */
            removeUndeliveredMessages();

            /**
             * If closing consumer inside message listener, we want to
             * make sure that the current message is not acknowledged.
             * After returned from message delivery, this flag is checked
             * (in deliverAndAcknowledge method).
             */
            if (Thread.currentThread() == session.sessionReader.sessionThread) {
                session.sessionReader.currentMessage.doAcknowledge = false;
            } else {
                //restart session reader
                session.sessionQueue.start();
            }

            //debug message
            if (debug) {
                Debug.println("message consumer closed ...");
                Debug.println(this);
            }

            messageListener = null;

            isClosed = true;

        } finally {
            //bug 6271876 -- connection flow control
            session.resetConnectionFlowControl(reduceFlowCount);

            if (session.sessionLogger.isLoggable(Level.FINE) ) {
                logLifeCycle(ClientResources.I_CONSUMER_CLOSED);
            }

        }
    }

    /**
     * Remove undelivered messages for the closed consumer.
     * -- PRIORITYQ
     */
    protected void removeUndeliveredMessages() throws JMSException {

        int reduceFlowCount = 0;

        Object[] obj = session.sessionQueue.toArray();
        int size = obj.length;

        /**
         * Only need to verify if there are messages in the sessionq
         */
        if ( size > 0 ) {
            //local vars.
            Vector removeq = new Vector();

            //consumer ID for this consumer.
            long consumerID = interestId.longValue();

            /**
             * find undelivered messages for this consumer and put
             * them in the removeq
             */
            for ( int i=0; i<size; i++ ) {
                ReadOnlyPacket pkt = (ReadOnlyPacket) (obj[i]);
                if ( (pkt != null) && (pkt.getConsumerID() == consumerID) ) {
                    removeq.addElement(pkt);
                }
            }

            reduceFlowCount = removeq.size();

            /**
             * remove undelivered messages in the sessionq.
             */
            for ( int i=0; i<removeq.size(); i++) {

                if (debug) {
                Debug.println("removing msg from sessionq: "+removeq.elementAt(i));
                }

                session.sessionQueue.remove( removeq.elementAt(i) );
            }
            //bug 6271876 -- connection flow control
            session.resetConnectionFlowControl(reduceFlowCount);
        }
    }


    protected SysMessageID getLastDeliveredID() {
        return lastDeliveredID;
    }

    public void dump (PrintStream ps) {

        ps.println ("------ MessageConsumerImpl dump ------");

        ps.println ("Interest ID: " + getInterestId());
        ps.println ("is registered: " + getIsRegistered());
        //ps.println ("isTopic: " + getIsTopic());
        ps.println ("is durable: " + getDurable());

        if ( durable ) {
            ps.println ("durableName: " + getDurableName());
        }

        ps.println ("destination: " + getDestination());
        ps.println ("selector: " + messageSelector);

        if ( receiveQueue != null ) {
            receiveQueue.dump(ps);
        } else {
            ps.println ("receiveQueue is null.");
        }
    }

    protected java.util.Hashtable getDebugState(boolean verbose) {
        java.util.Hashtable ht = super.getDebugState(verbose);

        ht.put("# pending", String.valueOf(receiveQueue.size()));
        ht.put("syncReadFlag", String.valueOf(syncReadFlag));
        if (verbose)
            ht.put("receiveQueue", receiveQueue);

        return ht;
    }

    public Object TEST_GetAttribute(String name) {
        if (name.startsWith("FlowControl")) {
            return session.readChannel.flowControl.TEST_GetAttribute(
                name, this);
        }

        return null;
    }

    public void logLifeCycle (String key) {

        if ( session.sessionLogger.isLoggable(Level.FINE) ) {
            session.sessionLogger.log(Level.FINE, key, this);
        }

    }

    public String toString() {

        String destName = null;

        try {
            destName = ((com.sun.messaging.Destination) destination).getName();
        } catch (Exception e) {
            ;
        }

         return session.toString() +
             ", ConsumerID=" + getInterestId() +
             ", DestName=" + destName;
    }
}

