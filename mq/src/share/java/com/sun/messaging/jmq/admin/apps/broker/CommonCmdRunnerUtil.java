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
 */ 

package com.sun.messaging.jmq.admin.apps.broker;

import java.io.*;
import java.util.Properties;
import java.util.Hashtable;
import java.util.Enumeration;
import java.util.Date;
import java.text.DateFormat;

import com.sun.messaging.jmq.Version;
import com.sun.messaging.jmq.admin.util.Globals;
import com.sun.messaging.jmq.admin.util.CommonGlobals;
import com.sun.messaging.jmq.util.DebugPrinter;
import com.sun.messaging.jmq.util.MultiColumnPrinter;
import com.sun.messaging.jmq.admin.bkrutil.BrokerAdminException;
import com.sun.messaging.jmq.admin.bkrutil.BrokerAdminConn;
import com.sun.messaging.jmq.admin.resources.AdminResources;
import com.sun.messaging.jmq.admin.apps.console.event.CommonCmdStatusEvent;
import com.sun.messaging.jmq.util.Password;
import com.sun.messaging.jmq.util.FileUtil;
import com.sun.messaging.jmq.util.options.OptionException;
import com.sun.messaging.jmq.util.options.UnrecognizedOptionException;
import com.sun.messaging.jmq.util.options.InvalidBasePropNameException;
import com.sun.messaging.jmq.util.options.InvalidHardCodedValueException;
import com.sun.messaging.jmq.util.options.MissingArgException;
import com.sun.messaging.jmq.util.options.BadNameValueArgException;


/** 
 * This class contains common static methods that are used by
 * imqcmd and imqbridgemgr
 *
 * Please do not not use individual admin tool specific references
 *
 */
public class CommonCmdRunnerUtil {

    private static AdminResources ar = Globals.getAdminResources();

    public static void printBrokerBusyEvent(CommonCmdStatusEvent be)  {

		int numRetriesAttempted = be.getNumRetriesAttempted();
        int maxNumRetries = be.getMaxNumRetries();
		long retryTimeount = be.getRetryTimeount();
		Object args[] = new Object [ 3 ];

		args[0] = Integer.toString(numRetriesAttempted);
		args[1] = Integer.toString(maxNumRetries);
		args[2] = Long.toString(retryTimeount);

		/*
		 * This string is of the form:
		 *  Broker not responding, retrying [1 of 5 attempts, timeout=20 seconds]
		 */
		String s = ar.getString(ar.I_JMQCMD_BROKER_BUSY, args);
        CommonGlobals.stdOutPrintln(s);
    }

    public static  String getTimeString(long millis)  {
	String ret = null;

	if (millis < 1000)  {
	    ret = millis + " milliseconds";
	} else if (millis < (60 * 1000))  {
	    long seconds = millis / 1000;
	    ret = seconds + " seconds";
	} else if (millis < (60 * 60 * 1000))  {
	    long mins = millis / (60 * 1000);
	    ret = mins + " minutes";
	} else  {
	    ret = "> 1 hour";
	}

	return (ret);
    }

    public static String getRateString(long latest, long previous, float secs)  {
        long	diff, rate;
	String	rateString = "";

        diff = latest - previous;

        rate = (long)(diff/secs);

        if (rate == 0)  {
            if (diff != 0)  {
                rateString = "< 1";
            } else  {
                rateString = "0";
            }
        } else  {
            rateString = Long.toString(rate);
        }

	return (rateString);
    }

    public static String displayInKBytes(long l)  {
	if (l == 0)  {
	    return ("0");
	} else if (l < 1024)  {
	    return ("< 1");
	} else  {
	    return(Long.toString(l/1024));
	}
    }


    public static void printDebugHash(Hashtable hash)  {
	DebugPrinter dbp;

	dbp = new DebugPrinter(hash, 4);
	dbp.println();		
	dbp.close();
    }


