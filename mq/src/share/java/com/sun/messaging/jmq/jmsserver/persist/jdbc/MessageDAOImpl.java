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
 * @(#)MessageDAOImpl.java	1.55 08/17/07
 */ 

package com.sun.messaging.jmq.jmsserver.persist.jdbc;

import com.sun.messaging.jmq.jmsserver.persist.Store;
import com.sun.messaging.jmq.jmsserver.persist.HABrokerInfo;
import com.sun.messaging.jmq.util.log.Logger;
import com.sun.messaging.jmq.jmsserver.util.*;
import com.sun.messaging.jmq.jmsserver.core.ConsumerUID;
import com.sun.messaging.jmq.jmsserver.core.DestinationUID;
import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.FaultInjection; 
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.cluster.BrokerState;
import com.sun.messaging.jmq.io.SysMessageID;
import com.sun.messaging.jmq.io.Packet;
import com.sun.messaging.jmq.io.DestMetricsCounters;
import com.sun.messaging.jmq.io.Status;

import java.util.*;
import java.sql.*;
import java.io.*;

/**
 * This class implement a generic MessageDAO.
 */
class MessageDAOImpl extends BaseDAOImpl implements MessageDAO {

    protected String tableName;
    protected static int msgColumnType = -Integer.MAX_VALUE;

    // SQLs
    protected String insertSQL;
    protected String updateDestinationSQL;
    protected String deleteSQL;
    protected String deleteByDstSQL;
    protected String selectSQL;
    protected String selectMsgsBySessionSQL;
    protected String selectMsgsByBrokerSQL;
    protected String selectMsgIDsAndDstIDsByBrokerSQL;
    protected String selectForUpdateSQL;
    protected String selectBrokerSQL;
    protected String selectCountByDstSQL;
    protected String selectCountByBrokerSQL;
    protected String selectCountByConsumerAckedSQL;
    protected String selectIDsByDstSQL;
    protected String selectMsgsByDstSQL;
    protected String selectExistSQL;
    protected String selectCanInsertSQL;

    private FaultInjection fi = null;

    /**
     * Constructor
     * @throws com.sun.messaging.jmq.jmsserver.util.BrokerException
     */
    MessageDAOImpl() throws BrokerException {
        fi = FaultInjection.getInjection();

        // Initialize all SQLs
        DBManager dbMgr = DBManager.getDBManager();

        tableName = dbMgr.getTableName( TABLE_NAME_PREFIX );

        insertSQL = new StringBuffer(128)
            .append( "INSERT INTO " ).append( tableName )
            .append( " ( " )
            .append( ID_COLUMN ).append( ", " )
            .append( MESSAGE_SIZE_COLUMN ).append( ", " )
            .append( STORE_SESSION_ID_COLUMN ).append( ", " )
            .append( DESTINATION_ID_COLUMN ).append( ", " )
            .append( TRANSACTION_ID_COLUMN ).append( ", " )
            .append( CREATED_TS_COLUMN ).append( ", " )
            .append( MESSAGE_COLUMN )
            .append( ") VALUES ( ?, ?, ?, ?, ?, ?, ? )" )
            .toString();

        updateDestinationSQL = new StringBuffer(128)
            .append( "UPDATE " ).append( tableName )
            .append( " SET " )
            .append( DESTINATION_ID_COLUMN ).append( " = ?, " )
            .append( MESSAGE_SIZE_COLUMN ).append( " = ?, " )
            .append( MESSAGE_COLUMN ).append( " = ?" )
            .append( " WHERE " )
            .append( ID_COLUMN ).append( " = ?" )
            .toString();

        deleteSQL = new StringBuffer(128)
            .append( "DELETE FROM " ).append( tableName )
            .append( " WHERE " )
            .append( ID_COLUMN ).append( " = ?" )
            .toString();

        deleteByDstSQL = new StringBuffer(128)
            .append( "DELETE FROM " ).append( tableName )
            .append( " WHERE " )
            .append( DESTINATION_ID_COLUMN ).append( " = ?" )
            .append( " AND " )
            .append( STORE_SESSION_ID_COLUMN )
            .append( " IN (SELECT " ).append( StoreSessionDAO.ID_COLUMN )
            .append( " FROM " )
            .append( dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append( " WHERE " )
            .append( StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?)" )
            .toString();

        selectCountByBrokerSQL = new StringBuffer(128)
            .append( "SELECT COUNT(*) FROM " )
            .append(   tableName ).append( " msgTbl, " )
            .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(   " sesTbl" )
            .append( " WHERE sesTbl." )
            .append( StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?" )
            .append( " AND sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .append(   " = msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .toString();

        // This query is a bit more complex because we want the SQL to count
        // the # of msgs, calculate the total size, and also determine if the
        // destination does exists
        selectCountByDstSQL = new StringBuffer(128)
            .append( "SELECT totalmsg, totalsize, " )
            .append( DestinationDAO.ID_COLUMN )
            .append( " FROM " )
            .append( dbMgr.getTableName( DestinationDAO.TABLE_NAME_PREFIX  ) )
            .append( ", (SELECT COUNT(msgTbl." )
            .append(     ID_COLUMN ).append( ") AS totalmsg, SUM(" )
            .append(     MESSAGE_SIZE_COLUMN ).append( ") AS totalsize")
            .append(   " FROM " ).append( tableName ).append( " msgTbl, " )
            .append(     dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(     " sesTbl" )
            .append(   " WHERE sesTbl." )
            .append(     StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?" )
            .append(   " AND sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .append(     " = msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .append(   " AND " )
            .append(     DESTINATION_ID_COLUMN ).append( " = ?) msgtable" )
            .append( " WHERE " )
            .append( DestinationDAO.ID_COLUMN ).append( " = ?" )
            .toString();

        selectCountByConsumerAckedSQL = new StringBuffer(128)
            .append( "SELECT COUNT(*) AS total, SUM(CASE WHEN " )
            .append( ConsumerStateDAO.STATE_COLUMN )
            .append( " = " ).append( Store.INTEREST_STATE_ACKNOWLEDGED )
            .append( " THEN 1 ELSE 0 END) AS totalAcked" )
            .append( " FROM " )
            .append( dbMgr.getTableName( ConsumerStateDAO.TABLE_NAME_PREFIX ) )
            .append( " WHERE " )
            .append( ConsumerStateDAO.MESSAGE_ID_COLUMN ).append( " = ?" )
            .toString();

        selectSQL = new StringBuffer(128)
            .append( "SELECT " )
            .append( MESSAGE_COLUMN )
            .append( " FROM " ).append( tableName )
            .append( " WHERE " )
            .append( ID_COLUMN ).append( " = ?" )
            .toString();

        selectMsgsBySessionSQL = new StringBuffer(128)
            .append( "SELECT " )
            .append( MESSAGE_COLUMN )
            .append( " FROM " ).append( tableName )
            .append( " WHERE " )
            .append( STORE_SESSION_ID_COLUMN ).append( " = ?" )
            .toString();

        selectMsgsByBrokerSQL = new StringBuffer(128)
            .append( "SELECT " )
            .append( MESSAGE_COLUMN )
            .append( " FROM " ).append( tableName ).append( " msgTbl, " )
            .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(   " sesTbl" )
            .append( " WHERE sesTbl." )
            .append(   StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?" )
            .append( " AND sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .append(   " = msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .toString();

        selectMsgIDsAndDstIDsByBrokerSQL = new StringBuffer(128)
            .append( "SELECT msgTbl." )
            .append( ID_COLUMN ).append( ", msgTbl." )
            .append( DESTINATION_ID_COLUMN )
            .append( " FROM " ).append( tableName ).append( " msgTbl, " )
            .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(   " sesTbl" )
            .append( " WHERE sesTbl." )
            .append(   StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?" )
            .append( " AND sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .append(   " = msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .toString();

        selectForUpdateSQL = new StringBuffer(128)
            .append( selectSQL )
            .append( " FOR UPDATE" )
            .toString();

        selectBrokerSQL = new StringBuffer(128)
            .append( "SELECT " )
            .append( StoreSessionDAO.BROKER_ID_COLUMN )
            .append( " FROM " ).append( tableName ).append( " msgTbl, " )
            .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(   " sesTbl" )
            .append( " WHERE msgTbl." )
            .append(   ID_COLUMN ).append( " = ?" )
            .append( " AND msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .append(   " = sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .toString();

        selectIDsByDstSQL = new StringBuffer(128)
            .append( "SELECT msgTbl." )
            .append( ID_COLUMN )
            .append( " FROM " ).append( tableName ).append( " msgTbl, " )
            .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(   " sesTbl" )
            .append( " WHERE sesTbl." )
            .append(   StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?" )
            .append( " AND sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .append(   " = msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .append( " AND " )
            .append( DESTINATION_ID_COLUMN ).append( " = ?" )
            .toString();

        selectMsgsByDstSQL = new StringBuffer(128)
            .append( "SELECT msgTbl." )
            .append( MESSAGE_COLUMN )
            .append( " FROM " ).append( tableName ).append( " msgTbl, " )
            .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
            .append(   " sesTbl" )
            .append( " WHERE sesTbl." )
            .append(   StoreSessionDAO.BROKER_ID_COLUMN ).append( " = ?" )
            .append( " AND sesTbl." ).append( StoreSessionDAO.ID_COLUMN )
            .append(   " = msgTbl." ).append( STORE_SESSION_ID_COLUMN )
            .append( " AND " )
            .append( DESTINATION_ID_COLUMN ).append( " = ?" )
            .toString();

        selectExistSQL = new StringBuffer(128)
            .append( "SELECT " )
            .append( ID_COLUMN )
            .append( " FROM " ).append( tableName )
            .append( " WHERE " )
            .append( ID_COLUMN ).append( " = ?" )
            .toString();

        // A single query that can tell us if the msg & destination exist and
        // broker is being taken over by other broker
        // If value of col1 is greater than 0, then the msg does exist.
        // If value of col2 is greater than 0, then the dst does exist.
        // If value of col3 is greater than 0, then broker is being taken over.
        StringBuffer strBuff = new StringBuffer(256)
            .append( "SELECT MAX(msgTS), MAX(dstTS), MAX(bkrState) FROM (" )
            .append(   "SELECT " ).append( CREATED_TS_COLUMN )
            .append(   " AS msgTS, 0 AS dstTS, 0 AS bkrState FROM " ).append( tableName )
            .append(   " WHERE " ).append( ID_COLUMN ).append( " = ?" )
            .append( " UNION " )
            .append(   "SELECT 0 AS msgTS, " ).append( DestinationDAO.CREATED_TS_COLUMN )
            .append(   " AS dstTS, 0 AS bkrState FROM " )
            .append(   dbMgr.getTableName( DestinationDAO.TABLE_NAME_PREFIX ) )
            .append(   " WHERE " ).append( DestinationDAO.ID_COLUMN ).append( " = ?" );
        if ( Globals.getHAEnabled() ) {
            strBuff
            .append( " UNION " )
            .append(   "SELECT 0 AS msgTS, 0 AS dstTS, " ).append( BrokerDAO.STATE_COLUMN )
            .append(   " AS bkrState FROM " )
            .append(   dbMgr.getTableName( BrokerDAO.TABLE_NAME_PREFIX ) )
            .append(   " WHERE " ).append( BrokerDAO.ID_COLUMN ).append( " = ? AND " )
            .append(   BrokerDAO.STATE_COLUMN ).append( " IN (" )
            .append(   BrokerState.I_FAILOVER_PENDING ).append( ", " )
            .append(   BrokerState.I_FAILOVER_STARTED ).append( ", " )
            .append(   BrokerState.I_FAILOVER_COMPLETE ).append( ", " )
            .append(   BrokerState.I_FAILOVER_FAILED ).append( ")" );
        }
        strBuff.append( ") tbl" );
        selectCanInsertSQL = strBuff.toString();
    }

    /**
     * Get the prefix name of the table.
     * @return table name
     */
    public final String getTableNamePrefix() {
        return TABLE_NAME_PREFIX;
    }

    /**
     * Get the name of the table.
     * @return table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Insert a new entry.
     * @param conn database connection
     * @param message the message to be persisted
     * @param dstUID the destination
     * @param conUIDs an array of interest ids whose states are to be
     *      stored with the message
     * @param states an array of states
     * @param storeSessionID the store session ID that owns the msg
     * @param createdTime timestamp
     * @param checkMsgExist check if message & destination exist in the store
     * @exception BrokerException if a message with the same id exists
     *  in the store already
     */
    public void insert( Connection conn, DestinationUID dstUID, Packet message,
        ConsumerUID[] conUIDs, int[] states, long storeSessionID,
        long createdTime, boolean checkMsgExist, boolean replaycheck )
        throws BrokerException {

        String dstID = null;
        if ( dstUID != null ) {
            dstID = dstUID.toString();
        }

        insert( conn, dstID, message, conUIDs, states, storeSessionID,
                createdTime, checkMsgExist, replaycheck );
    }

    public void insert( Connection conn, String dstID, Packet message,
        ConsumerUID[] conUIDs, int[] states, long storeSessionID,
        long createdTime, boolean checkMsgExist, boolean replaycheck )
         throws BrokerException {

        SysMessageID sysMsgID = (SysMessageID)message.getSysMessageID();
        String id = sysMsgID.getUniqueName();
        int size = message.getPacketSize();
        long txnID = message.getTransactionID();

        if ( dstID == null ) {
            dstID = DestinationUID.getUniqueString(
                message.getDestination(), message.getIsQueue() );
        }

        boolean myConn = false;
        PreparedStatement pstmt = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( false );
                myConn = true;
            }

            if ( checkMsgExist ) {
                try {
                    canInsertMsg( conn, id, dstID, dbMgr.getBrokerID() );
                } catch (BrokerException e) {
                    if (!(e instanceof StoreBeingTakenOverException) &&
                          e.getStatusCode() != Status.CONFLICT &&
                          e.getStatusCode() != Status.NOT_FOUND) {
                        e.setSQLRecoverable(true);
                        e.setSQLReplayCheck(replaycheck);
                    }
                    if (!(e instanceof StoreBeingTakenOverException) &&
                          e.getStatusCode() == Status.CONFLICT) {
                        if (replaycheck) {
                            if ( conUIDs != null ) {
                                HashMap map = null;
                                try {
                                    map = dbMgr.getDAOFactory().getConsumerStateDAO().getStates( conn, sysMsgID );
                                } catch (BrokerException ee) {
                                    e.setSQLRecoverable(true);
                                    e.setSQLReplayCheck(true);
                                    throw e;
                                }
                                List cids = Arrays.asList(conUIDs);
                                Iterator itr = map.entrySet().iterator();
                                Map.Entry pair = null;
                                ConsumerUID cid = null;
                                while (itr.hasNext()) {
                                    pair = (Map.Entry)itr.next();
                                    cid = (ConsumerUID)pair.getKey();
                                    int st = ((Integer)pair.getValue()).intValue();
                                    for (int i = 0; i < conUIDs.length; i++) {
                                        if (conUIDs[i].equals(cid)) {
                                            if (states[i] == st) {
                                                cids.remove(conUIDs[i]);
                                            }
                                        }
                                    }
                                }
                                if (cids.size() == 0) {
                                    logger.log(Logger.INFO, BrokerResources.I_CANCEL_SQL_REPLAY, id+"["+dstID+"]"+cids);
                                    return;
                                }
                            } else {
                                logger.log(Logger.INFO, BrokerResources.I_CANCEL_SQL_REPLAY, id+"["+dstID+"]");
                                return;
                            }
                        }
                    }
                    throw e;
                }
            }

            try {
                // Get the msg as bytes array
                byte[] data = message.getBytes();

                pstmt = conn.prepareStatement( insertSQL );
                pstmt.setString( 1, id );
                pstmt.setInt( 2, size );
                pstmt.setLong( 3, storeSessionID );
                pstmt.setString( 4, dstID );
                Util.setLong( pstmt, 5, (( txnID == 0 ) ? -1 : txnID) );
                pstmt.setLong( 6, createdTime );
                Util.setBytes( pstmt, 7, data );

                pstmt.executeUpdate();

                // Store the consumer's states if any
                if ( conUIDs != null ) {
                    dbMgr.getDAOFactory().getConsumerStateDAO().insert(
                        conn, dstID, sysMsgID, conUIDs, states, false, false );
                }

                // Commit all changes
                if ( myConn ) {
                    conn.commit();
                }
            } catch ( Exception e ) {
                myex = e;
                boolean replayck = false;
                try {
                    if ( (conn != null) && !conn.getAutoCommit() ) {
                        conn.rollback();
                    }
                } catch ( SQLException rbe ) {
                    replayck = true;
                    logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED, rbe );
                }

                Exception ex;
                if ( e instanceof BrokerException ) {
                    throw (BrokerException)e;
                } else if ( e instanceof IOException ) {
                    ex = DBManager.wrapIOException("[" + insertSQL + "]", (IOException)e);
                } else if ( e instanceof SQLException ) {
                    ex = DBManager.wrapSQLException("[" + insertSQL + "]", (SQLException)e);
                } else {
                    ex = e;
                }

                BrokerException ee = new BrokerException(
                    br.getKString( BrokerResources.X_PERSIST_MESSAGE_FAILED,
                    id ), ex );
                ee.setSQLRecoverable(true);
                if (replayck) {
                    ee.setSQLReplayCheck(true);
                }
                throw ee;
            }
        } catch (BrokerException e) {
            myex = e;
            throw e;
        } finally {
            if ( myConn ) {
                Util.close( null, pstmt, conn, myex );
            } else {
                Util.close( null, pstmt, null, myex );
            }
        }
    }

    /**
     * Move a message to another destination.
     * @param conn database connection
     * @param message the message
     * @param fromDst the destination
     * @param toDst the destination to move to
     * @param conUIDs an array of interest ids whose states are to be
     *      stored with the message
     * @param states an array of states
     * @throws IOException
     * @throws BrokerException
     */
    public void moveMessage( Connection conn, Packet message,
        DestinationUID fromDst, DestinationUID toDst, ConsumerUID[] conUIDs,
        int[] states ) throws IOException, BrokerException {

	SysMessageID sysMsgID = (SysMessageID)message.getSysMessageID().clone();
        String id = sysMsgID.getUniqueName();
        int size = message.getPacketSize();
        
        boolean myConn = false;
        PreparedStatement pstmt = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( false );
                myConn = true;
            }

            // Get the msg as bytes array
            byte[] data = message.getBytes();

            pstmt = conn.prepareStatement( updateDestinationSQL );
            pstmt.setString( 1, toDst.toString() );
            pstmt.setInt( 2, size );
            Util.setBytes( pstmt, 3, data );
            pstmt.setString( 4, id );

            if ( pstmt.executeUpdate() == 0 ) {
                // We're assuming the entry does not exist
                throw new BrokerException(
                    br.getKString( BrokerResources.E_MSG_NOT_FOUND_IN_STORE,
                        id, fromDst ), Status.NOT_FOUND );
            }

            /**
             * Update consumer states:
             * 1. remove the old states
             * 2. re-insert the states
             */
            ConsumerStateDAO conStateDAO = dbMgr.getDAOFactory().getConsumerStateDAO();
            conStateDAO.deleteByMessageID( conn, sysMsgID );
            if ( conUIDs != null || states != null ) {
                conStateDAO.insert(
                    conn, toDst.toString(), sysMsgID, conUIDs, states, false, false );
            }

            // Commit all changes
            if ( myConn ) {
                conn.commit();
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED, rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + updateDestinationSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            Object[] args = { id, fromDst, toDst };
            throw new BrokerException(
                br.getKString( BrokerResources.X_MOVE_MESSAGE_FAILED,
                args ), ex );
        } finally {
            if ( myConn ) {
                Util.close( null, pstmt, conn, myex );
            } else {
                Util.close( null, pstmt, null, myex );
            }
        }
    }

    /**
     * Delete an existing entry.
     * @param conn Database Connection
     * @param dstUID the destination
     * @param sysMsgID the SysMessageID
     * @throws BrokerException
     */
    public void delete( Connection conn, DestinationUID dstUID,
        SysMessageID sysMsgID, boolean replaycheck ) throws BrokerException {

        String id = sysMsgID.getUniqueName();

        boolean myConn = false;
        PreparedStatement pstmt = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( false );
                myConn = true; // Set to true since this is our connection
            }

            if (fi.FAULT_INJECTION) {
                HashMap fips = new HashMap();
                fips.put(FaultInjection.DST_NAME_PROP, 
                     DestinationUID.getUniqueString(dstUID.getName(), dstUID.isQueue()));
                fi.checkFaultAndExit(FaultInjection.FAULT_TXN_COMMIT_1_8, fips, 2, false);
            }
                     
            // Now delete the message
            boolean deleteFailed = false;
            pstmt = conn.prepareStatement( deleteSQL );
            pstmt.setString( 1, id );
            if ( pstmt.executeUpdate() == 0 ) {
                deleteFailed = true;
            } else {
                // For HA mode, make sure this broker still owns the store
                if ( Globals.getHAEnabled() ) {
                    String brokerID = dbMgr.getBrokerID();
                    BrokerDAO dao = dbMgr.getDAOFactory().getBrokerDAO();
                    if ( dao.isBeingTakenOver( conn, brokerID ) ) {
                        BrokerException be = new StoreBeingTakenOverException(
                            br.getKString( BrokerResources.E_STORE_BEING_TAKEN_OVER ) );

                        try {
                            HABrokerInfo bkrInfo = dao.getBrokerInfo( conn, brokerID );
                            logger.log( Logger.ERROR, br.getKString(
                                BrokerResources.X_INTERNAL_EXCEPTION,
                                bkrInfo.toString() ), be );
                        } catch (Throwable t) { /* Ignore error */ }

                        throw be;
                    }
                }
            }
            
            // Delete states
            dbMgr.getDAOFactory().getConsumerStateDAO().deleteByMessageID( conn, sysMsgID );

            if (deleteFailed && replaycheck) {
                logger.log(Logger.INFO, BrokerResources.I_CANCEL_SQL_REPLAY, id+"["+dstUID+"]delete");
                return;
            }
            if (deleteFailed) {
                // We'll assume the msg does not exist
                throw new BrokerException(
                    br.getKString( BrokerResources.E_MSG_NOT_FOUND_IN_STORE,
                        id, dstUID ), Status.NOT_FOUND );
            }

            // Check whether to commit or not
            if ( myConn ) {
                conn.commit();
            }
        } catch ( Exception e ) {
            myex = e;
            boolean replayck = false;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                replayck = true;
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED, rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                if (!(e instanceof StoreBeingTakenOverException) &&
                    ((BrokerException)e).getStatusCode() != Status.NOT_FOUND) { 
                    ((BrokerException)e).setSQLRecoverable(true);
                    ((BrokerException)e).setSQLReplayCheck(replayck);
                }
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + deleteSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            BrokerException be = new BrokerException(
                br.getKString( BrokerResources.X_REMOVE_MESSAGE_FAILED,
                id ), ex );
            be.setSQLRecoverable(true);
            be.setSQLReplayCheck(replayck);
            throw be;
        } finally {
            if ( myConn ) {
                Util.close( null, pstmt, conn, myex );
            } else {
                Util.close( null, pstmt, null, myex );
            }
        }
    }

    /**
     * Delete all messages from a destination for the current broker.
     * @param conn Database Connection
     * @param dstUID the destination
     * @return the number of msgs deleted
     * @throws BrokerException
     */
    public int deleteByDestination( Connection conn, DestinationUID dstUID )
        throws BrokerException {

        int msgCount;
        String dstID = dstUID.toString();

        boolean myConn = false;
        PreparedStatement pstmt = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( false );
                myConn = true; // Set to true since this is our connection
            }

            dbMgr.getDAOFactory().getDestinationDAO().checkDestination( conn, dstID );

            // First delete consumer states for the destination since
            // Consumer State table is a child table, i.e. it has to join with
            // the Message table to select message IDs for the destination that
            // will be deleted.
            dbMgr.getDAOFactory().getConsumerStateDAO().deleteByDestination( conn, dstUID );

            // Now delete all msgs associated with the destination
            pstmt = conn.prepareStatement( deleteByDstSQL );
            pstmt.setString( 1, dstID );
            pstmt.setString( 2, dbMgr.getBrokerID() );
            msgCount = pstmt.executeUpdate();

            // Check whether to commit or not
            if ( myConn ) {
                conn.commit();
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED, rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + deleteByDstSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_REMOVE_MESSAGES_FOR_DST_FAILED,
                dstID ), ex );
        } finally {
            if ( myConn ) {
                Util.close( null, pstmt, conn, myex );
            } else {
                Util.close( null, pstmt, null, myex );
            }
        }

        return msgCount;
    }

    /**
     * Delete all entries.
     * @param conn Database Connection
     * @throws com.sun.messaging.jmq.jmsserver.util.BrokerException
     */
    public void deleteAll( Connection conn )
        throws BrokerException {

        String whereClause = null;        
        if ( Globals.getHAEnabled() ) {
            DBManager dbMgr = DBManager.getDBManager();

            // Only delete messages that belong to the running broker,
            // construct the where clause for the delete statement:
            //   DELETE FROM mqmsg41cmycluster
            //   WHERE EXISTS
            //     (SELECT id FROM mqses41cmycluster
            //      WHERE  id = mqmsg41cmycluster.store_session_id AND
            //             broker_id = 'mybroker')
            whereClause = new StringBuffer(128)
                .append( "EXISTS (SELECT " )
                .append(   StoreSessionDAO.ID_COLUMN )
                .append(   " FROM " )
                .append(   dbMgr.getTableName( StoreSessionDAO.TABLE_NAME_PREFIX ) )
                .append(   " WHERE " )
                .append(   StoreSessionDAO.ID_COLUMN ).append( " = " )
                .append(   tableName ).append( "." ).append( STORE_SESSION_ID_COLUMN )
                .append(   " AND " )
                .append(   StoreSessionDAO.BROKER_ID_COLUMN ).append( " = '" )
                .append(   dbMgr.getBrokerID() ).append( "')" )
                .toString();
        }

        deleteAll( conn, whereClause, null, 0 );
    }

    /**
     * Get the broker ID that owns the specified message.
     * @param conn database connection
     * @param id the system message id of the message
     * @return the broker ID
     * @throws BrokerException
     */
    public String getBroker( Connection conn, DestinationUID dstUID, String id )
        throws BrokerException {

        String brokerID = null;

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            if ( conn == null ) {
                conn = DBManager.getDBManager().getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectBrokerSQL );
            pstmt.setString( 1, id );
            rs = pstmt.executeQuery();

            if ( rs.next() ) {
                brokerID = rs.getString( 1 );
            } else {
                throw new BrokerException(
                    br.getKString( BrokerResources.E_MSG_NOT_FOUND_IN_STORE,
                        id, dstUID ), Status.NOT_FOUND );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectBrokerSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectBrokerSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_LOAD_MESSAGE_FAILED, id), ex);
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return brokerID;
    }

    /**
     * Get the message.
     * @param conn database connection
     * @param dstUID the destination
     * @param sysMsgID the SysMessageID
     * @return Packet the message
     * @throws BrokerException if message does not exist in the store
     */
    public Packet getMessage( Connection conn, DestinationUID dstUID,
        SysMessageID sysMsgID ) throws BrokerException {

        return getMessage( conn, dstUID, sysMsgID.toString() );
    }

    /**
     * Get a Message.
     * @param conn database connection
     * @param dstUID the destination
     * @param id the system message id of the message
     * @return Packet the message
     * @throws BrokerException
     */
    public Packet getMessage( Connection conn, DestinationUID dstUID, String id )
        throws BrokerException {

        Packet msg = null;

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            if ( conn == null ) {
                conn = DBManager.getDBManager().getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectSQL );
            pstmt.setString( 1, id );
            rs = pstmt.executeQuery();
            msg = (Packet)loadData( rs, true );
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof IOException ) {
                ex = DBManager.wrapIOException("[" + selectSQL + "]", (IOException)e);
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_LOAD_MESSAGE_FAILED, id), ex);
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        if ( msg == null ) {
            throw new BrokerException(
                br.getKString( BrokerResources.E_MSG_NOT_FOUND_IN_STORE,
                    id, dstUID ), Status.NOT_FOUND );
        }

        return msg;
    }

    /**
     * Get all message IDs for a broker.
     * @param conn database connection
     * @param brokerID the broker ID
     * @return a List of all messages the specified broker owns
     * @throws BrokerException
     */
    public List getMessagesByBroker( Connection conn, String brokerID )
        throws BrokerException {

        List list = Collections.EMPTY_LIST;

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( true );
                myConn = true;
            }

            // Retrieve all messages for the target broker
            pstmt = conn.prepareStatement( selectMsgsByBrokerSQL );
            pstmt.setString( 1, brokerID );
            rs = pstmt.executeQuery();
            list = (List)loadData( rs, false );
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED, rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof IOException ) {
                ex = DBManager.wrapIOException("[" + selectMsgsByBrokerSQL + "]", (IOException)e);
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectMsgsByBrokerSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.E_LOAD_MSG_FOR_BROKER_FAILED,
                    brokerID ), ex );
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return list;
    }

    /**
     * Get all message IDs and corresponding destination IDs for a broker.
     * @param conn database connection
     * @param brokerID the broker ID
     * @return a Map of all messages corresponding destinations the specified broker owns
     * @throws BrokerException
     */
    public Map getMsgIDsAndDstIDsByBroker( Connection conn, String brokerID )
        throws BrokerException {

        Map map = new HashMap();

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( true );
                myConn = true;
            }

            if ( brokerID == null ) {
                brokerID = dbMgr.getBrokerID();
            }

            // Retrieve all message IDs and corresponding for the target broker
            pstmt = conn.prepareStatement( selectMsgIDsAndDstIDsByBrokerSQL );
            pstmt.setString( 1, brokerID );
            rs = pstmt.executeQuery();

            while ( rs.next() ) {
                map.put( rs.getString( 1 ), rs.getString( 2 ) );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED, rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof IOException ) {
                ex = DBManager.wrapIOException("[" + selectMsgIDsAndDstIDsByBrokerSQL + "]", (IOException)e);
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectMsgIDsAndDstIDsByBrokerSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.E_LOAD_MSG_FOR_BROKER_FAILED,
                    brokerID ), ex );
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return map;
    }

    /**
     * Get all message IDs for a destination and current/local broker.
     * @param conn database connection
     * @param dst the destination
     * @param brokerID the broker ID
     * @return a List of all persisted destination names.
     * @throws BrokerException
     */
    public List getIDsByDst( Connection conn, Destination dst, String brokerID )
        throws BrokerException {

        ArrayList list = new ArrayList();

        String dstID = dst.getUniqueName();

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = dbMgr.getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectIDsByDstSQL );
            pstmt.setString( 1, brokerID );
            pstmt.setString( 2, dstID );
            rs = pstmt.executeQuery();

            while ( rs.next() ) {
                String msgID = rs.getString( 1 );
                list.add( msgID );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectIDsByDstSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectIDsByDstSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_LOAD_MESSAGES_FOR_DST_FAILED ),
                dstID, ex );
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return list;
    }

    /**
     * Return an enumeration of all persisted messages for the given destination.
     * Use the Enumeration methods on the returned object to fetch and load
     * each message sequentially.
     *
     * This method is to be used at broker startup to load persisted
     * messages on demand.
     *
     * @param dst the destination
     * @param brokerID the broker ID
     * @return an enumeration of all persisted messages, an empty
     *		enumeration will be returned if no messages exist for the
     *		destionation
     * @exception BrokerException if an error occurs while getting the data
     */
    public Enumeration messageEnumeration( Destination dst, String brokerID )
        throws BrokerException {

        Connection conn = null;
        Exception myex = null;
        try {
            DBManager dbMgr = DBManager.getDBManager();
            conn = dbMgr.getConnection( true );

            // Verify destination exists
            dbMgr.getDAOFactory().getDestinationDAO().checkDestination(
                conn, dst.getUniqueName() );

            Iterator msgIDItr = getIDsByDst( conn, dst, brokerID ).iterator();
            return new MsgEnumeration( dst.getDestinationUID(), this, msgIDItr );
        } catch (BrokerException e) {
            myex = e;
            throw e;
        } finally {
            Util.close( null, null, conn, myex );
        }
    }

    /*
     * This method is to be used at broker startup to load persisted
     * messages on demand.  
     *
     * This method returns a message enumeration that uses ResultSet cursor.
     * Caller must call Store.closeEnumeration() after use 
     *
     * @param dst the destination
     * @param brokerID the broker ID
     * @return an enumeration of all persisted messages, an empty
     *      enumeration will be returned if no messages exist for the
     *      destionation
     * @exception BrokerException if an error occurs while getting the data
     */
    public Enumeration messageEnumerationCursor( Destination dst, String brokerID )
        throws BrokerException {

        String dstID = dst.getUniqueName();

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            DBManager dbMgr = DBManager.getDBManager();
            conn = dbMgr.getConnection( true );


            // Verify destination exists
            dbMgr.getDAOFactory().getDestinationDAO().checkDestination( conn, dstID );


            pstmt = conn.prepareStatement( selectMsgsByDstSQL );
            pstmt.setString( 1, brokerID );
            pstmt.setString( 2, dstID );
            rs = pstmt.executeQuery();

            return new MessageEnumeration( rs, pstmt, conn,
                                           selectMsgsByDstSQL,
                                           this, Globals.getStore() );
        } catch ( Throwable e ) {
            Throwable ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectMsgsByDstSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            Util.close( rs, pstmt, conn, ex );

            throw new BrokerException(
                br.getKString( BrokerResources.X_LOAD_MESSAGES_FOR_DST_FAILED ),
                dstID, ex );
        }
    }


    /**
     * Check if a a message has been acknowledged by all interests, i.e. consumers.
     * @param conn database connection
     * @param dstUID the destination
     * @param sysMsgID sysMsgID the SysMessageID
     * @return true if all interests have acknowledged the message;
     * false if message has not been routed or acknowledge by all interests
     * @throws BrokerException
     */
    public boolean hasMessageBeenAcked( Connection conn, DestinationUID dstUID,
        SysMessageID sysMsgID ) throws BrokerException {

        int total = -1;
        int totalAcked = -1;
        String id = sysMsgID.getUniqueName();

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            if ( conn == null ) {
                conn = DBManager.getDBManager().getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectCountByConsumerAckedSQL );
            pstmt.setString( 1, id );
            rs = pstmt.executeQuery();

            if ( rs.next() ) {
                total = rs.getInt( 1 );
                totalAcked = rs.getInt( 2 );
            } else {
                throw new BrokerException(
                    br.getKString( BrokerResources.E_MSG_NOT_FOUND_IN_STORE,
                        id, dstUID ), Status.NOT_FOUND );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectCountByConsumerAckedSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                if (((BrokerException)e).getStatusCode() != Status.NOT_FOUND) {
                    ((BrokerException)e).setSQLRecoverable(true);
                }
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectCountByConsumerAckedSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            BrokerException be = new BrokerException(
                br.getKString( BrokerResources.X_LOAD_MESSAGE_FAILED,
                id ), ex );
            be.setSQLRecoverable(true);
            throw be;
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        // Return true if all interests have acknowledged. To be safe,
        // message is considered unrouted if interest list is empty (total = 0).
        if ( total > 0 && total == totalAcked ) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check whether the specified message exists.
     * @param conn database connection
     * @param id the system message id of the message to be checked
     * @return return true if the specified message exists
     */
    public boolean hasMessage( Connection conn, String id ) throws BrokerException {

        boolean found = false;

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            if ( conn == null ) {
                conn = DBManager.getDBManager().getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectExistSQL );
            pstmt.setString( 1, id );
            rs = pstmt.executeQuery();

            if ( rs.next() ) {
                found = true;
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectExistSQL+"]", rbe );
            }
 
            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectExistSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_LOAD_MESSAGE_FAILED,
                id ), ex );
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return found;
    }

    /**
     * Check whether the specified message exists.
     * @param conn
     * @param dstID the destination
     * @param mid the system message id of the message to be checked
     * @throws BrokerException if the message does not exist in the store
     */
    public void checkMessage( Connection conn, String dstID, String mid )
        throws BrokerException {

        if ( !hasMessage( conn, mid ) ) {
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"[checkMessage():"+mid+", "+dstID+"]", rbe );
            }

            throw new BrokerException(
                br.getKString( BrokerResources.E_MSG_NOT_FOUND_IN_STORE,
                    mid, dstID ), Status.NOT_FOUND );
        }
    }

    /**
     * Get debug information about the store.
     * @param conn database connection
     * @return A HashMap of name value pair of information
     */
    public HashMap getDebugInfo( Connection conn ) {

        HashMap map = new HashMap();
        int count = -1;

        try {
            // Get row count
            count = getRowCount( null, null );
        } catch ( Exception e ) {
            logger.log( Logger.ERROR, e.getMessage(), e.getCause() );
        }

        map.put( "Messages(" + tableName + ")", String.valueOf( count ) );
        return map;
    }

    /**
     * Return the message count for the given broker.
     * @param conn database connection
     * @param brokerID the broker ID
     * @return the message count
     */
    public int getMessageCount( Connection conn, String brokerID ) throws BrokerException {

        int size = -1;

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            if ( conn == null ) {
                conn = DBManager.getDBManager().getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectCountByBrokerSQL );
            pstmt.setString( 1, brokerID );
            rs = pstmt.executeQuery();

            if ( rs.next() ) {
                size = rs.getInt( 1 );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectCountByBrokerSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectCountByBrokerSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString(
                    BrokerResources.X_GET_MSG_COUNTS_FOR_BROKER_FAILED,
                    brokerID ), ex );
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return size;
    }

    /**
     * Return the number of persisted messages and total number of bytes for
     * the given destination.
     * @param conn database connection
     * @param dst the destination
     * @return a HashMap
     */
    public HashMap getMessageStorageInfo( Connection conn, Destination dst )
        throws BrokerException {

        HashMap data = new HashMap( 2 );

        String dstID = dst.getUniqueName();

        boolean myConn = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            // Get a connection
            DBManager dbMgr = DBManager.getDBManager();
            if ( conn == null ) {
                conn = DBManager.getDBManager().getConnection( true );
                myConn = true;
            }

            pstmt = conn.prepareStatement( selectCountByDstSQL );
            pstmt.setString( 1, dbMgr.getBrokerID() );
            pstmt.setString( 2, dstID );
            pstmt.setString( 3, dstID );

            rs = pstmt.executeQuery();

            if ( rs.next() ) {
                data.put( DestMetricsCounters.CURRENT_MESSAGES,
                    new Integer( rs.getInt( 1 ) ) );
                data.put( DestMetricsCounters.CURRENT_MESSAGE_BYTES,
                    new Long( rs.getLong( 2 ) ) );
            } else {
                // Destination doesn't exists
                throw new BrokerException(
                    br.getKString( BrokerResources.E_DESTINATION_NOT_FOUND_IN_STORE,
                    dstID ), Status.NOT_FOUND );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectCountByDstSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectCountByDstSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_GET_COUNTS_FROM_DATABASE_FAILED,
                dstID ), ex );
        } finally {
            if ( myConn ) {
                Util.close( rs, pstmt, conn, myex );
            } else {
                Util.close( rs, pstmt, null, myex );
            }
        }

        return data;
    }

    /**
     * Load a single message or messages from a ResultSet.
     * @param rs the ResultSet
     * @param isSingleRow specify interesed in only the 1st row of the ResultSet
     * @return a message or a List of messages
     * @throws IOException
     * @throws SQLException
     */
    protected Object loadData( ResultSet rs, boolean isSingleRow )
        throws IOException, SQLException {

        ArrayList list = null;
        if ( !isSingleRow ) {
            list = new ArrayList( 100 );
        }

        while ( rs.next() ) {
            Packet msg = new Packet(false);
            msg.generateTimestamp(false);
            msg.generateSequenceNumber(false);

            InputStream is = null;
            if ( getMsgColumnType(rs, 1) == Types.BLOB ) {
                Blob blob = rs.getBlob( 1 );
                is = blob.getBinaryStream();
            } else {
                is = rs.getBinaryStream( 1 );
            }

            msg.readPacket(is);
            is.close();

            if (Store.getDEBUG()) {
                logger.log(Logger.DEBUG,
                    "Loaded message from database for "+ msg.getMessageID() );
            }

            if ( isSingleRow ) {
                return msg;
            } else {
                list.add( msg );
            }
        }

        return list;
    }

    /**
     * Get Message column type (e.g. is it a Blob?)
     * @param rs the ResultSet
     * @param msgColumnIndex the index of the Message column
     * @return column type
     */
    protected int getMsgColumnType( ResultSet rs, int msgColumnIndex )
        throws SQLException {

        // Cache the result
        if ( msgColumnType == -Integer.MAX_VALUE ) {
            msgColumnType = rs.getMetaData().getColumnType( msgColumnIndex );
        }

        return msgColumnType;
    }

    /**
     * Check if a msg can be inserted. A BrokerException is thrown if the msg
     * already exists in the store, the destination doesn't exist, or
     * the specified broker is being taken over by another broker (HA mode).
     * @param conn database connection
     * @param msgID message ID
     * @param dstID destination ID
     * @param brokerID broker ID
     * @throws BrokerException if msg cannot be inserted
     */
    protected void canInsertMsg( Connection conn, String msgID, String dstID,
        String brokerID ) throws BrokerException {

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Exception myex = null;
        try {
            pstmt = conn.prepareStatement( selectCanInsertSQL );
            pstmt.setString( 1, msgID );
            pstmt.setString( 2, dstID );
            if ( Globals.getHAEnabled() ) {
                pstmt.setString( 3, brokerID );
            }

            rs = pstmt.executeQuery();

            if ( rs.next() ) {
                // Make sure msg doesn't exist, i.e. created timestamp == 0
                if ( rs.getLong( 1 ) > 0 ) {
                    throw new BrokerException( br.getKString(
                        BrokerResources.E_MSG_EXISTS_IN_STORE, msgID, dstID ),
                        Status.CONFLICT );
                }

                // Make sure dst does exist, i.e. created timestamp > 0
                if ( rs.getLong( 2 ) == 0 ) {
                    throw new BrokerException( br.getKString(
                        BrokerResources.E_DESTINATION_NOT_FOUND_IN_STORE,
                        dstID ), Status.NOT_FOUND );
                }

                // Make sure broker is not being taken over, i.e. state == 0
                if ( Globals.getHAEnabled() ) {
                    if ( rs.getInt( 3 ) > 0 ) {
                        BrokerException be = new StoreBeingTakenOverException(
                        br.getKString(BrokerResources.E_STORE_BEING_TAKEN_OVER) );
                        try {
                            DBManager dbMgr = DBManager.getDBManager();
                            BrokerDAO dao = dbMgr.getDAOFactory().getBrokerDAO();
                            HABrokerInfo bkrInfo = dao.getBrokerInfo( conn, dbMgr.getBrokerID() );
                            logger.log( Logger.ERROR, br.getKString(BrokerResources.X_INTERNAL_EXCEPTION,
                                                      bkrInfo.toString() ), be );
                        } catch (Throwable t) { /* Ignore error */ }

                        throw be;
                    }

                }
            } else {
                // Shouldn't happen
                throw new BrokerException(
                    br.getKString( BrokerResources.X_JDBC_QUERY_FAILED, selectCanInsertSQL ) );
            }
        } catch ( Exception e ) {
            myex = e;
            try {
                if ( (conn != null) && !conn.getAutoCommit() ) {
                    conn.rollback();
                }
            } catch ( SQLException rbe ) {
                logger.log( Logger.ERROR, BrokerResources.X_DB_ROLLBACK_FAILED+"["+selectCanInsertSQL+"]", rbe );
            }

            Exception ex;
            if ( e instanceof BrokerException ) {
                throw (BrokerException)e;
            } else if ( e instanceof SQLException ) {
                ex = DBManager.wrapSQLException("[" + selectCanInsertSQL + "]", (SQLException)e);
            } else {
                ex = e;
            }

            throw new BrokerException(
                br.getKString( BrokerResources.X_JDBC_QUERY_FAILED, selectCanInsertSQL ), ex );
        } finally {
            Util.close( rs, pstmt, null, myex );
        }
    }

    /**
     * Message Enumeration class.
     */
    private static class MsgEnumeration implements Enumeration {

        DestinationUID dID = null;
        MessageDAO msgDAO = null;
        Iterator msgIDItr = null;
        Object msgToReturn = null;

        MsgEnumeration( DestinationUID dstUID, MessageDAO dao, Iterator itr ) {
            dID = dstUID;
            msgDAO = dao;
            msgIDItr = itr;
        }

        public boolean hasMoreElements() {
            Packet msg = null;
            while ( msgIDItr.hasNext() ) {
                String mid = null;
                try {
                    mid = (String)msgIDItr.next();
                    msg = msgDAO.getMessage( null, dID, mid );
                    msgToReturn = msg;
                    return true;
                } catch ( Exception e ) {
                    Globals.getLogger().logStack( Logger.ERROR,
                        BrokerResources.X_LOAD_MESSAGE_FAILED, mid, e );
                }
            }

            // no more
            msgToReturn = null;
            return false;
        }

        public Object nextElement() {
            if ( msgToReturn != null ) {
                return msgToReturn;
            } else {
                throw new NoSuchElementException();
            }
        }
    }
 }
