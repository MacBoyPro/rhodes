package com.rho.net;

import java.io.IOException;

public interface INetworkAccess {

	public abstract void configure();
	public abstract IHttpConnection connect(String server, boolean bDownloadfile) throws IOException;
	public abstract void close();
	public abstract long getMaxPacketSize();
	
	public abstract String getHomeUrl();
}