    /*
     * Returns the broker host name.
     * Returns null if not specified.
     *
     * @param brokerHostPort String in the form of host:port
     *
     * @return host value or null if not specified
     */
    public static String getBrokerHost(String brokerHostPort) {
        String host = brokerHostPort;

	if (brokerHostPort == null) return (null);

        int i = brokerHostPort.indexOf(':');
        if (i >= 0)
            host = brokerHostPort.substring(0, i);

        if (host == null || host.equals("")) {
	    return null;
        }
	return host;
    }

    /*
     * Returns the broker port number.
     * Return -1 if not specified.
     *
     * @param brokerHostPort String in the form of host:port
     *
     * @return port value or -1 if not specified
     *
     * @throw BrokerAdminException if port value is not valid
     */
    public static int getBrokerPort(String brokerHostPort) throws BrokerAdminException {
	int port = -1;

	if (brokerHostPort == null) return (port);

        int i = brokerHostPort.indexOf(':');

	if (i >= 0) {
            try {
                port = Integer.parseInt(brokerHostPort.substring(i + 1));

            } catch (Exception e) {
		throw new BrokerAdminException(BrokerAdminException.INVALID_PORT_VALUE);
	    }
	}
	return port;
    }


    /*
     * Prints out the appropriate error message using 
     * CommonGlobals.stdErrPrintln()
     */
    public static void printBrokerAdminException(BrokerAdminException bae, 
                                                 String brokerHostPortOption,
                                                 boolean debugMode)  {
	Exception	e = bae.getLinkedException();
	int		type = bae.getType();

	switch (type)  {
	case BrokerAdminException.CONNECT_ERROR:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_CONNECT_ERROR,
		bae.getBrokerHost(), bae.getBrokerPort()));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_VERIFY_BROKER, brokerHostPortOption));
	break;

	case BrokerAdminException.MSG_SEND_ERROR:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_MSG_SEND_ERROR));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.MSG_REPLY_ERROR:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_MSG_REPLY_ERROR));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.CLOSE_ERROR:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_CLOSE_ERROR));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.PROB_GETTING_MSG_TYPE:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_PROB_GETTING_MSG_TYPE));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.PROB_GETTING_STATUS:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_PROB_GETTING_STATUS));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.REPLY_NOT_RECEIVED:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_REPLY_NOT_RECEIVED));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.INVALID_OPERATION:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_INVALID_OPERATION));
	    printBrokerAdminExceptionDetails(bae, debugMode);
	break;

	case BrokerAdminException.INVALID_PORT_VALUE:
            CommonGlobals.stdErrPrintln(
                ar.getString(ar.I_ERROR_MESG),
                ar.getKString(ar.E_JMQCMD_INVALID_PORT_VALUE));
	break;

        case BrokerAdminException.INVALID_LOGIN:
            CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_CONNECT_ERROR,
		bae.getBrokerHost(), bae.getBrokerPort()));
            printBrokerAdminExceptionDetails(bae, debugMode);
            CommonGlobals.stdErrPrintln(ar.getString(ar.E_INVALID_LOGIN));
        break;

        case BrokerAdminException.SECURITY_PROB:
            CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_CONNECT_ERROR,
		bae.getBrokerHost(), bae.getBrokerPort()));
            printBrokerAdminExceptionDetails(bae, debugMode);
            CommonGlobals.stdErrPrintln(ar.getString(ar.E_LOGIN_FORBIDDEN));
        break;

        case BrokerAdminException.PROB_SETTING_SSL:
            CommonGlobals.stdErrPrintln(ar.getString(ar.E_JMQCMD_CONNECT_ERROR,
		bae.getBrokerHost(), bae.getBrokerPort()));
            printBrokerAdminExceptionDetails(bae, debugMode);
            CommonGlobals.stdErrPrintln(ar.getString(ar.E_PROB_SETTING_SSL));
        break;

	case BrokerAdminException.BAD_ADDR_SPECIFIED:
            CommonGlobals.stdErrPrintln(
                ar.getString(ar.I_ERROR_MESG),
                ar.getKString(ar.E_JMQCMD_BAD_ADDRESS, bae.getBrokerAddress()));
	break;

	}
    }

    private static void printBrokerAdminExceptionDetails(BrokerAdminException bae, boolean debugMode)  {
	Exception	e = bae.getLinkedException();
	String		s = bae.getBrokerErrorStr();

	if (s != null)  {
	    CommonGlobals.stdErrPrintln(s);
	}

	if (e != null)  {
	    String msg = e.getMessage(), s2 = e.toString();

	    if (s2 != null)  {
	        CommonGlobals.stdErrPrintln(s2);
	    } else if (msg != null)  {
	        CommonGlobals.stdErrPrintln(msg);
	    }

	    if (debugMode)  {
	        e.printStackTrace(System.err);
	    }

	}
    }


    public static void printCommonCmdException(CommonCmdException bce)  {
	Exception	ex = bce.getLinkedException();
	int		type = bce.getType();

	switch (type)  {
	case CommonCmdException.READ_PASSFILE_FAIL:
            CommonGlobals.stdErrPrintln(
		ar.getString(ar.I_ERROR_MESG),
		ar.getKString(ar.E_READ_PASSFILE_FAIL, ex));
	break;

	default:
	    CommonGlobals.stdErrPrintln("Unknown exception caught: " + type);
	}
    }


    /**
     * Return user input. Return null if an error occurred.
     */
    public static String getUserInput(String question)  {
	return (getUserInput(question, null));
    }

    /**
     * Return user input. Return <defaultResponse> if no response ("") was
     * given. Return null if an error occurred.
     */
    public static String getUserInput(String question, String defaultResponse)  {

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
	    CommonGlobals.stdOutPrint(question);
            String s = in.readLine();

	    if (s.equals("") && (defaultResponse != null))  {
		s = defaultResponse;
	    }
	    return(s);

        } catch (IOException ex) {
            CommonGlobals.stdErrPrintln(
		ar.getString(ar.I_ERROR_MESG),
		ar.getKString(ar.E_PROB_GETTING_USR_INPUT));
            return null;
        }
    }    

    /**
     * Return the password without echoing.
     */
    public static String getPassword() {

        Password pw = new Password();
        if (pw.echoPassword()) {
            CommonGlobals.stdOutPrintln(ar.getString(ar.W_ECHO_PASSWORD));
        }
        CommonGlobals.stdOutPrint(ar.getString(ar.I_JMQCMD_PASSWORD));
        return pw.getPassword();
    }

    public static void printBrokerInfo(BrokerAdminConn broker, MultiColumnPrinter mcp) {
    mcp.setNumCol(2);
    mcp.setGap(4);
    mcp.setBorder("-");
	String[] row = new String[2];

	row[0] = ar.getString(ar.I_JMQCMD_BKR_HOST);
	row[1] = ar.getString(ar.I_JMQCMD_PRIMARY_PORT);
	mcp.addTitle(row);

	row[0] = broker.getBrokerHost();
	row[1] = broker.getBrokerPort();
	mcp.add(row);

	mcp.println();
    }

    public static void printAttrs(Properties targetAttrs, boolean printTitle, MultiColumnPrinter mcp) {
    String[] row = new String[2];

    if (printTitle)  {
        mcp.setNumCol(2);
        mcp.setGap(4);
        mcp.setBorder("-");
        row[0] = "Property Name";
        row[1] = "Property Value";
        mcp.addTitle(row);
    } else  {
        mcp.setNumCol(2);
        mcp.setGap(4);
    }

    for (Enumeration e = targetAttrs.propertyNames();  e.hasMoreElements() ;) {
        String propName = (String)e.nextElement(),
           value = targetAttrs.getProperty(propName);
        row[0] = propName;
        row[1] = value;
        mcp.add(row);
    }
    mcp.println();
    }


    public static String checkNullAndReturnPrint(Object obj)  {
	 if (obj != null)  {
	    return (obj.toString());
	 } else  {
	    return ("");
	 }
    }

    public static String checkNullAndReturnPrintTimestamp(Long timestamp)  {
	 if (timestamp != null)  {
	    String	ts;
	    Date	d = new Date(timestamp.longValue());
	    DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, 
						DateFormat.MEDIUM);

	    ts = df.format(d);

	    return (ts);
	 } else  {
	    return ("");
	 }
    }

    public static BrokerAdminConn promptForAuthentication(BrokerAdminConn broker) {
        String usernameValue = broker.getUserName();
        String passwordValue = broker.getPassword();

        boolean carriageReturnNeeded = false;

        if (usernameValue == null) {
            broker.setUserName(getUserInput(ar.getString(ar.I_JMQCMD_USERNAME)));
            carriageReturnNeeded = true;
        }

        if (passwordValue == null) {
	    String passwd = getPassword();
            broker.setPassword(passwd);
            carriageReturnNeeded = false;
        }

        if (carriageReturnNeeded)
            CommonGlobals.stdOutPrintln("");

        return broker;
    }

    /*
     * Get password from either the passfile or -p option.
     * In some future release, the -p option will go away
     * leaving the passfile the only way to specify the 
     * password (besides prompting the user for it).
     * -p has higher precendence compared to -passfile.
     */
    public static String getPasswordFromFile(String passfile,
                                             String passwdPropNameInPassFile, 
                                             Properties cmdProps) 
		throws CommonCmdException  {

	if (passfile != null)  {
	    String ret = null;
	    try  {
	        Properties props = new Properties();
		/*
	     * Read password from passfile
		 */
                InputStream fis = FileUtil.retrieveObfuscatedFile(
                        passfile);

                props.load(fis);
		ret = props.getProperty(passwdPropNameInPassFile);

		fis.close();
		if (ret == null)  {
		    throw new RuntimeException(
		      ar.getString(ar.E_PASSFILE_PASSWD_PROPERTY_NOT_FOUND,
				passwdPropNameInPassFile,
				passfile));
		}
	    } catch(Exception e)  {
		CommonCmdException bce = 
			new CommonCmdException(CommonCmdException.READ_PASSFILE_FAIL);
		bce.setProperties(cmdProps);
		bce.setLinkedException(e);

		throw (bce);
	    }
	    return (ret);
	}
	
	return (null);
    }

    public static void handleArgsParsingExceptions(OptionException e, 
                                                    String optionAdminPasswd,
                                                    String toolName) {
    String  option = e.getOption();

    if (e instanceof UnrecognizedOptionException)  {
        // Output error indicating -p option is no longer supported.
        // otherwise just output the standard unrecognized option error.
        if (optionAdminPasswd != null && option.equals(optionAdminPasswd)) {
                Globals.stdErrPrintln(
            ar.getString(ar.I_ERROR_MESG),
            ar.getKString(ar.E_PASSWD_OPTION_NOT_SUPPORTED, option));
        } else {
                Globals.stdErrPrintln(
            ar.getString(ar.I_ERROR_MESG),
            ar.getKString(ar.E_UNRECOG_OPTION, option, toolName));
        }

    } else if (e instanceof InvalidBasePropNameException)  {
            Globals.stdErrPrintln(
        ar.getString(ar.I_INTERNAL_ERROR_MESG),
        ar.getKString(ar.E_INVALID_BASE_PROPNAME, option));

    } else if (e instanceof InvalidHardCodedValueException)  {
            Globals.stdErrPrintln(
        ar.getString(ar.I_INTERNAL_ERROR_MESG),
        ar.getKString(ar.E_INVALID_HARDCODED_VAL, option));

    } else if (e instanceof MissingArgException)  {
        /*
         * REVISIT:
         * We can provide more specific messages here depending on what
         * the option was e.g. for -t:
         *  Error: An object type was expected for option -t
         */
            Globals.stdErrPrintln(
        ar.getString(ar.I_ERROR_MESG),
        ar.getKString(ar.E_MISSING_ARG, option, toolName));

    } else if (e instanceof BadNameValueArgException)  {
        BadNameValueArgException bnvae = (BadNameValueArgException)e;
        String  badArg = bnvae.getArg();

            Globals.stdErrPrintln(
        ar.getString(ar.I_ERROR_MESG),
        ar.getKString(ar.E_BAD_NV_ARG, badArg, option));

    } else  {
            Globals.stdErrPrintln(
        ar.getString(ar.I_ERROR_MESG),
        ar.getKString(ar.E_OPTION_PARSE_ERROR));
    }
    }

    /**
     * Print banner.
     * XXX REVISIT 07/26/00 nakata: Add build number to M_BANNER
     */
    public static void printBanner() {
        Version version = new Version(false);
        CommonGlobals.stdOutPrintln(version.getBanner(false));
    }

    public static void printVersion() {

    Version version = new Version(false);
    Globals.stdOutPrintln(version.getVersion());
    Globals.stdOutPrintln(ar.getString(ar.I_JAVA_VERSION) +
        System.getProperty("java.version") + " " +
        System.getProperty("java.vendor") + " " +
        System.getProperty("java.home"));
    }

    /**
     * This should be called last in handleCheckOptionsException
     */
    public static void handleCommonCheckOptionsExceptions(CommonCmdException e,
                                                           String cmd, String cmdArg,
                                                           CommonHelpPrinter hp) {
    String errorString = e.getErrorString();
    String badValue = e.getBadValue();
    String validCmdArgs[] = e.getValidCmdArgs();
    int type = e.getType();
    String  errorValue;

	switch (type)  {
	case CommonCmdException.NO_CMD_SPEC:
	    printBanner();
	    hp.printShortHelp(1);
	break;

	case BrokerCmdException.BAD_CMD_SPEC:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.I_ERROR_MESG), 
                                    ar.getKString(ar.E_BAD_COMMAND_SPEC, cmd));
	break;

	case BrokerCmdException.BAD_CMDARG_SPEC:
	    /*
	     * The following prints this error message, e.g:
	     *	% jcmd pause foo
	     *	Error [A3101]: Bad argument specified for the pause command: foo
	     *	The valid command arguments for the pause command are:
	     *		svc
	     *		bkr
	     */

	    /*
	     * Prints:
	     *	Error [A3101]: Bad argument specified for the pause command: foo
	     */
	    CommonGlobals.stdErrPrintln(ar.getString(ar.I_ERROR_MESG), 
                                    ar.getKString(ar.E_BAD_CMDARG_SPEC1, cmd, cmdArg));

	    if (validCmdArgs != null)  {
		/*
		 * Prints:
		 *  The valid command arguments for the pause command are:
		 */
	        CommonGlobals.stdErrPrintln(ar.getString(ar.E_BAD_CMDARG_SPEC2, cmd));
	    
		/*
		 * Prints:
		 *	svc
		 *	bkr
		 */
	        for (int i = 0; i < validCmdArgs.length; ++i)  {
	            CommonGlobals.stdErrPrintln("\t" + validCmdArgs[i]);
	        }
	    }
	break;

	case CommonCmdException.INVALID_RECV_TIMEOUT_VALUE:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.I_ERROR_MESG), 
            ar.getKString(ar.E_INVALID_RECV_TIMEOUT_VALUE, errorString));
	break;

	case CommonCmdException.INVALID_NUM_RETRIES_VALUE:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.I_ERROR_MESG), 
            ar.getKString(ar.E_INVALID_NUM_RETRIES_VALUE, errorString));
	break;

	case BrokerCmdException.INVALID_TIME:
	    CommonGlobals.stdErrPrintln(ar.getString(ar.I_ERROR_MESG), 
            ar.getKString(ar.E_INVALID_TIME_VALUE, badValue));
	break;
    default:
        CommonGlobals.stdErrPrintln(ar.getString(ar.I_ERROR_MESG),
            ar.getKString(ar.E_OPTION_VALID_ERROR));

	}
    }

}
