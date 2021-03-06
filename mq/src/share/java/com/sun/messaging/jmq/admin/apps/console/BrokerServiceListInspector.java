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
 * @(#)BrokerServiceListInspector.java	1.9 06/27/07
 */ 

package com.sun.messaging.jmq.admin.apps.console;

import com.sun.messaging.jmq.admin.bkrutil.BrokerAdminUtil;
import com.sun.messaging.jmq.admin.resources.AdminConsoleResources;
import com.sun.messaging.jmq.admin.resources.AdminResources;
import com.sun.messaging.jmq.admin.util.Globals;
import com.sun.messaging.jmq.util.ServiceState;
import com.sun.messaging.jmq.util.admin.ServiceInfo;

/** 
 * Inspector panel for the broker service list.
 *
 * @see InspectorPanel
 * @see AInspector
 * @see ConsoleObj
 */
public class BrokerServiceListInspector extends TabledInspector  {

    private static AdminResources ar = Globals.getAdminResources();
    private static AdminConsoleResources acr = Globals.getAdminConsoleResources();

    /**
     * Return the array of Strings containing the collumn labels/headers.
     * @return the array of Strings containing the collumn labels/headers.
     */
    public String[] getColumnHeaders()  {
        String[] columnNames = {acr.getString(acr.I_SVC_NAME),
                                acr.getString(acr.I_PORT_NUMBER),
                                acr.getString(acr.I_SVC_STATE)};
        return (columnNames);
    }

    /**
     * Returns the Object at a particular cell collumn for a given
     * ConsoleObj object. Each row in the JTable represents one ConsoleObj.
     * This method returns the object/value for the ConsoleObj, for a particular 
     * collumn.
     *
     * @return the Object at a particular cell collumn for a given
     * ConsoleObj object.
     */
    public Object getValueAtCollumn(ConsoleObj conObj, int col)  {

        BrokerServiceCObj      bSvcCObj;

        if (!(conObj instanceof BrokerServiceCObj))  {
            return null;
        }

        bSvcCObj = (BrokerServiceCObj)conObj;
	ServiceInfo svcInfo = bSvcCObj.getServiceInfo();

        if (col == 0) {
            return (bSvcCObj);
        } else if (col == 1) {
	    String portStr;

	    // The port number is not applicable to this service
	    if (svcInfo.port == -1) {
		portStr = "-";

	    } else if (svcInfo.dynamicPort) {
                // Add more information about the port number:
                // dynamically generated or statically declared
                switch (svcInfo.state) {
                    case ServiceState.UNKNOWN:
                        portStr = ar.getString(ar.I_DYNAMIC);
                        break;
                    default:
                        portStr = new Integer(svcInfo.port).toString()
					+ " (" 
					+ ar.getString(ar.I_DYNAMIC) 
					+ ")";
                 }
            } else {
                portStr = new Integer(svcInfo.port).toString()
				+ " (" 
				+ ar.getString(ar.I_STATIC) 
				+ ")";
	    }

            return (portStr);
        } else if (col == 2) {
          //  return (ServiceState.getString(svcInfo.state));
        	return BrokerAdminUtil.getServiceState(svcInfo.state);
        }

        return (null);
    }
}
