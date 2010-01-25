package com.rho;

import com.rho.db.IDBStorage;
import com.xruby.runtime.builtin.RubyArray;
import com.xruby.runtime.lang.RubyProgram;
import com.rho.file.*;

public interface IRhoRubyHelper {
	public abstract void initRubyExtensions();
	public abstract RubyProgram createMainObject();
	public abstract String getPlatform();
	
	public abstract IDBStorage createDBStorage();
	public abstract IFileAccess createFileAccess();
	public abstract IRAFile createRAFile();
	
	public void loadBackTrace(RubyArray backtrace);
	public String getDeviceId();
	public boolean hasNetwork();
	
	public String getAppProperty(String name);
	public boolean isSimulator();
	
	public void showLog();
	
	public String getLocale();
	
	public int getScreenWidth();
	public int getScreenHeight();
}
