#include "SyncEngine.h"
#include "SyncSource.h"

#include "json/JSONIterator.h"
#include "common/RhoConf.h"
#include "common/StringConverter.h"
#include "sync/ClientRegister.h"
#include "net/URI.h"
#include "statistic/RhoProfiler.h"
#include "ruby/ext/rho/rhoruby.h"
#include "SyncProtocol_1.h"
#include "SyncProtocol_3.h"

namespace rho {
const _CRhoRuby& RhoRuby = _CRhoRuby();

/*static*/ String _CRhoRuby::getMessageText(const char* szName)
{
    return rho_ruby_getMessageText(szName);
}

/*static*/ String _CRhoRuby::getErrorText(int nError)
{
    return rho_ruby_getErrorText(nError);
}

namespace sync {
IMPLEMENT_LOGCLASS(CSyncEngine,"Sync");

using namespace rho::net;
using namespace rho::common;
using namespace rho::json;

CSyncEngine::CSyncEngine(db::CDBAdapter& db): m_dbAdapter(db), m_NetRequest(0), m_syncState(esNone), m_oSyncNotify(*this)
{
    m_bStopByUser = false;
    m_nSyncPageSize = 2000;

    initProtocol();
}

String CSyncEngine::SYNC_PAGE_SIZE() { return convertToStringA(m_nSyncPageSize); }

void CSyncEngine::initProtocol()
{
    int nVersion = RHOCONF().getInt("sync_version");
    if ( nVersion == 3 )
        m_SyncProtocol = new CSyncProtocol_3();
    else
        m_SyncProtocol = new CSyncProtocol_1();
}

void CSyncEngine::doSyncAllSources()
{
    setState(esSyncAllSources);
    m_bStopByUser = false;
    loadAllSources();

    m_strSession = loadSession();
    if ( isSessionExist()  )
    {
        m_clientID = loadClientID();
        getNotify().cleanLastSyncObjectCount();

	    PROF_CREATE_COUNTER("Net");	    
	    PROF_CREATE_COUNTER("Parse");
	    PROF_CREATE_COUNTER("DB");
	    PROF_CREATE_COUNTER("Data");
	    PROF_CREATE_COUNTER("Data1");
	    PROF_CREATE_COUNTER("Pull");
	    PROF_START("Sync");

        syncAllSources();

	    PROF_DESTROY_COUNTER("Net");	    
	    PROF_DESTROY_COUNTER("Parse");
	    PROF_DESTROY_COUNTER("DB");
	    PROF_DESTROY_COUNTER("Data");
	    PROF_DESTROY_COUNTER("Data1");
	    PROF_DESTROY_COUNTER("Pull");
	    PROF_STOP("Sync");

    }
    else
    {
        if ( m_sources.size() > 0 )
        {
            CSyncSource& src = *m_sources.elementAt(getStartSource());
    	    //src.m_strError = "Client is not logged in. No sync will be performed.";
            src.m_nErrCode = RhoRuby.ERR_CLIENTISNOTLOGGEDIN;

            getNotify().fireSyncNotification(&src, true, src.m_nErrCode, "");
        }else
            getNotify().fireSyncNotification(null, true, RhoRuby.ERR_CLIENTISNOTLOGGEDIN, "");
    }

    if ( getState() != esExit )
        setState(esNone);
}

void CSyncEngine::doSyncSource(const CSourceID& oSrcID, String strParams, String strAction, boolean bSearchSyncChanges, int nProgressStep)
{
    setState(esSyncSource);
    m_bStopByUser = false;
    loadAllSources();

    CSyncSource* pSrc = findSource(oSrcID);
    if ( pSrc != null )
    {
        CSyncSource& src = *pSrc;

        LOG(INFO) +"Started synchronization of the data source: " + src.getName();

    	src.m_strParams = strParams;
    	src.m_strAction = strAction;
    	src.m_bSearchSyncChanges = bSearchSyncChanges;
        src.m_nProgressStep = nProgressStep;

	    m_strSession = loadSession();
	    if ( isSessionExist()  ) {
	    	m_clientID = loadClientID();
	        if ( getState() != esStop )
            {
                getNotify().cleanLastSyncObjectCount();
	            src.sync();
            }
	    } else {
	    	//src.m_strError = "Client is not logged in. No sync will be performed.";
            src.m_nErrCode = RhoRuby.ERR_CLIENTISNOTLOGGEDIN;
	    }

        getNotify().fireSyncNotification(&src, true, src.m_nErrCode, src.m_nErrCode == RhoRuby.ERR_NONE ? RhoRuby.getMessageText("sync_completed") : "");
    }else
    {
        LOG(ERROR) + "Sync one source : Unknown Source " + oSrcID.toString();

        CSyncSource src(*this);
    	//src.m_strError = "Unknown sync source.";
        src.m_nErrCode = RhoRuby.ERR_RUNTIME;

        getNotify().fireSyncNotification(&src, true, src.m_nErrCode, "");
    }

    getNotify().cleanCreateObjectErrors();

    if ( getState() != esExit )
        setState(esNone);
}

CSyncSource* CSyncEngine::findSource(const CSourceID& oSrcID)
{
    for( int i = 0; i < (int)m_sources.size(); i++ )
    {
        CSyncSource& src = *m_sources.elementAt(i);
        if ( oSrcID.isEqual(src) )
            return &src;
    }
    
    return null;
}

CSyncSource* CSyncEngine::findSourceByName(const String& strSrcName)
{
    CSourceID oSrcID;
    oSrcID.m_strName = strSrcName;
    return findSource(oSrcID);
}

void CSyncEngine::loadAllSources()
{
    m_sources.clear();

    DBResult( res, getDB().executeSQL("SELECT source_id,source_url,token,name from sources ORDER BY priority") );
    for ( ; !res.isEnd(); res.next() )
    { 
        String strDbUrl = res.getStringByIdx(1);
        if ( strDbUrl.length() == 0 )
            continue;

        if ( strDbUrl.at(0) == '/' || strDbUrl.at(0) == '\\' )
            strDbUrl.erase(strDbUrl.begin());

		String strUrl = strDbUrl.find("http") == 0 ? strDbUrl : (RHOCONF().getPath("syncserver") + strDbUrl);
        if ( strUrl.at(strUrl.length()-1) == '/' || strUrl.at(strUrl.length()-1) == '\\' )
            strUrl.erase(strUrl.end()-1);

        String strName = res.getStringByIdx(3);
        if ( strUrl.length() > 0 )
            m_sources.addElement( new CSyncSource( res.getIntByIdx(0), strUrl, strName, res.getUInt64ByIdx(2), *this) );
    }
}

String CSyncEngine::loadClientID()
{
    String clientID = "";
    synchronized(m_mxLoadClientID)
    {
        boolean bResetClient = false;
        {
            DBResult( res, getDB().executeSQL("SELECT client_id,reset from client_info limit 1") );
            if ( !res.isEnd() )
            {
                clientID = res.getStringByIdx(0);
                bResetClient = res.getIntByIdx(1) > 0;
            }
        }

        if ( clientID.length() == 0 )
        {
            clientID = requestClientIDByNet();

            getDB().executeSQL("DELETE FROM client_info");
            getDB().executeSQL("INSERT INTO client_info (client_id) values (?)", clientID);
        }else if ( bResetClient )
        {
    	    if ( !resetClientIDByNet(clientID) )
    		    stopSync();
    	    else
    		    getDB().executeSQL("UPDATE client_info SET reset=? where client_id=?", 0, clientID );	    	
        }
    }
    return clientID;
}

boolean CSyncEngine::resetClientIDByNet(const String& strClientID)//throws Exception
{
    NetResponse( resp, getNet().pullData(getProtocol().getClientResetUrl(strClientID), this) );
    return resp.isOK();
}

String CSyncEngine::requestClientIDByNet()
{
    NetResponse(resp,getNet().pullData(getProtocol().getClientCreateUrl(), this));
    if ( resp.isOK() && resp.getCharData() != null )
    {
        const char* szData = resp.getCharData();
        CJSONEntry oJsonEntry(szData);

        CJSONEntry oJsonObject = oJsonEntry.getEntry("client");
        if ( !oJsonObject.isEmpty() )
            return oJsonObject.getString("client_id");
    }

    return "";
}

int CSyncEngine::getStartSource()
{
    for( int i = 0; i < (int)m_sources.size(); i++ )
    {
        CSyncSource& src = *m_sources.elementAt(i);
        if ( !src.isEmptyToken() )
            return i;
    }

    return 0;
}

void CSyncEngine::syncAllSources()
{
    boolean bError = false;
    for( int i = getStartSource(); i < (int)m_sources.size() && isContinueSync(); i++ )
    {
        CSyncSource& src = *m_sources.elementAt(i);
        if ( isSessionExist() && getState() != esStop )
            src.sync();

        getNotify().onSyncSourceEnd(i, m_sources);
        bError = src.m_nErrCode != RhoRuby.ERR_NONE;
    }

    if ( !bError)
    	getNotify().fireSyncNotification(null, true, RhoRuby.ERR_NONE, RhoRuby.getMessageText("sync_completed"));
}

void CSyncEngine::callLoginCallback(String callback, int nErrCode, String strMessage)
{
	//try{
    String strBody = "error_code=" + convertToStringA(nErrCode);
    strBody += "&error_message=";
    URI::urlEncode(strMessage, strBody);

    String strUrl = getNet().resolveUrl(callback);
    
	LOG(INFO) + "Login callback: " + callback + ". Body: "+ strBody;

    NetResponse( resp, getNet().pushData( strUrl, strBody, null ) );
    if ( !resp.isOK() )
        LOG(ERROR) + "Call Login callback failed. Code: " + resp.getRespCode() + "; Error body: " + resp.getCharData();
	//}catch(Exception exc)
	//{
	//	LOG.ERROR("Call Login callback failed.", exc);
	//}
}

static String getServerFromUrl( const String& strUrl );
boolean CSyncEngine::checkAllSourcesFromOneDomain()//throws Exception
{
	loadAllSources();
	
    if ( m_sources.size() == 0 )
        return true;

    //All sources should be from one domain
    CSyncSource& src0 = *m_sources.elementAt(0);
    String srv0 = getServerFromUrl(src0.getUrl());
    for( int i = 1; i < (int)m_sources.size(); i++ )
    {
        CSyncSource& src = *m_sources.elementAt(i);
        String srv = getServerFromUrl(src.getUrl());
        if ( srv.compare( srv0 ) != 0 )
            return false;
    }
	
    return true;
}

void CSyncEngine::login(String name, String password, String callback)
{
    PROF_START("Login");
	//try {
	if ( !checkAllSourcesFromOneDomain() )
	{
        callLoginCallback(callback, RhoRuby.ERR_DIFFDOMAINSINSYNCSRC, "");
    	return;
	}

    NetResponse( resp, getNet().pullCookies( getProtocol().getLoginUrl(), getProtocol().getLoginBody(name, password), this ) );
    
    if ( !resp.isResponseRecieved())
    {
        callLoginCallback(callback, RhoRuby.ERR_NETWORK, resp.getCharData());
        return;
    }

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

    String strSession = resp.getCharData();
    if ( strSession.length() == 0 )
    {
    	LOG(ERROR) + "Return empty session.";
    	callLoginCallback(callback, RhoRuby.ERR_UNEXPECTEDSERVERRESPONSE, "" );
        return;
    }

    getDB().executeSQL( "UPDATE sources SET session=?", strSession );

    if ( CClientRegister::getInstance() != null )
        CClientRegister::getInstance()->stopWait();
    
    callLoginCallback(callback, RhoRuby.ERR_NONE, "" );
	
    PROF_STOP("Login");
	//}catch(Exception exc)
	//{
	//	LOG.ERROR("Login failed.", exc);
    //	callLoginCallback(callback, RhoRuby.ERR_RUNTIME, exc.getMessage() );
	//}
}

boolean CSyncEngine::isLoggedIn()
 {
    int nCount = 0;
    DBResult( res , getDB().executeSQL("SELECT count(session) FROM sources") );
     if ( !res.isEnd() )
        nCount = res.getIntByIdx(0);

    return nCount > 0;
}

String CSyncEngine::loadSession()
{
    String strRes = "";
    DBResult( res , getDB().executeSQL("SELECT session FROM sources WHERE session IS NOT NULL") );
    
    if ( !res.isEnd() )
    	strRes = res.getStringByIdx(0);
    
    return strRes;
}

void CSyncEngine::logout()
{
    getDB().executeSQL( "UPDATE sources SET session=NULL" );
    m_strSession = "";
    getNet().deleteCookie("");

    loadAllSources();
    for( int i = 0; i < (int)m_sources.size(); i++ )
    {
        CSyncSource& src = *m_sources.elementAt(i);
        getNet().deleteCookie(src.getUrl());
    }

}
	
void CSyncEngine::setSyncServer(char* syncserver)
{
	rho_conf_setString("syncserver", syncserver);
	rho_conf_save();
	logout();
}

static String getServerFromUrl( const String& strUrl )
{
    const char* url = strUrl.c_str();
    const char* pStartSrv, *pEndSrv;
    int nSrvLen;
    const char* pHttp = strstr(url,"://");
    if ( !pHttp )
        pHttp = strstr(url,":\\\\");

    if ( pHttp )
        pStartSrv = pHttp+3;
    else
        pStartSrv = url;

    pEndSrv = strchr( pStartSrv, '/');
    if ( !pEndSrv )
        pEndSrv = strchr( pStartSrv, '\\');

    nSrvLen = pEndSrv ? (pEndSrv - pStartSrv) : strlen(pStartSrv);
    return String(pStartSrv, nSrvLen);
}

String CSyncEngine::CSourceID::toString()const
{
    if ( m_strName.length() > 0 )
        return "name : " + m_strName;
    else if ( m_strUrl.length() > 0 )
        return "url : " + m_strUrl;

    return "# : " + convertToStringA(m_nID);
}

boolean CSyncEngine::CSourceID::isEqual(CSyncSource& src)const
{
    if ( m_strName.length() > 0 )
        return src.getName().compare(m_strName)==0;
    else if ( m_strUrl.length() > 0 )
        return src.getUrl().compare(m_strUrl)==0;

    return m_nID == src.getID();
}
}
}
