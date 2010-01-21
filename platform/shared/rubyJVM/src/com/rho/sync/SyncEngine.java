/*
 *  rhodes
 *
 *  Copyright (C) 2008 Rhomobile, Inc. All rights reserved.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.rho.sync;

import com.rho.RhoClassFactory;
import com.rho.RhoConf;
import com.rho.RhoEmptyLogger;
import com.rho.RhoEmptyProfiler;
import com.rho.RhoLogger;
import com.rho.RhoProfiler;
import com.rho.RhoRuby;
import com.rho.db.*;
import com.rho.net.*;
import com.rho.*;
import java.io.IOException;
import java.util.Vector;
import java.util.Hashtable;

public class SyncEngine implements NetRequest.IRhoSession
{
	private static final RhoLogger LOG = RhoLogger.RHO_STRIP_LOG ? new RhoEmptyLogger() : 
		new RhoLogger("Sync");
	private static final RhoProfiler PROF = RhoProfiler.RHO_STRIP_PROFILER ? new RhoEmptyProfiler() : 
		new RhoProfiler();
	
    public static final int esNone = 0, esSyncAllSources = 1, esSyncSource = 2, esSearch=3, esStop = 4, esExit = 5;

    static class SourceID
    {
        String m_strName = "";
        int m_nID;

        SourceID(int id, String strName ){ m_nID = id; m_strName = strName; }
        SourceID(String strName ){ m_strName = strName; }
        
        public String toString()
        {
            if ( m_strName.length() > 0 )
                return "name : " + m_strName;

            return "# : " + m_nID;
        }
        boolean isEqual(SyncSource src)
        {
            if ( m_strName.length() > 0 )
                return src.getName().equals(m_strName);

            return m_nID == src.getID().intValue();
        }
    };
    
    Vector/*<SyncSource*>*/ m_sources = new Vector();
    DBAdapter   m_dbUserAdapter;
    DBAdapter   m_dbAppAdapter;
    NetRequest m_NetRequest;
    ISyncProtocol m_SyncProtocol;
    int         m_syncState;
    String     m_clientID = "";
    Mutex m_mxLoadClientID = new Mutex();
    String m_strSession = "";
    SyncNotify m_oSyncNotify = new SyncNotify(this);
    boolean m_bStopByUser = false;
    int m_nSyncPageSize = 2000;
    boolean m_bHasUserPartition;
    boolean m_bHasAppPartition;
    
    void setState(int eState){ m_syncState = eState; }
    int getState(){ return m_syncState; }
    boolean isContinueSync(){ return m_syncState != esExit && m_syncState != esStop; }
	boolean isSyncing(){ return m_syncState == esSyncAllSources || m_syncState == esSyncSource; }
    void stopSync(){ if (isContinueSync()){ setState(esStop); m_NetRequest.cancel(); } }
    void stopSyncByUser(){ m_bStopByUser = true; stopSync(); }
    void exitSync(){ setState(esExit); m_NetRequest.cancel(); }
    boolean isStoppedByUser(){ return m_bStopByUser; }
    
    String getClientID(){ return m_clientID; }
    void setSession(String strSession){m_strSession=strSession;}
    boolean isSessionExist(){ return m_strSession != null && m_strSession.length() > 0; }
    
  //IRhoSession
    public String getSession(){ return m_strSession; }
    public String getContentType(){ return getProtocol().getContentType();}
    
    DBAdapter getDB(){ return m_dbUserAdapter; }
    DBAdapter getAppDB(){ return m_dbAppAdapter; }    
    SyncNotify getNotify(){ return m_oSyncNotify; }
    NetRequest getNet() { return m_NetRequest;}
    ISyncProtocol getProtocol(){ return m_SyncProtocol; }
    
    SyncEngine(DBAdapter dbUser, DBAdapter dbApp){
    	m_dbUserAdapter = dbUser;
    	m_dbAppAdapter = dbApp;

		m_NetRequest = null;
    	m_syncState = esNone;
    	
    	initProtocol();
    }

    void initProtocol()
    {
        m_SyncProtocol = new SyncProtocol_3();
    }
    
    int getSyncPageSize() { return m_nSyncPageSize; }
    void setSyncPageSize(int nPageSize){ m_nSyncPageSize = nPageSize; }
    
    void setFactory(RhoClassFactory factory)throws Exception{ 
		m_NetRequest = RhoClassFactory.createNetRequest();
    }
    
    void prepareSync(int eState)throws Exception
    {
        setState(eState);
        m_bStopByUser = false;
        loadAllSources();

        m_strSession = loadSession();
        if ( isSessionExist()  )
        {
            m_clientID = loadClientID();
            getNotify().cleanLastSyncObjectCount();
        }
        else
        {
	    	if ( m_sources.size() > 0 )
	        {		    	
		    	SyncSource src = (SyncSource)m_sources.elementAt(getStartSource());
		    	//src.m_strError = "Client is not logged in. No sync will be performed.";
		    	src.m_nErrCode = RhoRuby.ERR_CLIENTISNOTLOGGEDIN;
		    	
		    	getNotify().fireSyncNotification(src, true, src.m_nErrCode, "");
	        }else
	        	getNotify().fireSyncNotification(null, true, RhoRuby.ERR_CLIENTISNOTLOGGEDIN, "");

            stopSync();
        }
    }
    
    void doSyncAllSources()
    {
	    try
	    {
	        prepareSync(esSyncAllSources);
	
	        if ( isContinueSync() )
	        {
			    PROF.CREATE_COUNTER("Net");	    
			    PROF.CREATE_COUNTER("Parse");
			    PROF.CREATE_COUNTER("DB");
			    PROF.CREATE_COUNTER("Data");
			    PROF.CREATE_COUNTER("Data1");
			    PROF.CREATE_COUNTER("Pull");
			    PROF.START("Sync");
	
	            syncAllSources();
	
			    PROF.DESTROY_COUNTER("Net");	    
			    PROF.DESTROY_COUNTER("Parse");
			    PROF.DESTROY_COUNTER("DB");
			    PROF.DESTROY_COUNTER("Data");
			    PROF.DESTROY_COUNTER("Data1");
			    PROF.DESTROY_COUNTER("Pull");
			    PROF.STOP("Sync");
	        }
	
	        getNotify().cleanCreateObjectErrors();
	    }catch(Exception exc)
	    {
	    	LOG.ERROR("Sync failed.", exc);
	    }

        if ( getState() != esExit )
            setState(esNone);
    }

    void doSearch(Vector/*<rho::String>*/ arSources, String strParams, String strAction, boolean bSearchSyncChanges, int nProgressStep)
    {
/*        prepareSync(esSearch);
        if ( !isContinueSync() )
        {
            if ( getState() != esExit )
                setState(esNone);

            return;
        }

        CTimeInterval startTime = CTimeInterval::getCurrentTime();

        if ( bSearchSyncChanges )
        {
            for ( int i = 0; i < (int)arSources.size(); i++ )
            {
                CSyncSource* pSrc = findSourceByName(arSources.elementAt(i));
                if ( pSrc != null )
                    pSrc->syncClientChanges();
            }
        }

        int nErrCode = 0;
        while( isContinueSync() )
        {
            int nSearchCount = 0;
            String strUrl = getProtocol().getServerQueryUrl(strAction);
            String strQuery = getProtocol().getServerQueryBody("", getClientID(), getSyncPageSize());

            if ( strParams.length() > 0 )
                strQuery += strParams;

            for ( int i = 0; i < (int)arSources.size(); i++ )
            {
                CSyncSource* pSrc = findSourceByName(arSources.elementAt(i));
                if ( pSrc != null )
                {
                    strQuery += "&sources[][name]=" + pSrc->getName();

                    if ( !pSrc->isTokenFromDB() && pSrc->getToken() > 1 )
                        strQuery += "&sources[][token]=" + convertToStringA(pSrc->getToken());
                }
            }

    		LOG(INFO) + "Call search on server. Url: " + (strUrl+strQuery);
            NetResponse(resp,getNet().pullData(strUrl+strQuery, this));

            if ( !resp.isOK() )
            {
                stopSync();
    			if (resp.isResponseRecieved())
    				nErrCode = RhoRuby.ERR_REMOTESERVER;
    			else
    				nErrCode = RhoRuby.ERR_NETWORK;
                continue;
            }

            const char* szData = resp.getCharData();

            CJSONArrayIterator oJsonArr(szData);

            for( ; !oJsonArr.isEnd() && isContinueSync(); oJsonArr.next() )
            {
                CJSONArrayIterator oSrcArr(oJsonArr.getCurItem());

                int nVersion = 0;
                if ( !oSrcArr.isEnd() && oSrcArr.getCurItem().hasName("version") )
                {
                    nVersion = oSrcArr.getCurItem().getInt("version");
                    oJsonArr.next();
                }

                if ( nVersion != getProtocol().getVersion() )
                {
                    LOG(ERROR) + "Sync server send search data with incompatible version. Client version: " + convertToStringA(getProtocol().getVersion()) +
                        "; Server response version: " + convertToStringA(nVersion);
                    stopSync();
                    nErrCode = RhoRuby.ERR_UNEXPECTEDSERVERRESPONSE;
                    continue;
                }

                if ( !oSrcArr.getCurItem().hasName("source") )
                {
                    LOG(ERROR) + "Sync server send search data without source name.";
                    stopSync();
                    nErrCode = RhoRuby.ERR_UNEXPECTEDSERVERRESPONSE;
                    continue;
                }

                String strSrcName = oSrcArr.getCurItem().getString("source");
                CSyncSource* pSrc = findSourceByName(strSrcName);
                if ( pSrc == null )
                {
                    LOG(ERROR) + "Sync server send search data for unknown source name:" + strSrcName;
                    stopSync();
                    nErrCode = RhoRuby.ERR_UNEXPECTEDSERVERRESPONSE;
                    continue;
                }

                oSrcArr.reset(0);
                pSrc->m_bIsSearch = true;
                pSrc->setProgressStep(nProgressStep);
                pSrc->processServerResponse_ver3(oSrcArr);

                nSearchCount += pSrc->getCurPageCount();
            }

            if ( nSearchCount == 0 )
                break;
        }  

        if ( isContinueSync() )
        	getNotify().fireSyncNotification(null, true, RhoRuby.ERR_NONE, RhoRuby.getMessageText("sync_completed"));
        else if ( nErrCode != 0 )
        {
            CSyncSource& src = *m_sources.elementAt(getStartSource());
            src.m_nErrCode = nErrCode;
            src.m_bIsSearch = true;
            getNotify().fireSyncNotification(&src, true, src.m_nErrCode, "");
        }

        //update db info
        CTimeInterval endTime = CTimeInterval::getCurrentTime();
        unsigned long timeUpdated = CLocalTime().toULong();
        for ( int i = 0; i < (int)arSources.size(); i++ )
        {
            CSyncSource* pSrc = findSourceByName(arSources.elementAt(i));
            if ( pSrc == null )
                continue;
            CSyncSource& oSrc = *pSrc;
            oSrc.getDB().executeSQL("UPDATE sources set last_updated=?,last_inserted_size=?,last_deleted_size=?, \
    						 last_sync_duration=?,last_sync_success=?, backend_refresh_time=? WHERE source_id=?", 
                             timeUpdated, oSrc.getInsertedCount(), oSrc.getDeletedCount(), 
                             (endTime-startTime).toULong(), oSrc.getGetAtLeastOnePage(), oSrc.getRefreshTime(),
                             oSrc.getID() );
        }
        //

        getNotify().cleanCreateObjectErrors();
        if ( getState() != esExit )
            setState(esNone);*/
    }

    void doSyncSource(SourceID oSrcID)
    {
        SyncSource src = null;

	    try
	    {
	        prepareSync(esSyncSource);
	
	        if ( isContinueSync() )
	        {
	        	src = findSource(oSrcID);
	            if ( src != null )
	            {
		            LOG.INFO("Started synchronization of the data source: " + src.getName() );
	
	                src.sync();
	
				    getNotify().fireSyncNotification(src, true, src.m_nErrCode, src.m_nErrCode == RhoRuby.ERR_NONE ? RhoRuby.getMessageText("sync_completed") : "");
	            }else
	            {
		        	src = new SyncSource(this, getDB());
			    	//src.m_strError = "Unknown sync source.";
			    	src.m_nErrCode = RhoRuby.ERR_RUNTIME;
		        	
	    	    	throw new RuntimeException("Sync one source : Unknown Source " + oSrcID.toString() );
	            }
	        }
	
	    } catch(Exception exc) {
    		LOG.ERROR("Sync source " + oSrcID.toString() + " failed.", exc);
	    	
	    	if ( src != null && src.m_nErrCode == RhoRuby.ERR_NONE )
	    		src.m_nErrCode = RhoRuby.ERR_RUNTIME;
	    	
	    	getNotify().fireSyncNotification(src, true, src.m_nErrCode, "" ); 
	    }

        getNotify().cleanCreateObjectErrors();
        if ( getState() != esExit )
            setState(esNone);
    }

	SyncSource findSource(SourceID oSrcID)
	{
	    for( int i = 0; i < (int)m_sources.size(); i++ )
	    {
	        SyncSource src = (SyncSource)m_sources.elementAt(i);
	        if ( oSrcID.isEqual(src) )
	            return src;
	    }
	    
	    return null;
	}
	
	SyncSource findSourceByName(String strSrcName)
	{
		return findSource(new SourceID(strSrcName));		
	}
	
	void loadAllSources()throws DBException
	{
	    m_sources.removeAllElements();
	    m_bHasUserPartition = false;
	    m_bHasAppPartition = false;
	    
	    IDBResult res = getDB().executeSQL("SELECT source_id,sync_type,token,name, partition from sources ORDER BY priority");
	    for ( ; !res.isEnd(); res.next() )
	    { 
	        String strShouldSync = res.getStringByIdx(1);
	        if ( strShouldSync.compareTo("none") == 0 || strShouldSync.compareTo("bulkonly") == 0 )
	            continue;

	        String strName = res.getStringByIdx(3);
	        String strPartition = res.getStringByIdx(4);
	        m_bHasUserPartition = m_bHasUserPartition || strPartition.compareTo("user") == 0;
	        m_bHasAppPartition = m_bHasAppPartition || strPartition.compareTo("app") == 0;

	        m_sources.addElement( new SyncSource( res.getIntByIdx(0), strName, res.getLongByIdx(2), 
	            (strPartition.compareTo("user") == 0 ? getDB() : getAppDB()), this) );
	    }
	}

	public String loadClientID()throws Exception
	{
	    String clientID = "";
		
		synchronized( m_mxLoadClientID )
		{
		    boolean bResetClient = false;
		    int nBulkSyncState = 0;
		    {
		        IDBResult res = getDB().executeSQL("SELECT client_id,reset,bulksync_state from client_info");
		        if ( !res.isEnd() )
		        {
		            clientID = res.getStringByIdx(0);
		            bResetClient = res.getIntByIdx(1) > 0;
		            nBulkSyncState = res.getIntByIdx(2);
		        }
		    }
		    
		    if ( clientID.length() == 0 )
		    {
		        clientID = requestClientIDByNet();
		
	            IDBResult res = getDB().executeSQL("SELECT * FROM client_info");
	            if ( !res.isEnd() )
	                getDB().executeSQL("UPDATE client_info SET client_id=?", clientID);
	            else
	                getDB().executeSQL("INSERT INTO client_info (client_id) values (?)", clientID);
		    }else if ( bResetClient )
		    {
		    	if ( !resetClientIDByNet(clientID) )
		    		stopSync();
		    	else
		    		getDB().executeSQL("UPDATE client_info SET reset=? where client_id=?", new Integer(0), clientID );	    	
		    }

		    //TODO:doBulkSync
	       	doBulkSync(clientID, nBulkSyncState);		    
		}
		
		return clientID;
	}

	boolean resetClientIDByNet(String strClientID)throws Exception
	{
	    NetResponse resp = getNet().pullData(getProtocol().getClientResetUrl(strClientID), this);
	    return resp.isOK();
	}
	
	String requestClientIDByNet()throws Exception
	{
	    NetResponse resp = getNet().pullData(getProtocol().getClientCreateUrl(), this);
	    if ( resp.isOK() && resp.getCharData() != null )
	    {
	    	String szData = resp.getCharData();
	        JSONEntry oJsonEntry = new JSONEntry(szData);
	
	        JSONEntry oJsonObject = oJsonEntry.getEntry("client");
	        if ( !oJsonObject.isEmpty() )
	            return oJsonObject.getString("client_id");
	    }
	
	    return "";
	}

	void doBulkSync(String strClientID, int nBulkSyncState)throws Exception
	{
	    if ( nBulkSyncState >= 2 || !isContinueSync() )
	        return;

		LOG.INFO("Bulk sync: start");
		getNotify().fireBulkSyncNotification(false, RhoRuby.ERR_NONE);

	    if ( nBulkSyncState == 0 && m_bHasUserPartition )
	    {
	        loadBulkPartition(getDB(), "user", strClientID);

	        if ( !isContinueSync() )
	            return;

		    getDB().executeSQL("UPDATE client_info SET bulksync_state=1 where client_id=?", strClientID );	    	
	    }

	    if ( m_bHasAppPartition )
	        loadBulkPartition(getAppDB(), "app", strClientID);

	    if ( !isContinueSync() )
	        return;

	    getDB().executeSQL("UPDATE client_info SET bulksync_state=2 where client_id=?", strClientID );

	    getNotify().fireBulkSyncNotification(true, RhoRuby.ERR_NONE);
	            
	}

	void loadBulkPartition(DBAdapter dbPartition, String strPartition, String strClientID )throws Exception
	{
	    String serverUrl = RhoConf.getInstance().getPath("syncserver");
	    String strUrl = serverUrl + "bulk_data";
	    String strQuery = "?client_id=" + strClientID + "&partition=" + strPartition;
	    String strDataUrl = "", strCmd = "";

	    while(strCmd.length() == 0)
	    {	    
	        NetResponse resp = getNet().pullData(strUrl+strQuery, this);
	        if ( !resp.isOK() || resp.getCharData() == null )
	        {
	    	    LOG.ERROR( "Bulk sync failed: server return an error." );
	    	    stopSync();
	    	    getNotify().fireBulkSyncNotification(true, RhoRuby.ERR_REMOTESERVER);
	    	    return;
	        }

		    LOG.INFO("Bulk sync: got response from server: " + resp.getCharData() );
	    	
	        String szData = resp.getCharData();
	        JSONEntry oJsonEntry = new JSONEntry(szData);
	        strCmd = oJsonEntry.getString("result");
	        if ( oJsonEntry.hasName("url") )
	   	        strDataUrl = oJsonEntry.getString("url");
	        
	        if ( strCmd.compareTo("wait") == 0)
	        {
	            int nTimeout = RhoConf.getInstance().getInt("bulksync_timeout_sec");
	            if ( nTimeout == 0 )
	                nTimeout = 5;

	            SyncThread.sleep(nTimeout*1000);
	            strCmd = "";
	        }
	    }

	    if ( strCmd.compareTo("nop") == 0)
	    {
		    LOG.INFO("Bulk sync return no data.");
		    return;
	    }

	    String fDataName = dbPartition.getDBPath() + "_bulk.data";
	    String strHsqlDataUrl = getHostFromUrl(serverUrl) + strDataUrl + ".hsqldb.data";
	    LOG.INFO("Bulk sync: download data from server: " + strHsqlDataUrl);
	    {
		    NetResponse resp1 = getNet().pullFile(strHsqlDataUrl, fDataName, this);
		    if ( !resp1.isOK() )
		    {
			    LOG.ERROR("Bulk sync failed: cannot download database file.");
			    stopSync();
			    getNotify().fireBulkSyncNotification(true, RhoRuby.ERR_REMOTESERVER);
			    return;
		    }
	    }
	    
	    String fScriptName = dbPartition.getDBPath() + "_bulk.script";
	    String strHsqlScriptUrl = getHostFromUrl(serverUrl) + strDataUrl + ".hsqldb.script";
	    LOG.INFO("Bulk sync: download script from server: " + strHsqlScriptUrl);
	    {
		    NetResponse resp1 = getNet().pullFile(strHsqlScriptUrl, fScriptName, this);
		    if ( !resp1.isOK() )
		    {
			    LOG.ERROR("Bulk sync failed: cannot download database file.");
			    stopSync();
			    getNotify().fireBulkSyncNotification(true, RhoRuby.ERR_REMOTESERVER);
			    return;
		    }
	    }
	    
		LOG.INFO("Bulk sync: change db");
	    
	    dbPartition.setBulkSyncDB(fDataName, fScriptName);
	}
	
	int getStartSource()
	{
	    for( int i = 0; i < m_sources.size(); i++ )
	    {
	        SyncSource src = (SyncSource)m_sources.elementAt(i);
	        if ( !src.isEmptyToken() )
	            return i;
	    }
	
	    return 0;
	}

	void syncAllSources()throws Exception
	{
		//TODO: do not stop on error source
		boolean bError = false;
	    for( int i = getStartSource(); i < m_sources.size() && isContinueSync(); i++ )
	    {
	    	SyncSource src = null;
	    	try{
		        src = (SyncSource)m_sources.elementAt(i);
		        if ( isSessionExist() && getState() != esStop )
		            src.sync();
		
	    	}catch(Exception exc)
	    	{
		    	if ( src.m_nErrCode == RhoRuby.ERR_NONE )
		    		src.m_nErrCode = RhoRuby.ERR_RUNTIME;
		    	
		    	setState(esStop);
	    		throw exc;
	    	}finally{
	    		getNotify().onSyncSourceEnd( i, m_sources );
	    		bError = src.m_nErrCode != RhoRuby.ERR_NONE;
	    	}
	    }
	    
	    if ( !bError)
	    	getNotify().fireSyncNotification(null, true, RhoRuby.ERR_NONE, RhoRuby.getMessageText("sync_completed"));
	}
	
	void callLoginCallback(String callback, int nErrCode, String strMessage)
	{
		try{
		    String strBody = "error_code=" + nErrCode;
	        strBody += "&error_message=" + URI.urlEncode(strMessage != null? strMessage : "");
	        strBody += "&rho_callback=1";
	        
	        String strUrl = getNet().resolveUrl(callback);
	        
			LOG.INFO( "Login callback: " + callback + ". Body: "+ strBody );
	
		    NetResponse resp = getNet().pushData( strUrl, strBody, null );
		    if ( !resp.isOK() )
		        LOG.ERROR( "Call Login callback failed. Code: " + resp.getRespCode() + "; Error body: " + resp.getCharData() );
		}catch(Exception exc)
		{
			LOG.ERROR("Call Login callback failed.", exc);
		}
	}
	
	void login(String name, String password, String callback)
	{
		try {
		    NetResponse resp = null;
			
		    try{
				
			    resp = getNet().pullCookies( getProtocol().getLoginUrl(), getProtocol().getLoginBody(name, password), this );
			    
			    if ( resp.isUnathorized() )
			    {
			        callLoginCallback(callback, RhoRuby.ERR_UNATHORIZED, resp.getCharData());
			    	return;
			    }
			    
			    if ( !resp.isOK() )
			    {
			    	callLoginCallback(callback, RhoRuby.ERR_REMOTESERVER, resp.getCharData());
			    	return;
			    }
		    }catch(IOException exc)
		    {
				LOG.ERROR("Login failed.", exc);
		    	callLoginCallback(callback, RhoRuby.getNetErrorCode(exc), "" );
		    	return;
		    }
		    
		    String strSession = resp.getCharData();
		    if ( strSession == null || strSession.length() == 0 )
		    {
		    	LOG.ERROR("Return empty session.");
		    	callLoginCallback(callback, RhoRuby.ERR_UNEXPECTEDSERVERRESPONSE, "" );
		        return;
		    }
		    
		    IDBResult res = getDB().executeSQL("SELECT * FROM client_info");
		    if ( !res.isEnd() )
		        getDB().executeSQL( "UPDATE client_info SET session=?", strSession );
		    else
		        getDB().executeSQL("INSERT INTO client_info (session) values (?)", strSession);
		
		    //if ( ClientRegister.getInstance() != null )
		    //	ClientRegister.getInstance().stopWait();
		    
	    	callLoginCallback(callback, RhoRuby.ERR_NONE, "" );
		    
		}catch(Exception exc)
		{
			LOG.ERROR("Login failed.", exc);
	    	callLoginCallback(callback, RhoRuby.ERR_RUNTIME, "" );
		}
	}

	boolean isLoggedIn()throws DBException
	{
	    int nCount = 0;
	    IDBResult res = getDB().executeSQL("SELECT count(session) FROM client_info WHERE session IS NOT NULL");
	    
	    if ( !res.isEnd() )
	        nCount = res.getIntByIdx(0);
	    
	    return nCount > 0;
	}

	String loadSession()throws DBException
	{
	    String strRes = "";
	    IDBResult res = getDB().executeSQL("SELECT session FROM client_info WHERE session IS NOT NULL");
	    
	    if ( !res.isEnd() )
	    	strRes = res.getStringByIdx(0);
	    
	    return strRes;
	}
	
	public void logout()throws Exception
	{
	    getDB().executeSQL( "UPDATE client_info SET session = NULL");
	    m_strSession = "";
	
	    loadAllSources();
	}
	
	public void setSyncServer(String url)throws Exception
	{
		RhoConf.getInstance().setPropertyByName("syncserver", url);
		RhoConf.getInstance().saveToFile();
		RhoConf.getInstance().loadConf();
		
		getDB().executeSQL("DELETE FROM client_info");
		
		logout();
	}
	
	static String getHostFromUrl( String strUrl )
	{
		URI uri = new URI(strUrl);
		return uri.getHostSpecificPart() + "/";
	}
	
}
